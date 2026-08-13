package com.danials.cameragate;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.danials.cameragate.core.CameraGate;
import com.danials.cameragate.core.LocaleHelper;

/**
 * Foreground service that keeps the camera server alive while the phone is
 * locked or the app is backgrounded, and holds a partial wake lock so the
 * CPU stays awake while the screen is off.
 *
 * Kill-resistance strategy (Android 4.x is the primary target):
 *  - START_STICKY: if Android reaps the service, it is restarted with a null
 *    intent and this service re-arms everything.
 *  - stopWithTask=false: swiping the app away does not stop it.
 *  - onTaskRemoved(): explicitly re-launches the service after a swipe-away
 *    (some Android 4.x builds ignore START_STICKY).
 *  - AlarmManager watchdog: a repeating alarm proves the process is alive
 *    and restarts the service if a task killer got to it.
 *  - Partial wake lock keeps the CPU running with the screen off.
 *
 * Uses only framework APIs (Notification.Builder works since API 11), so the
 * whole app keeps running on Android 4.x devices.
 */
public class CameraGateService extends Service {

    private static final String TAG = "CameraGate";
    private static final String CHANNEL_ID = "cameragate_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKELOCK_TIMEOUT_MS = 4 * 60 * 1000L;
    private static final long WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L;
    private static final int WATCHDOG_REQUEST_CODE = 4242;
    private static final int RESTART_REQUEST_CODE = 4243;

    public static final String ACTION_START = "com.danials.cameragate.action.START";
    public static final String ACTION_STOP = "com.danials.cameragate.action.STOP";
    private static final String ACTION_WATCHDOG = "com.danials.cameragate.action.WATCHDOG";
    private static final String ACTION_RESTART = "com.danials.cameragate.action.RESTART";

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable wakeLockRefresher = new Runnable() {
        @Override
        public void run() {
            acquireWakeLock();
            handler.postDelayed(this, WAKELOCK_TIMEOUT_MS / 2);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, CameraGateService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        cancelAlarms(context);
        context.stopService(new Intent(context, CameraGateService.class));
    }

    /** A stopped service must never resurrect itself via the alarms. */
    private static void cancelAlarms(Context context) {
        AlarmManager am = (AlarmManager)
                context.getSystemService(ALARM_SERVICE);
        am.cancel(pendingIntent(context,
                new Intent(context, CameraGateService.class)
                        .setAction(ACTION_WATCHDOG),
                WATCHDOG_REQUEST_CODE));
        am.cancel(pendingIntent(context,
                new Intent(context, CameraGateService.class)
                        .setAction(ACTION_RESTART),
                RESTART_REQUEST_CODE));
    }

    private static PendingIntent pendingIntent(
            Context context, Intent intent, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(context, requestCode, intent, flags);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        scheduleWatchdog();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();
        CameraGate gate = CameraGateApp.gate();
        if (!gate.isRunning()) {
            if (!gate.start()) {
                Log.e(TAG, "server failed to start");
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        acquireWakeLock();
        handler.removeCallbacks(wakeLockRefresher);
        handler.postDelayed(wakeLockRefresher, WAKELOCK_TIMEOUT_MS / 2);

        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action) || ACTION_RESTART.equals(action)) {
            Log.i(TAG, "service started (" + action + ")");
        }
        return START_STICKY;
    }

    /**
     * The user swiped the app away. The service keeps running
     * (stopWithTask=false) but some Android 4.x builds then also stop the
     * service - so re-launch it explicitly a moment later.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restart = new Intent(this, CameraGateService.class)
                .setAction(ACTION_RESTART);
        PendingIntent pi = pendingIntent(restart, RESTART_REQUEST_CODE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pi);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        CameraGateApp.gate().stop();
        super.onDestroy();
    }

    /**
     * Repeating alarm that proves the service is alive. If a task killer
     * stopped it, the alarm starts it again (onStartCommand re-arms the
     * server). Works on Android 4.0+.
     */
    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent pi = pendingIntent(
                new Intent(this, CameraGateService.class)
                        .setAction(ACTION_WATCHDOG),
                WATCHDOG_REQUEST_CODE);
        long first = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Inexact repeating on 6+; the watchdog is a safety net, not a timer.
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    first, WATCHDOG_INTERVAL_MS, pi);
        } else {
            am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    first, WATCHDOG_INTERVAL_MS, pi);
        }
    }

    private PendingIntent pendingIntent(Intent intent, int requestCode) {
        return CameraGateService.pendingIntent(this, intent, requestCode);
    }

    private void startInForeground() {
        createChannelIfNeeded();
        // Fresh localization each build: <7.x configuration changes can flip
        // the process resources back to the system locale in between.
        Context ui = LocaleHelper.apply(this, LocaleHelper.current(this));
        Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ui.getString(R.string.service_notification_title))
                .setContentText(ui.getString(R.string.service_notification_text))
                .setContentIntent(serviceIntent())
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    private PendingIntent serviceIntent() {
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.service_channel_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "cameragate:server");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }
    }

    private void releaseWakeLock() {
        handler.removeCallbacks(wakeLockRefresher);
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}