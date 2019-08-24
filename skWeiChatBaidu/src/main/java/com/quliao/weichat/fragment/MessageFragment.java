package com.quliao.weichat.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.handmark.pulltorefresh.library.PullToRefreshBase;
import com.roamer.slidelistview.SlideBaseAdapter;
import com.roamer.slidelistview.SlideListView;
import com.quliao.weichat.AppConstant;
import com.quliao.weichat.MyApplication;
import com.quliao.weichat.R;
import com.quliao.weichat.Reporter;
import com.quliao.weichat.bean.Friend;
import com.quliao.weichat.bean.PrivacySetting;
import com.quliao.weichat.bean.RoomMember;
import com.quliao.weichat.bean.message.ChatMessage;
import com.quliao.weichat.bean.message.XmppMessage;
import com.quliao.weichat.broadcast.MsgBroadcast;
import com.quliao.weichat.db.InternationalizationHelper;
import com.quliao.weichat.db.dao.ChatMessageDao;
import com.quliao.weichat.db.dao.FriendDao;
import com.quliao.weichat.db.dao.RoomMemberDao;
import com.quliao.weichat.helper.AvatarHelper;
import com.quliao.weichat.helper.DialogHelper;
import com.quliao.weichat.helper.PrivacySettingHelper;
import com.quliao.weichat.pay.PaymentActivity;
import com.quliao.weichat.pay.sk.SKPayActivity;
import com.quliao.weichat.ui.MainActivity;
import com.quliao.weichat.ui.base.EasyFragment;
import com.quliao.weichat.ui.groupchat.FaceToFaceGroup;
import com.quliao.weichat.ui.groupchat.SelectContactsActivity;
import com.quliao.weichat.ui.me.NearPersonActivity;
import com.quliao.weichat.ui.message.ChatActivity;
import com.quliao.weichat.ui.message.MucChatActivity;
import com.quliao.weichat.ui.message.multi.RoomInfoActivity;
import com.quliao.weichat.ui.nearby.PublicNumberSearchActivity;
import com.quliao.weichat.ui.nearby.UserSearchActivity;
import com.quliao.weichat.ui.other.BasicInfoActivity;
import com.quliao.weichat.util.Constants;
import com.quliao.weichat.util.HtmlUtils;
import com.quliao.weichat.util.HttpUtil;
import com.quliao.weichat.util.PreferenceUtils;
import com.quliao.weichat.util.StringUtils;
import com.quliao.weichat.util.TimeUtils;
import com.quliao.weichat.util.ToastUtil;
import com.quliao.weichat.util.UiUtils;
import com.quliao.weichat.util.ViewHolder;
import com.quliao.weichat.view.ClearEditText;
import com.quliao.weichat.view.HeadView;
import com.quliao.weichat.view.MessagePopupWindow;
import com.quliao.weichat.view.PullToRefreshSlideListView;
import com.quliao.weichat.view.VerifyDialog;
import com.quliao.weichat.xmpp.ListenerManager;
import com.quliao.weichat.xmpp.XmppConnectionManager;
import com.quliao.weichat.xmpp.listener.AuthStateListener;
import com.quliao.weichat.xmpp.listener.ChatMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static com.quliao.weichat.bean.Friend.ID_SYSTEM_MESSAGE;

/**
 * 消息界面
 */
