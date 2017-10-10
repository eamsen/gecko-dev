package org.mozilla.gecko.media;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.CheckResult;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import org.mozilla.gecko.util.ThreadUtils;

public class MediaControlService extends Service {
    private static final String LOGTAG = "GV MediaControlService";

    public static final String ACTION_INIT = "action_init";
    public static final String ACTION_RESUME = "action_resume";
    public static final String ACTION_PAUSE = "action_pause";
    public static final String ACTION_STOP = "action_stop";
    public static final String ACTION_RESUME_BY_AUDIO_FOCUS = "action_resume_audio_focus";
    public static final String ACTION_PAUSE_BY_AUDIO_FOCUS = "action_pause_audio_focus";
    public static final String ACTION_START_AUDIO_DUCK = "action_start_audio_duck";
    public static final String ACTION_STOP_AUDIO_DUCK = "action_stop_audio_duck";
    private static final String MEDIA_CONTROL_PREF = "dom.audiochannel.mediaControl";

    // This is maximum volume level difference when audio ducking. The number is arbitrary.
    private static final int AUDIO_DUCK_MAX_STEPS = 3;
    private enum AudioDucking { START, STOP };
    private boolean mSupportsDucking = false;
    private int mAudioDuckCurrentSteps = 0;

    private MediaSession mSession;
    private MediaController mController;
    private HeadSetStateReceiver mHeadSetStateReceiver;

    private boolean mInitialize = false;
    private boolean mIsMediaControlPrefOn = true;

    private State mMediaState = State.STOPPED;

    protected enum State {
        PLAYING,
        PAUSED,
        STOPPED
    }

    @Override
    public void onCreate() {
        Log.d(LOGTAG, "onCreate");
        initialize();
        mHeadSetStateReceiver = new HeadSetStateReceiver()
                                .registerReceiver(getApplicationContext());
    }

