package io.agora.wawaji.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.SparseBooleanArray;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.agora.common.Constant;
import io.agora.common.Wawaji;
import io.agora.rtc.Constants;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.video.VideoCanvas;
import io.agora.wawaji.app.R;
import io.agora.wawaji.app.model.AGEventHandler;
import io.agora.wawaji.app.model.ConstantApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WawajiPlayerActivity extends BaseActivity implements AGEventHandler {

    private final static Logger log = LoggerFactory.getLogger(WawajiPlayerActivity.class);

    private final SparseBooleanArray mUidList = new SparseBooleanArray();

    private String machinelName;
    private String signalChannelName;
    private TextView textViewPlay;
    private TextView textPlayPersonName;
    private boolean isJoinSignalRoom = false;
    private boolean isPlaying = false;
    private boolean isOrder = false;
    private String playPersonName;
    private String selfName = "";
    private int queueCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wawaji_player);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return false;
    }

    private boolean isBroadcaster(int cRole) {
        return cRole == Constants.CLIENT_ROLE_BROADCASTER;
    }

    private boolean isBroadcaster() {
        return isBroadcaster(config().mClientRole);
    }

    @Override
    protected void initUIandEvent() {
        event().addEventHandler(this);

        Intent i = getIntent();
        int cRole = i.getIntExtra(ConstantApp.ACTION_KEY_CROLE, 0);

        if (cRole == 0) {
            throw new RuntimeException("Should not reach here");
        }

        Wawaji wawaji = (Wawaji) i.getSerializableExtra(ConstantApp.ACTION_KEY_ROOM_WAWAJI);
        if (wawaji != null) {

            String roomName = wawaji.getName();
            roomName = "xcapture";
            machinelName = wawaji.getName();
            signalChannelName = "room_" + machinelName;
            doConfigEngine(cRole);

            log.debug("initUIandEvent isBroadcaster(cRole) :" + isBroadcaster(cRole));

            worker().joinChannel(roomName, config().mUid);
            worker().joinSiginalChannel(signalChannelName);
            selfName = String.valueOf(worker().getEngineConfig().mUid);

            TextView textRoomName = (TextView) findViewById(R.id.room_name);
            textRoomName.setText(roomName);
        }
        textViewPlay = (TextView) findViewById(R.id.wawaji_play);
        textPlayPersonName = (TextView) findViewById(R.id.play_name);
    }

    private void doConfigEngine(int cRole) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        int prefIndex = pref.getInt(ConstantApp.PrefManager.PREF_PROPERTY_PROFILE_IDX, ConstantApp.DEFAULT_PROFILE_IDX);
        if (prefIndex > ConstantApp.VIDEO_PROFILES.length - 1) {
            prefIndex = ConstantApp.DEFAULT_PROFILE_IDX;
        }
        int vProfile = ConstantApp.VIDEO_PROFILES[prefIndex];

        worker().configEngine(cRole, vProfile);
    }

    @Override
    protected void deInitUIandEvent() {
        doLeaveChannel();
        event().removeEventHandler(this);
    }

    private void doLeaveChannel() {
        worker().leaveChannel(config().mChannel);

        if (isBroadcaster()) {
            worker().leaveSiginalChannel(signalChannelName);
        }
    }

    public void onLeaveGameClicked(View view) {
        finish();
    }

    @Override
    public void onUserJoined(int uid, int elapsed) {
        doAutomaticRenderRemoteUi(uid);
    }

    private void doAutomaticRenderRemoteUi(final int uid) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }

                if (uid != Constant.Wawaji_CAM_MAIN && uid != Constant.Wawaji_CAM_SECONDARY) {
                    return;
                }

                boolean isMain = uid == Constant.Wawaji_CAM_MAIN;
                if (isMain) { // always be the main cam
                    doSetupVideoStreamView(uid);
                }

                mUidList.put(uid, isMain);
            }
        });
    }

    private void doSetupVideoStreamView(int uid) {
        SurfaceView surfaceV = RtcEngine.CreateRendererView(getApplicationContext());
        surfaceV.setZOrderOnTop(true);
        surfaceV.setZOrderMediaOverlay(true);
        if (config().mWawajiUid == uid) {
            return;
        } else {
            rtcEngine().setupRemoteVideo(new VideoCanvas(surfaceV, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        }

        FrameLayout container = (FrameLayout) findViewById(R.id.gaming_video);
        if (container.getChildCount() >= 2) {
            return;
        }
        container.removeAllViews();
        container.addView(surfaceV);

        config().mWawajiUid = uid;
    }

    private void doRemoveRemoteUi(final int uid) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }

                rtcEngine().setupRemoteVideo(new VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid));
            }
        });
    }

    @Override
    public void onJoinChannelSuccess(final String channel, final int uid, final int elapsed) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }

                final boolean isBroadcaster = isBroadcaster();
                log.debug("onJoinChannelSuccess " + channel + " " + (uid & 0xFFFFFFFFL) + " " + elapsed + " " + isBroadcaster);

                worker().getEngineConfig().mUid = uid;
            }
        });
    }

    @Override
    public void onUserOffline(int uid, int reason) {
        log.debug("onUserOffline " + (uid & 0xFFFFFFFFL) + " " + reason);
        doRemoveRemoteUi(uid);
        mUidList.delete(uid);
    }

    @Override
    public void onExtraInfo(int msg, Object... data) {
        if (isFinishing()) {
            return;
        }

        int intv;
        boolean boolv;

        switch (msg) {
            case Constant.Wawaji_Msg_TIMEOUT:
                intv = (Integer) data[0];
                break;
            case Constant.Wawaji_Msg_RESULT:
                boolv = (Boolean) data[0];
                if (boolv) {
                    showLongToast("Congrats, lucky day");
                } else {
                    showShortToast("Sorry oops");
                }
                finish();
                break;
            case Constant.Wawaji_Msg_FORCED_LOGOUT:
                showShortToast("Forced logout by others " + data[0]);
                finish();
                break;
        }
    }

    @Override
    public void onChannelJoined(String channelID) {
        log.debug("signal onChannelJoined " + channelID);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                isJoinSignalRoom = true;
            }
        });
    }

    @Override
    public void onChannelAttrUpdated(String channelID, final String name, final String value,final String type) {
        log.debug("onChannelAttrUpdated " + channelID + " name :" + name + " value :" + value + " type:" + type);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (name.equals("attrs") && type.equals("update")) {
                    JsonParser parser = new JsonParser();
                    JsonElement jElem = parser.parse(value);
                    JsonObject obj = jElem.getAsJsonObject();

                    JsonElement elemPlay = obj.get("playing");

                    // parse play person name
                    isPlaying = false;
                    if (elemPlay != null && !elemPlay.isJsonNull()) {
                        playPersonName = elemPlay.getAsString();
                        if (playPersonName.equals(selfName)) {
                            isPlaying = true;
                            isOrder = false;
                        }
                    } else {
                        playPersonName = "";
                    }

                    // parse queue list
                    JsonArray jsonArrQueue = obj.getAsJsonArray("queue");
                    if (isOrder) {
                        for (int i = 0; i < jsonArrQueue.size(); i++) {
                            JsonElement object = jsonArrQueue.get(i);
                            log.debug("signal onStartBtnClicked  object.getAsJsonObject():" + object.getAsString());
                            if (object.getAsString().equals(selfName)) {
                                queueCount = i;
                                break;
                            }
                        }
                    } else {
                        queueCount = jsonArrQueue.size();
                    }

                    // set play person name
                    if (playPersonName != null && !playPersonName.equals("")) {
                        textPlayPersonName.setText(playPersonName + getString(R.string.label_isplaying));
                    } else {
                        textPlayPersonName.setText("");
                    }

                    // set play game button
                    if (isPlaying) {
                        textViewPlay.setText(getString(R.string.label_isplaying));
                    } else {
                        if (isOrder) {
                            String str = getString(R.string.label_queuing_success);
                            textViewPlay.setText(String.format(str, queueCount));
                        } else {

                            textViewPlay.setText(getString(R.string.label_incert_coins));
                        }
                    }

                }
            }
        });
    }

    @Override
    public void onMessageInstantReceive(final String account, int uid, final String msg) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (account.equals(machinelName)) {
                    JsonParser parser = new JsonParser();
                    JsonElement jElem = parser.parse(msg);
                    JsonObject obj = jElem.getAsJsonObject();

                    jElem = obj.get("type");
                    String type = jElem.getAsString();
                    if ("PREPARE".equals(type)) {
                        log.debug("signal onMessageInstantReceive  machinelName:" + machinelName);
                        worker().startWawaji(machinelName);
                    }
                }
            }
        });
    }

    public void onStartBtnClicked(View view) {
        log.debug("signal onStartBtnClicked isPlaying:" + isPlaying);
        if (isPlaying || isOrder) {
            return;
        }

        if (isJoinSignalRoom) {
            isOrder = true;
            worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_PLAY);
            String str = getString(R.string.label_queuing_success);
            textViewPlay.setText(String.format(str, queueCount));
        }
    }

    public void onCatcherBtnClicked(View view) {
        if (!isPlaying) {
            showShortToast(getString(R.string.label_not_a_player));
            return;
        }

        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_CATCH);
    }

    public void onRightBtnClicked(View view) {
        if (!isPlaying) {
            showShortToast(getString(R.string.label_not_a_player));
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_RIGHT);
                        break;
                    case MotionEvent.ACTION_UP:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_RIGHT_STOP);
                        break;
                    default:
                        break;
                }

                return false;
            }
        });
    }

    public void onDownBtnClicked(View view) {
        if (!isPlaying) {
            showShortToast(getString(R.string.label_not_a_player));
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_DOWN);
                        break;
                    case MotionEvent.ACTION_UP:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_DOWN_STOP);
                        break;
                    default:
                        break;
                }

                return false;
            }
        });
    }

    public void onUpBtnClicked(View view) {
        if (!isPlaying) {
            showShortToast(getString(R.string.label_not_a_player));
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_UP);
                        break;
                    case MotionEvent.ACTION_UP:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_UP_STOP);
                        break;
                    default:
                        break;
                }

                return false;
            }
        });
    }

    public void onLeftBtnClicked(View view) {
        if (!isPlaying) {
            showShortToast(getString(R.string.label_not_a_player));
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_LEFT);
                        break;
                    case MotionEvent.ACTION_UP:
                        worker().ctrlWawaji(signalChannelName, Constant.Wawaji_Ctrl_LEFT_STOP);
                        break;
                    default:
                        break;
                }

                return false;
            }
        });
    }

    public void onSwitchCameraClicked(View view) {
        // running on UI thread

        if (mUidList.size() > 1) {
            int targetUid = 0;
            for (int i = 0, size = mUidList.size(); i < size; i++) {
                int uid = mUidList.keyAt(i);
                boolean current = mUidList.get(uid);

                if (current) {
                    mUidList.put(uid, false);
                    if (i < size - 1) {
                        targetUid = mUidList.keyAt(i + 1);
                    } else {
                        targetUid = mUidList.keyAt(0);
                    }
                    break;
                }
            }
            mUidList.put(targetUid, true);
            // targetUid should not be 0
            doSetupVideoStreamView(targetUid);
        } else {
            showShortToast(getString(R.string.label_can_not_switch_cam));
        }
    }

}