public class MessageFragment extends EasyFragment implements AuthStateListener, ChatMessageListener {
    // 消息界面在前台展示中就不响铃新消息，
    public static boolean foreground = false;
    private TextView mTvTitle;
    private ImageView mIvTitleRight;
    private View mHeadView;
    private ClearEditText mEditText;
    private boolean search;
    private LinearLayout mNetErrorLl;
    private PullToRefreshSlideListView mListView;
    private MessageListAdapter mAdapter;
    private List<Friend> mFriendList;
    private String mLoginUserId;
    private MessagePopupWindow mMessagePopupWindow;
    // 上次刷新时间，限制过快刷新，
    private long refreshTime;
    private boolean timerRunning;
    // 刷新的定时器，限制过快刷新，
    private CountDownTimer timer = new CountDownTimer(1000, 1000) {
        @Override
        public void onTick(long millisUntilFinished) {

        }

        @Override
        public void onFinish() {
            timerRunning = false;
            refreshTime = System.currentTimeMillis();
            refresh();
        }
    };
    private BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TextUtils.isEmpty(action)) {
                return;
            }
            if (action.equals(MsgBroadcast.ACTION_MSG_UI_UPDATE)) {// 刷新页面
                long lastRefreshTime = refreshTime;
                long delta = System.currentTimeMillis() - lastRefreshTime;
                if (delta > TimeUnit.SECONDS.toMillis(1)) {
                    refreshTime = System.currentTimeMillis();
                    refresh();
                } else if (!timerRunning) {
                    timerRunning = true;
                    timer.start();
                }
            } else if (action.equals(Constants.NOTIFY_MSG_SUBSCRIPT)) {
                Friend friend = (Friend) intent.getSerializableExtra(AppConstant.EXTRA_FRIEND);
                if (friend != null) {
                    clearMessageNum(friend);
                }
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {// 网络发生改变
                if (!HttpUtil.isGprsOrWifiConnected(getActivity())) {
                    mNetErrorLl.setVisibility(View.VISIBLE);
                } else {
                    mNetErrorLl.setVisibility(View.GONE);
                }
            } else if (action.equals(Constants.NOT_AUTHORIZED)) {
                mTvTitle.setText(getString(R.string.password_error));
            }
        }
    };

    private void refresh() {
        if (!TextUtils.isEmpty(mEditText.getText().toString().trim())) {
            mEditText.setText("");// 内部调用了loadData
        } else {
            loadDatas();
        }
    }

    @Override
    protected int inflateLayoutId() {
        return R.layout.fragment_message;
    }

    @Override
    protected void onActivityCreated(Bundle savedInstanceState, boolean createView) {
        initActionBar();
        // 不能用createView判断不初始化，因为Fragment复用时老activity可能被销毁了，
        initView();
        loadDatas();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        foreground = isVisibleToUser;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        foreground = !hidden;
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        foreground = true;
        int authState = XmppConnectionManager.mXMPPCurrentState;
        if (authState == 0 || authState == 1) {
            findViewById(R.id.pb_title_center).setVisibility(View.VISIBLE);
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_GoingOff"));
        } else if (authState == 2) {
            findViewById(R.id.pb_title_center).setVisibility(View.GONE);
            //mTvTitle.setText(getString(R.string.message));
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_OnLine"));
        } else {
            findViewById(R.id.pb_title_center).setVisibility(View.GONE);
            //mTvTitle.setText(getString(R.string.message));
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_OffLine"));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getActivity().unregisterReceiver(mUpdateReceiver);
        ListenerManager.getInstance().removeAuthStateChangeListener(this);
        ListenerManager.getInstance().removeChatMessageListener(this);
    }

    private void initActionBar() {
        findViewById(R.id.iv_title_left).setVisibility(View.GONE);
        mTvTitle = (TextView) findViewById(R.id.tv_title_center);
        mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_OffLine"));
        appendClick(mTvTitle);

        mIvTitleRight = (ImageView) findViewById(R.id.iv_title_right);
        mIvTitleRight.setImageResource(R.drawable.ic_app_add);
        appendClick(mIvTitleRight);
    }

    private void initView() {
        mLoginUserId = coreManager.getSelf().getUserId();

        mFriendList = new ArrayList<>();

        LayoutInflater inflater = LayoutInflater.from(getContext());
        if (mHeadView != null) {
            // Fragment复用时可能已经添加过headerView了，
            mListView.getRefreshableView().removeHeaderView(mHeadView);
        }
        mHeadView = inflater.inflate(R.layout.head_for_messagefragment, null);
        mEditText = (ClearEditText) mHeadView.findViewById(R.id.search_edit);
        mNetErrorLl = (LinearLayout) mHeadView.findViewById(R.id.net_error_ll);
        mNetErrorLl.setOnClickListener(this);

        mListView = (PullToRefreshSlideListView) findViewById(R.id.pull_refresh_list);
        mListView.getRefreshableView().addHeaderView(mHeadView, null, false);
        mAdapter = new MessageListAdapter(getActivity());
        mListView.setAdapter(mAdapter);
        mListView.getRefreshableView().setAdapter(mAdapter);
        mListView.setMode(PullToRefreshBase.Mode.PULL_FROM_START);
        mListView.setOnRefreshListener(new PullToRefreshBase.OnRefreshListener<SlideListView>() {
            @Override
            public void onRefresh(PullToRefreshBase<SlideListView> refreshView) {
                refresh();
            }
        });

        mListView.getRefreshableView().setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) {
                hardLine();

                // 在跳转之前关闭软键盘
                InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(INPUT_METHOD_SERVICE);
                if (inputManager != null) {
                    inputManager.hideSoftInputFromWindow(findViewById(R.id.message_fragment).getWindowToken(), 0); // 强制隐藏键盘
                }

                position = (int) arg3;
                Friend friend = mFriendList.get(position);
                Intent intent = new Intent();
                if (friend.getRoomFlag() == 0) { // 个人
                    if (TextUtils.equals(friend.getUserId(), Friend.ID_SK_PAY)) {
                        intent.setClass(getActivity(), SKPayActivity.class);
                    } else {
                        intent.setClass(getActivity(), ChatActivity.class);
                        intent.putExtra(ChatActivity.FRIEND, friend);
                    }
                } else {
                    intent.setClass(getActivity(), MucChatActivity.class);
                    intent.putExtra(AppConstant.EXTRA_USER_ID, friend.getUserId());
                    intent.putExtra(AppConstant.EXTRA_NICK_NAME, friend.getNickName());
                }
                if (search) {
                    intent.putExtra("isserch", true);
                    // 为什么改取为chatRecordTimeOut，可至queryChatMessageByContent方法内查看原因
                    // intent.putExtra("jilu_id", friend.getTimeSend());
                    intent.putExtra("jilu_id", friend.getChatRecordTimeOut());
                } else {
                    intent.putExtra(Constants.NEW_MSG_NUMBER, friend.getUnReadNum());
                }
                startActivity(intent);
                clearMessageNum(friend);
            }
        });

        mEditText.setHint(InternationalizationHelper.getString("JX_SearchChatLog"));
        mEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String str = s.toString().trim();
                if (!TextUtils.isEmpty(str)) {
                    queryChatMessage(str);
                } else {
                    loadDatas();
                }
            }
        });

        ListenerManager.getInstance().addAuthStateChangeListener(this);
        ListenerManager.getInstance().addChatMessageListener(this);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MsgBroadcast.ACTION_MSG_UI_UPDATE);// 刷新页面Ui
        intentFilter.addAction(Constants.NOTIFY_MSG_SUBSCRIPT);// 刷新"消息"角标
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);// 网络发生改变
        intentFilter.addAction(Constants.NOT_AUTHORIZED);// XMPP密码错误
        getActivity().registerReceiver(mUpdateReceiver, intentFilter);

        mTvTitle.postDelayed(() -> updataListView(), 2000);
    }

    /**
     * 加载朋友数据
     */
    private void loadDatas() {
        if (mFriendList != null) {
            mFriendList.clear();
        }
        search = false;
        mFriendList = FriendDao.getInstance().getNearlyFriendMsg(mLoginUserId);
        List<Friend> mRemoveFriend = new ArrayList<>();
        if (mFriendList.size() > 0) {
            for (int i = 0; i < mFriendList.size(); i++) {
                Friend friend = mFriendList.get(i);
                if (friend != null) {
                    if (friend.getUserId().equals(Friend.ID_NEW_FRIEND_MESSAGE)
                            || friend.getUserId().equals(mLoginUserId)) {
                        mRemoveFriend.add(friend);
                    }
                }
            }
            mFriendList.removeAll(mRemoveFriend);
        }

        mTvTitle.postDelayed(new Runnable() {
            @Override
            public void run() {
                updataListView();
                mListView.onRefreshComplete();
            }
        }, 200);
    }

    private void clearMessageNum(Friend friend) {
        friend.setUnReadNum(0);
        FriendDao.getInstance().markUserMessageRead(mLoginUserId, friend.getUserId());
        MainActivity mMainActivity = (MainActivity) getActivity();
        if (mMainActivity != null) {
            mMainActivity.updateNumData();
        }
        for (Friend mF : mFriendList) {
            if (Objects.equals(mF.getUserId(), friend.getUserId())) {
                mF.setUnReadNum(0);
                updataListView();
                break;
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.tv_title_center:
                hardLine();
                break;
            case R.id.iv_title_right:
                mMessagePopupWindow = new MessagePopupWindow(getActivity(), this, coreManager);
                mMessagePopupWindow.getContentView().measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                mMessagePopupWindow.showAsDropDown(v,
                        -(mMessagePopupWindow.getContentView().getMeasuredWidth() - v.getWidth() / 2 - 40),
                        0);
                break;
            case R.id.search_public_number:
                // 搜索公众号
                mMessagePopupWindow.dismiss();
                PublicNumberSearchActivity.start(requireContext());
                break;
            case R.id.create_group:
                // 发起群聊
                mMessagePopupWindow.dismiss();
                startActivity(new Intent(getActivity(), SelectContactsActivity.class));
                break;
            case R.id.face_group:
                mMessagePopupWindow.dismiss();
                // 面对面建群
                startActivity(new Intent(getActivity(), FaceToFaceGroup.class));
                break;
            case R.id.add_friends:
                // 添加朋友
                mMessagePopupWindow.dismiss();
                startActivity(new Intent(getActivity(), UserSearchActivity.class));
                break;
            case R.id.scanning:
                // 扫一扫
                mMessagePopupWindow.dismiss();
                MainActivity.requestQrCodeScan(getActivity());
                break;
            case R.id.receipt_payment:
                // 收付款
                mMessagePopupWindow.dismiss();
                startActivity(new Intent(getActivity(), PaymentActivity.class));
                break;
            case R.id.near_person:
                // 附近的人
                mMessagePopupWindow.dismiss();
                startActivity(new Intent(getActivity(), NearPersonActivity.class));
                break;
            case R.id.net_error_ll:
                //网络错误
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                break;
        }
    }

    /**
     * 查询聊天记录
     */
    private void queryChatMessage(String str) {
        List<Friend> data = new ArrayList<>();
        mFriendList = FriendDao.getInstance().getNearlyFriendMsg(mLoginUserId);
        for (int i = 0; i < mFriendList.size(); i++) {
            Friend friend = mFriendList.get(i);
            List<Friend> friends = ChatMessageDao.getInstance().queryChatMessageByContent(friend, str);
            if (friends != null && friends.size() > 0) {
                data.addAll(friends);
            }
        }

        if (mFriendList != null) {
            mFriendList.clear();
        }

        search = true;
        mFriendList.addAll(data);
        updataListView();
    }

    /**
     * 更新列表
     */
    private void updataListView() {
        mAdapter.setData(mFriendList);
    }

    /**
     * xmpp在线状态监听
     */
    @Override
    public void onAuthStateChange(int authState) {
        authState = XmppConnectionManager.mXMPPCurrentState;
        if (mTvTitle == null) {
            return;
        }
        if (authState == 0 || authState == 1) {
            // 登录中
            findViewById(R.id.pb_title_center).setVisibility(View.VISIBLE);
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_GoingOff"));
        } else if (authState == 2) {
            // 在线
            DialogHelper.dismissProgressDialog();
            MainActivity.isAuthenticated = true;
            findViewById(R.id.pb_title_center).setVisibility(View.GONE);
            //mTvTitle.setText(getString(R.string.message));
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_OnLine"));
            mNetErrorLl.setVisibility(View.GONE);// 网络判断对部分手机有时会失效，坐下兼容(当xmpp在线时，隐藏网络提示)
        } else {
            // 离线
            DialogHelper.dismissProgressDialog();
            findViewById(R.id.pb_title_center).setVisibility(View.GONE);
            //mTvTitle.setText(getString(R.string.message));
            mTvTitle.setText(InternationalizationHelper.getString("JXMsgViewController_OffLine"));
        }
    }

    public boolean hardLine() {// 手动重连
        int authState = XmppConnectionManager.mXMPPCurrentState;
        if (authState == 2) {
            return false;
        }

        if (!coreManager.isServiceReady()) {
            // 小米手机在后台运行时，CoreService经常被系统杀死，需要兼容ta
            coreManager.relogin();
        } else {
            MainActivity mActivity = (MainActivity) getActivity();
            if (mActivity != null) {
                coreManager.autoReconnectShowProgress(mActivity);
            } else {
            }
        }
        return true;
    }

    @Override
    public void onMessageSendStateChange(int messageState, String msgId) {
        updataListView();
    }

    @Override
    public boolean onNewMessage(String fromUserId, ChatMessage message, boolean isGroupMsg) {
        return false;
    }

    public void onActivityCreated() {
    }

    /**
     * 适配器
     */
    class MessageListAdapter extends SlideBaseAdapter {
        private List<Friend> mFriendList = new ArrayList<>();

        public MessageListAdapter(Context context) {
            super(context);
        }

        @Override
        public int getFrontViewId(int position) {
            return R.layout.row_nearly_message;
        }

        @Override
        public int getLeftBackViewId(int position) {
            return 0;
        }

        @Override
        public int getRightBackViewId(int position) {
            return R.layout.row_item_delete;
        }

        @Override
        public int getCount() {
            if (mFriendList != null) {
                return mFriendList.size();
            }
            return 0;
        }

        @Override
        public Object getItem(int position) {
            if (mFriendList != null) {
                return mFriendList.get(position);
            }
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        public void setData(List<Friend> friendList) {
            // adapter内部使用额外的list以解决异步操作数据源导致崩溃的问题，
            this.mFriendList = new ArrayList<>(friendList);
            notifyDataSetChanged();
        }

        /**
         * 禁止侧滑
         */
       /* @Override
        public SlideListView.SlideMode getSlideModeInPosition(int position) {
            Friend friend = mFriendList.get(position);
            if (friend != null && (friend.getUserId().equals(Friend.ID_SYSTEM_MESSAGE) || friend.getUserId().equals(Friend.ID_NEW_FRIEND_MESSAGE))) {
                return SlideListView.SlideMode.NONE;
            }
            return super.getSlideModeInPosition(position);
        }*/
        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = createConvertView(position);
            }
            RelativeLayout rl_warp = ViewHolder.get(convertView, R.id.item_friend_warp);
            HeadView avatar = ViewHolder.get(convertView, R.id.avatar_imgS);
            TextView nick_name_tv = ViewHolder.get(convertView, R.id.nick_name_tv);
            TextView tip_tv = ViewHolder.get(convertView, R.id.item_message_tip);
            final TextView content_tv = ViewHolder.get(convertView, R.id.content_tv);
            TextView num_tv = ViewHolder.get(convertView, R.id.num_tv);
            TextView time_tv = ViewHolder.get(convertView, R.id.time_tv);
            View replay_iv = ViewHolder.get(convertView, R.id.replay_iv);
            View not_push_ll = ViewHolder.get(convertView, R.id.not_push_iv);
            TextView top_tv = ViewHolder.get(convertView, R.id.top_tv);
            TextView mark_read_tv = ViewHolder.get(convertView, R.id.read_unread_tv);
            top_tv.setVisibility(View.VISIBLE);
            mark_read_tv.setVisibility(View.VISIBLE);
            TextView delete_tv = ViewHolder.get(convertView, R.id.delete_tv);

            final Friend friend = mFriendList.get(position);

            AvatarHelper.getInstance().displayAvatar(coreManager.getSelf().getUserId(), friend, avatar);
            nick_name_tv.setText(!TextUtils.isEmpty(friend.getRemarkName()) ? friend.getRemarkName() : friend.getNickName());
            tip_tv.setVisibility(View.GONE);

            if (friend.getRoomFlag() != 0) {// 群组 @
                if (friend.getIsAtMe() == 1) {
                    tip_tv.setText("[有人@我]");
                    tip_tv.setVisibility(View.VISIBLE);
                } else if (friend.getIsAtMe() == 2) {
                    tip_tv.setText("[@全体成员]");
                    tip_tv.setVisibility(View.VISIBLE);
                }
            }

            if (friend.getType() == XmppMessage.TYPE_TEXT) {// 文本消息 表情
                String s = StringUtils.replaceSpecialChar(friend.getContent());
                CharSequence content = HtmlUtils.transform200SpanString(s.replaceAll("\n", "\r\n"), true);
                // TODO: 这样匹配的话正常消息里的&8824也会被替换掉，
                if (content.toString().contains("&8824")) {// 草稿
                    content = content.toString().replaceFirst("&8824", "");

                    tip_tv.setText(InternationalizationHelper.getString("JX_Draft"));
                    tip_tv.setVisibility(View.VISIBLE);
                }
                content_tv.setText(content);
            } else {
                content_tv.setText(HtmlUtils.addSmileysToMessage(
                        ChatMessage.getSimpleContent(requireContext(), friend.getType(), friend.getContent()),
                        false
                ));
            }

            // 搜索下匹配关键字高亮显示
            if (search) {
                String text = content_tv.getText().toString();
                SpannableString spannableString = StringUtils.matcherSearchTitle(Color.parseColor("#fffa6015"),
                        text, mEditText.getText().toString());
                content_tv.setText(spannableString);
            }

            UiUtils.updateNum(num_tv, friend.getUnReadNum());
            time_tv.setText(TimeUtils.getFriendlyTimeDesc(getActivity(), friend.getTimeSend()));

            if (num_tv.getVisibility() == View.VISIBLE) {
                replay_iv.setVisibility(View.GONE);
            } else {
                replay_iv.setVisibility(View.VISIBLE);
            }
            if (friend.getUserId().equals(Friend.ID_SK_PAY)) {
                replay_iv.setVisibility(View.GONE);
            }


            //PUT CONDITIONS FOR isNOMessagePush
//            if(friend.getisNoMessagePush()==1){
//                not_push_ll.setVisibility(View.VISIBLE);
//            }else{
//                if (friend.getOfflineNoPushMsg() == 1) {
//                    not_push_ll.setVisibility(View.VISIBLE);
//                } else {
//                    not_push_ll.setVisibility(View.GONE);
//                }
//            }
//            ///////////////////////////////// PUT CONDITIONS FOR isNOMessagePush
            if (friend.getOfflineNoPushMsg() == 1) {
                not_push_ll.setVisibility(View.VISIBLE);
            } else {
                not_push_ll.setVisibility(View.GONE);
            }

            final long time = friend.getTopTime();

            if (time == 0) {
                rl_warp.setBackgroundResource(R.drawable.list_selector_background_ripple);
                top_tv.setText(InternationalizationHelper.getString("JX_Top"));
            } else {
                rl_warp.setBackgroundResource(R.color.Grey_200);
                top_tv.setText(InternationalizationHelper.getString("JX_CancelTop"));
            }
            if (friend.getUnReadNum() > 0) {
                mark_read_tv.setText(getString(R.string.mark_read));
            } else {
                mark_read_tv.setText(getString(R.string.mark_unread));
            }

            // 点击头像跳转详情
            avatar.setOnClickListener(view -> {
                if (!UiUtils.isNormalClick(view)) {
                    return;
                }
                if (friend.getRoomFlag() == 0) {   // 个人
                    if (!friend.getUserId().equals(ID_SYSTEM_MESSAGE)
                            && !friend.getUserId().equals(Friend.ID_NEW_FRIEND_MESSAGE)
                            && !friend.getUserId().equals(Friend.ID_SK_PAY)
                            && friend.getIsDevice() != 1) {
                        Intent intent = new Intent(getActivity(), BasicInfoActivity.class);
                        intent.putExtra(AppConstant.EXTRA_USER_ID, friend.getUserId());
                        startActivity(intent);
                    }
                } else {   // 群组
                    if (friend.getGroupStatus() == 0) {
                        Intent intent = new Intent(getActivity(), RoomInfoActivity.class);
                        intent.putExtra(AppConstant.EXTRA_USER_ID, friend.getUserId());
                        startActivity(intent);
                    }
                }
            });
            replay_iv.setOnClickListener(v -> {
                if (friend.getRoomFlag() != 0) {
                    // 用户可能不在群组里，
                    int status = friend.getGroupStatus();
                    if (1 == status) {
                        ToastUtil.showToast(requireContext(), R.string.tip_been_kick);
                        return;
                    } else if (2 == status) {
                        ToastUtil.showToast(requireContext(), R.string.tip_disbanded);
                        return;
                    } else if (3 == status) {
                        ToastUtil.showToast(requireContext(), R.string.tip_group_disable_by_service);
                        return;
                    }
                }
                if (!coreManager.isLogin()) {
                    ToastUtil.showToast(requireContext(), R.string.tip_xmpp_offline);
                    return;
                }
                // TODO: hint是上一条消息，如果有草稿可能会是草稿，
                DialogHelper.verify(
                        requireActivity(),
                        getString(R.string.title_replay_place_holder, nick_name_tv.getText().toString()),
                        content_tv.getText().toString(),
                        new VerifyDialog.VerifyClickListener() {
                            @Override
                            public void cancel() {

                            }

                            @Override
                            public void send(String str) {
                                if (TextUtils.isEmpty(str)) {
                                    ToastUtil.showToast(requireContext(), R.string.tip_replay_empty);
                                    return;
                                }
                                if (!coreManager.isLogin()) {
                                    Reporter.unreachable();
                                    ToastUtil.showToast(requireContext(), R.string.tip_xmpp_offline);
                                    return;
                                }
                                RoomMember member = RoomMemberDao.getInstance().getSingleRoomMember(friend.getRoomId(), mLoginUserId);
                                // 判断禁言状态，
                                if (member != null && member.getRole() == 3) {// 普通成员需要判断是否被禁言
                                    if (friend.getRoomTalkTime() > (System.currentTimeMillis() / 1000)) {
                                        ToastUtil.showToast(mContext, InternationalizationHelper.getString("HAS_BEEN_BANNED"));
                                        return;
                                    }
                                } else if (member == null) {// 也需要判断是否被禁言
                                    if (friend.getRoomTalkTime() > (System.currentTimeMillis() / 1000)) {
                                        ToastUtil.showToast(mContext, InternationalizationHelper.getString("HAS_BEEN_BANNED"));
                                        return;
                                    }
                                }
                                ChatMessage message = new ChatMessage();
                                // 文本类型，抄自，
                                // 黑名单没考虑，正常情况黑名单会删除会话，
                                message.setType(XmppMessage.TYPE_TEXT);
                                message.setFromUserId(mLoginUserId);
                                message.setFromUserName(coreManager.getSelf().getNickName());
                                message.setContent(str);
                                // 获取阅后即焚状态(因为用户可能到聊天设置界面 开启/关闭 阅后即焚，所以在onResume时需要重新获取下状态)
                                int isReadDel = PreferenceUtils.getInt(mContext, Constants.MESSAGE_READ_FIRE + friend.getUserId() + mLoginUserId, 0);
                                message.setIsReadDel(isReadDel);
                                if (1 != friend.getRoomFlag()) {
                                    PrivacySetting privacySetting = PrivacySettingHelper.getPrivacySettings(requireContext());
                                    boolean isSupport = privacySetting.getMultipleDevices() == 1;
                                    if (isSupport) {
                                        message.setFromId("android");
                                    } else {
                                        message.setFromId("youjob");
                                    }
                                }
                                if (1 == friend.getRoomFlag()) {
                                    // 是群聊，
                                    message.setToUserId(friend.getUserId());
                                    if (friend.getChatRecordTimeOut() == -1 || friend.getChatRecordTimeOut() == 0) {// 永久
                                        message.setDeleteTime(-1);
                                    } else {
                                        long deleteTime = TimeUtils.sk_time_current_time() + (long) (friend.getChatRecordTimeOut() * 24 * 60 * 60);
                                        message.setDeleteTime(deleteTime);
                                    }
                                } else if (friend.getIsDevice() == 1) {
                                    message.setToUserId(mLoginUserId);
                                    message.setToId(friend.getUserId());
                                    // 我的设备消息不过期？
                                } else {
                                    message.setToUserId(friend.getUserId());
                                    // sz 消息过期时间
                                    if (friend.getChatRecordTimeOut() == -1 || friend.getChatRecordTimeOut() == 0) {// 永久
                                        message.setDeleteTime(-1);
                                    } else {
                                        long deleteTime = TimeUtils.sk_time_current_time() + (long) (friend.getChatRecordTimeOut() * 24 * 60 * 60);
                                        message.setDeleteTime(deleteTime);
                                    }
                                }

                                                                                                    ///CALL PRIVACY SETTING PARAMETERS
                                PrivacySetting privacySetting = PrivacySettingHelper.getPrivacySettings(requireContext());
                                boolean isEncrypt = privacySetting.getIsEncrypt() == 1;
                                if (isEncrypt) {
                                    message.setIsEncrypt(1);
                                } else {
                                    message.setIsEncrypt(0);
                                }


                                message.setReSendCount(ChatMessageDao.fillReCount(message.getType()));
                                message.setPacketId(UUID.randomUUID().toString().replaceAll("-", ""));
                                message.setTimeSend(TimeUtils.sk_time_current_time());
                                // 消息保存在数据库，
                                ChatMessageDao.getInstance().saveNewSingleChatMessage(message.getFromUserId(), message.getToUserId(), message);
                                for (Friend mFriend : mFriendList) {
                                    if (mFriend.getUserId().equals(friend.getUserId())) {
                                        if (1 == friend.getRoomFlag()) {
                                            coreManager.sendMucChatMessage(message.getToUserId(), message);
                                            mFriend.setContent(message.getFromUserName() + ": " + message.getContent());
                                        } else {
                                            coreManager.sendChatMessage(message.getToUserId(), message);
                                            mFriend.setContent(message.getContent());
                                        }
                                        // 清除小红点，
                                        clearMessageNum(friend);
                                        notifyDataSetChanged();
                                        break;
                                    }
                                }
                            }
                        });

            });

            top_tv.setOnClickListener(v -> {
                if (time == 0) {
                    FriendDao.getInstance().updateTopFriend(friend.getUserId(), friend.getTimeSend());
                } else {
                    FriendDao.getInstance().resetTopFriend(friend.getUserId());
                }
                loadDatas();
            });

            mark_read_tv.setOnClickListener(v -> {
                if (friend.getUnReadNum() > 0) {
                    clearMessageNum(friend);
                } else {
                    FriendDao.getInstance().markUserMessageUnRead(mLoginUserId, friend.getUserId());
                    MsgBroadcast.broadcastMsgNumUpdate(MyApplication.getInstance(), true, 1);
                    MsgBroadcast.broadcastMsgUiUpdate(MyApplication.getInstance());
                }
            });

            delete_tv.setOnClickListener(v -> {
                String mLoginUserId = coreManager.getSelf().getUserId();
                if (friend.getRoomFlag() == 0) {
                    // 如果是普通的人，从好友表中删除最后一条消息的记录，这样就不会查出来了
                    FriendDao.getInstance().resetFriendMessage(mLoginUserId, friend.getUserId());
                    // 消息表中删除
                    ChatMessageDao.getInstance().deleteMessageTable(mLoginUserId, friend.getUserId());
                } else {
                    // 从消息表删除
                    FriendDao.getInstance().resetFriendMessage(mLoginUserId, friend.getUserId());
                }
                if (friend.getUnReadNum() > 0) {
                    MsgBroadcast.broadcastMsgNumUpdate(getActivity(), false, friend.getUnReadNum());
                }

                // 保留旧代码，0.
                // 内部和外部的mFriendList都要更新到，
                MessageFragment.this.mFriendList.remove(position);
                setData(MessageFragment.this.mFriendList);
            });
            return convertView;
        }
    }
}
