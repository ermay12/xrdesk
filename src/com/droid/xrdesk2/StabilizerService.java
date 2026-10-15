package com.droid.xrdesk2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import com.droid.xrdesk2.motion.Stabilizer;
import com.droid.xrdesk2.xreal.RateRing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Stabilizes the glasses' whole display: reads head rotation from the glasses' IMU, turns it into
 * an offset (Stabilizer) and has the shell helper shift the display's picture by that much, at
 * most once per frame. Apps keep running natively on the display; nothing is redrawn.
 *
 * Threads: main (lifecycle, Shizuku, IMU state), "stab" (samples in, one move per vsync out) and
 * a worker for slow helper calls.
 */
public class StabilizerService extends Service implements Choreographer.FrameCallback {
    private static final String TAG = "XRDesk2";
    static final String ACTION_STOP = "com.droid.xrdesk2.action.STOP";
    static final String ACTION_PAUSE = "com.droid.xrdesk2.action.PAUSE";  // toggles
    static final String ACTION_RECENTER = "com.droid.xrdesk2.action.RECENTER";
    private static final String CHANNEL = "stabilizer";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESEND_MS = 500;  // in case the display manager reset the projection

    private static volatile String sStatus = "Stopped";
    private static volatile boolean sRunning;
    private static volatile boolean sPaused;

    static String status() {
        return sStatus;
    }

    static boolean running() {
        return sRunning;
    }

    static boolean paused() {
        return sPaused;
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Binder mClientToken = new Binder();  // the helper restores the display if this process dies
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mRefreshPending = new AtomicBoolean();
    private Options mOptions;
    private Shizuku.UserServiceArgs mHelperArgs;
    private boolean mHelperBound;
    private volatile Helper mHelper;      // attached and stabilizing
    private Helper mConnectedHelper;      // main thread; told to exit when this service stops
    private int mDisplayId = -1;
    private String mDisplayName;
    private DisplayManager mDisplayManager;
    private XrealImuSource mImu;
    private volatile boolean mStreaming;
    private boolean mStopping;

    // Stab thread
    private HandlerThread mStabThread;
    private Handler mStab;
    private Choreographer mChoreographer;
    private final Stabilizer mStabilizer = new Stabilizer();
    private final AtomicBoolean mDrainPending = new AtomicBoolean();
    private long mLastSampleNs;
    private boolean mFrameScheduled;
    private int mSentX = Integer.MIN_VALUE;
    private int mSentY;
    private long mSentAtMs;
    private int mMoves;
    private long mStatsSinceMs;

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        sPaused = false;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Stabilizer", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        mOptions = Options.load(this);
        mStabilizer.configure(mOptions.focal, mOptions.drift, mOptions.max, mOptions.predictMs / 1000f, mOptions.deadband);
        mStabThread = new HandlerThread("xrdesk2-stab", Process.THREAD_PRIORITY_DISPLAY);
        mStabThread.start();
        mStab = new Handler(mStabThread.getLooper());
        mStab.post(() -> mChoreographer = Choreographer.getInstance());
        mImu = new XrealImuSource(this, mImuListener);
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mMain);

