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

public class MediaControlService {
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

    public static Class<?> getType() {
        try {
            return Class.forName("org.mozilla.gecko.media.FennecMediaControlService");
        } catch (ClassNotFoundException e) {}
        try {
            return Class.forName("org.mozilla.gecko.media.GeckoViewMediaControlService");
        } catch (ClassNotFoundException e) {}
        return null;
    }
}