    @Override
    public void onDestroy() {
        mHeadSetStateReceiver.unregisterReceiver(getApplicationContext());
        shutdown();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOGTAG, "onStartCommand");
        handleIntent(intent);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mSession.release();
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
    }

    private boolean isMediaPlaying() {
        return mMediaState.equals(State.PLAYING);
    }

    private void initialize() {
        if (mInitialize ||
            !isAndroidVersionLollipopOrHigher()) {
            return;
        }

        Log.d(LOGTAG, "initialize");
        if (!initMediaSession()) {
             Log.e(LOGTAG, "initialization fail!");
             stopSelf();
             return;
        }

        mInitialize = true;
    }

    private void shutdown() {
        if (!mInitialize) {
            return;
        }

        Log.d(LOGTAG, "shutdown");
        setState(State.STOPPED);

        mInitialize = false;
        stopSelf();
    }

    private boolean isAndroidVersionLollipopOrHigher() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    private void handleIntent(Intent intent) {
        Log.d(LOGTAG, "handleIntent");
        if (intent == null || intent.getAction() == null || !mInitialize) {
            return;
        }

        Log.d(LOGTAG, "HandleIntent, action = " + intent.getAction() + ", mediaState = " + mMediaState);
        switch (intent.getAction()) {
            case ACTION_INIT :
                // This action is used to create a service and do the initialization,
                // the actual operation would be executed via control interface's
                // pending intent.
                break;
            case ACTION_RESUME :
                mController.getTransportControls().play();
                break;
            case ACTION_PAUSE :
                mController.getTransportControls().pause();
                break;
            case ACTION_STOP :
                mController.getTransportControls().stop();
                break;
            case ACTION_PAUSE_BY_AUDIO_FOCUS :
                mController.getTransportControls().sendCustomAction(ACTION_PAUSE_BY_AUDIO_FOCUS, null);
                break;
            case ACTION_RESUME_BY_AUDIO_FOCUS :
                mController.getTransportControls().sendCustomAction(ACTION_RESUME_BY_AUDIO_FOCUS, null);
                break;
            case ACTION_START_AUDIO_DUCK:
                handleAudioDucking(AudioDucking.START);
                break;
            case ACTION_STOP_AUDIO_DUCK :
                handleAudioDucking(AudioDucking.STOP);
                break;
        }
    }

    private void handleAudioDucking(AudioDucking audioDucking) {
        if (!mInitialize || !mSupportsDucking) {
            return;
        }

        int currentVolume = mController.getPlaybackInfo().getCurrentVolume();
        int maxVolume = mController.getPlaybackInfo().getMaxVolume();

        int adjustDirection;
        if (audioDucking == AudioDucking.START) {
            mAudioDuckCurrentSteps = Math.min(AUDIO_DUCK_MAX_STEPS, currentVolume);
            adjustDirection = AudioManager.ADJUST_LOWER;
        } else {
            mAudioDuckCurrentSteps = Math.min(mAudioDuckCurrentSteps, maxVolume - currentVolume);
            adjustDirection = AudioManager.ADJUST_RAISE;
        }

        for (int i = 0; i < mAudioDuckCurrentSteps; i++) {
            mController.adjustVolume(adjustDirection, 0);
        }
    }

    private boolean initMediaSession() {
        // Android MediaSession is introduced since version L.
        try {
            mSession = new MediaSession(getApplicationContext(),
                                        "fennec media session");
            mController = new MediaController(getApplicationContext(),
                                              mSession.getSessionToken());
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "can't create MediaSession and MediaController!");
            return false;
        }

        int volumeControl = mController.getPlaybackInfo().getVolumeControl();
        if (volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE ||
                volumeControl == VolumeProvider.VOLUME_CONTROL_RELATIVE) {
            mSupportsDucking = true;
        } else {
            Log.w(LOGTAG, "initMediaSession, Session does not support volume absolute or relative volume control");
        }

        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (action.equals(ACTION_PAUSE_BY_AUDIO_FOCUS)) {
                    Log.d(LOGTAG, "Controller, pause by audio focus changed");
                    setState(State.PAUSED);
                } else if (action.equals(ACTION_RESUME_BY_AUDIO_FOCUS)) {
                    Log.d(LOGTAG, "Controller, resume by audio focus changed");
                    setState(State.PLAYING);
                }
            }

            @Override
            public void onPlay() {
                Log.d(LOGTAG, "Controller, onPlay");
                super.onPlay();
                setState(State.PLAYING);
                notifyObservers("mediaControl", "resumeMedia");
            }

            @Override
            public void onPause() {
                Log.d(LOGTAG, "Controller, onPause");
                super.onPause();
                setState(State.PAUSED);
                notifyObservers("mediaControl", "mediaControlPaused");
            }

            @Override
            public void onStop() {
                Log.d(LOGTAG, "Controller, onStop");
                super.onStop();
                setState(State.STOPPED);
                notifyObservers("mediaControl", "mediaControlStopped");
            }
        });
        return true;
    }

    private void setMediaStateForTab(boolean isTabPlaying) {
    }

    private void notifyObservers(String topic, String data) {
        // GeckoAppShell.notifyObservers(topic, data);
    }

    private boolean isNeedToRemoveControlInterface(State state) {
        return state.equals(State.STOPPED);
    }

    private void setState(State newState) {
        mMediaState = newState;
        setMediaStateForTab(mMediaState.equals(State.PLAYING));
        onStateChanged();
    }

    private void onStateChanged() {
        if (!mInitialize) {
            return;
        }

        Log.d(LOGTAG, "onStateChanged, state = " + mMediaState);

        if (isNeedToRemoveControlInterface(mMediaState)) {
            stopForeground(false);
            // NotificationManagerCompat.from(this).cancel();
            return;
        }

        if (!mIsMediaControlPrefOn) {
            return;
        }

        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                updateNotification();
            }
        });
    }

    protected void updateNotification() {
        Log.d(LOGTAG, "updateNotification");
        ThreadUtils.assertNotOnUiThread();

        final Notification.MediaStyle style = new Notification.MediaStyle();
        style.setShowActionsInCompactView(0);

        final boolean isPlaying = isMediaPlaying();
        // final int visibility = //tab.isPrivate() ?
            // Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC;

        final Notification notification = new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle("WOOTTitle")
            .setContentText("WOOTURL")
            // .setContentIntent(createContentIntent())
            // .setDeleteIntent(createDeleteIntent())
            .setStyle(style)
            .addAction(createNotificationAction())
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setWhen(0)
            // .setVisibility(visibility)
            .build();

        if (isPlaying) {
            startForeground(233, notification);
        } else {
            stopForeground(false);
            NotificationManagerCompat.from(this).notify(233, notification);
        }
    }

    private Notification.Action createNotificationAction() {
        final Intent intent = createIntentUponState(mMediaState);
        boolean isPlayAction = intent.getAction().equals(ACTION_RESUME);

        int icon = isPlayAction ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause;
        String title = isPlayAction ? "Play" : "Pause";

        final PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 1, intent, 0);

        //noinspection deprecation - The new constructor is only for API > 23
        return new Notification.Action.Builder(null, "WOOTTitle", pendingIntent).build();
    }

    /**
     * This method encapsulated UI logic. For PLAYING state, UI should display a PAUSE icon.
     * @param state The expected current state of MediaControlService
     * @return corresponding Intent to be used for Notification
     */
    @VisibleForTesting
    protected Intent createIntentUponState(State state) {
        String action = state.equals(State.PLAYING) ? ACTION_PAUSE : ACTION_RESUME;
        final Intent intent = new Intent(getApplicationContext(), MediaControlService.class);
        intent.setAction(action);
        return intent;
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent();
        // Intent intent = IntentHelper.getTabSwitchIntent(tab);
        return PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        // return null;
    }

    private PendingIntent createDeleteIntent() {
        Intent intent = new Intent(getApplicationContext(), MediaControlService.class);
        intent.setAction(ACTION_STOP);
        return  PendingIntent.getService(getApplicationContext(), 1, intent, 0);
    }

    private class HeadSetStateReceiver extends BroadcastReceiver {

        @CheckResult(suggest = "new HeadSetStateReceiver().registerReceiver(Context)")
        HeadSetStateReceiver registerReceiver(Context context) {
            IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            context.registerReceiver(this, intentFilter);
            return this;
        }

        void unregisterReceiver(Context context) {
            context.unregisterReceiver(HeadSetStateReceiver.this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (isMediaPlaying()) {
                Intent pauseIntent = new Intent(getApplicationContext(), MediaControlService.class);
                pauseIntent.setAction(ACTION_PAUSE);
                handleIntent(pauseIntent);
            }
        }

    }

}