        setStatus("Connecting to Shizuku…");
        Shizuku.addBinderReceivedListenerSticky(mShizukuReady, mMain);
        mMain.postDelayed(() -> {
            if (mHelper == null && !mHelperBound && !Shizuku.pingBinder()) {
                fail("Shizuku isn't running. Start it in the Shizuku app, then start XR Desk 2 again.");
            }
        }, 3000);
        Log.i(TAG, "service started, options: " + mOptions.text);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
        } else if (ACTION_PAUSE.equals(action)) {
            sPaused = !sPaused;
            Log.i(TAG, sPaused ? "paused" : "resumed");
            mStab.post(this::recenter);
            setStatus(sStatus);
        } else if (ACTION_RECENTER.equals(action)) {
            mStab.post(this::recenter);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStopping = true;
        sRunning = false;
        mMain.removeCallbacksAndMessages(null);
        mImu.stop();
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        Shizuku.removeBinderReceivedListener(mShizukuReady);
        mHelper = null;
        if (mConnectedHelper != null) {
            mConnectedHelper.detach();  // puts the display back and exits; quick
        }
        if (mHelperBound && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(mHelperArgs, mHelperConnection, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "unbindUserService failed", e);
            }
        }
        mWorker.shutdown();
        mStabThread.quitSafely();
        sStatus = "Stopped";
        Log.i(TAG, "service stopped");
    }

    // ---- Shell helper (main thread) ----

    private final Shizuku.OnBinderReceivedListener mShizukuReady = () -> {
        if (mStopping || mHelperBound) {
            return;
        }
        if (Shizuku.isPreV11()) {
            fail("This Shizuku version is too old. Update Shizuku.");
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            fail("XR Desk 2 isn't allowed to use Shizuku. Open XR Desk 2 and press Start to allow it.");
        } else {
            bindHelper();
        }
    };

    private void bindHelper() {
        int version;
        try {
            // A new install restarts the helper, so it always runs the installed code.
            version = (int) (getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime / 1000);
        } catch (PackageManager.NameNotFoundException e) {
            version = 1;
        }
        mHelperArgs = new Shizuku.UserServiceArgs(new ComponentName(this, ProjectionHelper.class))
                .daemon(false)
                .processNameSuffix("helper")
                .version(version);
        try {
            Shizuku.bindUserService(mHelperArgs, mHelperConnection);
            mHelperBound = true;
            setStatus("Starting the shell helper…");
        } catch (RuntimeException e) {
            Log.e(TAG, "couldn't bind the Shizuku user service", e);
            fail("Couldn't start the shell helper through Shizuku: " + e);
        }
    }

    private final ServiceConnection mHelperConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Helper helper = new Helper(binder);
            if (mStopping) {
                new Thread(helper::detach, "xrdesk2-detach").start();
                return;
            }
            mConnectedHelper = helper;
            mWorker.execute(() -> {
                Helper.Attached a = helper.attach(mClientToken, mOptions.display, mOptions.fill);
                mMain.post(() -> onAttached(helper, a));
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!mStopping) {
                fail("The shell helper stopped (did Shizuku stop?).");
            }
        }
    };

    private void onAttached(Helper helper, Helper.Attached a) {
        if (mStopping) {
            new Thread(helper::detach, "xrdesk2-detach").start();  // the worker is shut down
            return;
        }
        if (a.error != null) {
            fail("Can't stabilize: " + a.error);
            return;
        }
        mHelper = helper;
        mDisplayId = a.displayId;
        mDisplayName = a.name;
        Log.i(TAG, "stabilizing display " + a.displayId + " (" + a.name + ")");
        setStatus("Waiting for the glasses' motion sensor…");
        mImu.start();
    }

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == mDisplayId) {
                fail("The glasses were disconnected.");
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Helper helper = mHelper;
            if (displayId != mDisplayId || helper == null || !mRefreshPending.compareAndSet(false, true)) {
                return;
            }
            mWorker.execute(() -> {
                mRefreshPending.set(false);
                String error = helper.refresh();
                if (error != null) {
                    mMain.post(() -> fail("Can't stabilize: " + error));
                }
            });
        }
    };

    // ---- IMU ----

    private final XrealImuSource.Listener mImuListener = new XrealImuSource.Listener() {
        @Override
        public void onSamples() {
            if (mDrainPending.compareAndSet(false, true)) {
                mStab.post(mDrain);
            }
        }

        @Override
        public void onStateChanged(boolean streaming, String detail) {
            mMain.post(() -> {
                if (mStopping) {
                    return;
                }
                mStreaming = streaming;
                if (streaming) {
                    setStatus("Stabilizing " + mDisplayName);
                } else {
                    setStatus("Glasses' motion sensor: " + detail);
                    mStab.post(StabilizerService.this::recenter);
                }
            });
        }
    };

    private final RateRing.Consumer mConsumer = this::onSample;

    /** Stab thread: hands queued samples to the stabilizer. */
    private final Runnable mDrain = () -> {
        mDrainPending.set(false);  // before draining, so samples queued meanwhile post again
        RateRing ring = mImu.ring();
        if (sPaused || !mStreaming) {
            ring.clear();
            return;
        }
        ring.drain(mConsumer);
        scheduleFrame();
    };

    /** Stab thread. Rates in rad/s about the view's right and up axes, IMU timestamp in ns. */
    private void onSample(long t, float right, float up, float roll) {
        if (mOptions.swapAxes) {
            float tmp = right;
            right = up;
            up = tmp;
        }
        if (mOptions.invertX) {
            up = -up;  // the up-axis rate drives horizontal motion
        }
        if (mOptions.invertY) {
            right = -right;
        }
        float dt = mLastSampleNs == 0 ? 0f : (t - mLastSampleNs) * 1e-9f;
        if (dt < 0f || dt > 0.05f) {
            dt = 0f;  // a gap: don't integrate across it
        }
        mLastSampleNs = t;
        mStabilizer.update(right, up, dt);
    }

    /** Stab thread. Back to center (also used when pausing, resuming or losing the IMU). */
    private void recenter() {
        mStabilizer.reset();
        mLastSampleNs = 0;
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (!mFrameScheduled && mChoreographer != null) {
            mFrameScheduled = true;
            mChoreographer.postFrameCallback(this);
        }
    }

    /** Stab thread, once per vsync while samples arrive: sends the offset if it changed. */
    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        Helper helper = mHelper;
        if (helper == null) {
            return;
        }
        boolean active = !sPaused && mStreaming;
        int x = active ? mStabilizer.x() : 0;
        int y = active ? mStabilizer.y() : 0;
        long now = SystemClock.uptimeMillis();
        if (x == mSentX && y == mSentY && now - mSentAtMs < RESEND_MS) {
            return;
        }
        helper.move(x, y);
        mSentX = x;
        mSentY = y;
        mSentAtMs = now;
        mMoves++;
        if (now - mStatsSinceMs >= 10000) {
            Log.i(TAG, "moves in the last " + (now - mStatsSinceMs) / 1000 + " s: " + mMoves);
            mMoves = 0;
            mStatsSinceMs = now;
        }
    }

    // ---- Status and notification (main thread) ----

    private void fail(String message) {
        Log.e(TAG, message);
        setStatus(message);
        if (!mStopping) {
            stopSelf();
        }
    }

    private void setStatus(String status) {
        sStatus = status;
        Log.i(TAG, "status: " + status);
        if (!mStopping) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(sPaused ? "XR Desk 2 (paused)" : "XR Desk 2")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, sPaused ? "Resume" : "Pause", action(ACTION_PAUSE, 1)).build())
                .addAction(new Notification.Action.Builder(null, "Recenter", action(ACTION_RECENTER, 2)).build())
                .addAction(new Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 3)).build())
                .build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, StabilizerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
