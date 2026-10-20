package com.droid.xrdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
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
import android.view.Display;

import com.droid.xrdesk.motion.Stabilizer;
import com.droid.xrdesk.xreal.RateRing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Stabilizes the glasses' whole display: reads head rotation from the glasses' IMU, turns it into
 * an offset (Stabilizer) and has the shell helper shift the display's picture by that much, at
 * most once per frame. Apps keep running natively on the display; nothing is redrawn. The helper
 * also sizes and places the picture as set (Options fill, offsetx, offsety), stabilizing or not.
 *
 * Threads: main (lifecycle, Shizuku, IMU state), "stab" (samples in, one move per vsync out) and
 * a worker for slow helper calls.
 */
public class StabilizerService extends Service implements Choreographer.FrameCallback {
    private static final String TAG = "XRDesk";
    // Independent wishes keep this service running: stabilizing, the mouse screen or the picture
    // screen being open, and a picture size or position other than the display's own
    // (Options.adjusted). All need the shell helper; only stabilizing reads the glasses' IMU.
    static final String ACTION_START = "com.droid.xrdesk.action.START";      // stabilize
    static final String ACTION_STOP = "com.droid.xrdesk.action.STOP";        // stop stabilizing
    static final String ACTION_MOUSE_ON = "com.droid.xrdesk.action.MOUSE_ON";
    static final String ACTION_MOUSE_OFF = "com.droid.xrdesk.action.MOUSE_OFF";
    static final String ACTION_PICTURE_ON = "com.droid.xrdesk.action.PICTURE_ON";
    static final String ACTION_PICTURE_OFF = "com.droid.xrdesk.action.PICTURE_OFF";
    static final String ACTION_RECENTER = "com.droid.xrdesk.action.RECENTER";
    static final String ACTION_RELOAD = "com.droid.xrdesk.action.RELOAD";  // settings changed; doesn't start stabilizing
    private static final String CHANNEL = "stabilizer";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESEND_MS = 500;  // in case the display manager reset the projection

    private static volatile String sStatus = "Stopped";
    private static volatile StabilizerService sInstance;
    private static volatile boolean sRunning;
    private static volatile boolean sStabilizing;  // wanted, main thread writes
    private static volatile boolean sMouseOn;
    private static volatile boolean sPictureOn;

    static String status() {
        return sStatus;
    }

    static boolean running() {
        return sRunning;
    }

    /** Running and stabilizing (not just serving the mouse screen). */
    static boolean stabilizing() {
        return sRunning && sStabilizing;
    }

    /** The shell helper once attached to the glasses (used by the mouse screen), else null. */
    static Helper helper() {
        StabilizerService s = sInstance;
        return s != null ? s.mHelper : null;
    }

    /** The glasses' display id once the helper is attached, else -1. */
    static int displayId() {
        StabilizerService s = sInstance;
        return s != null && s.mHelper != null ? s.mDisplayId : -1;
    }

    /** Applies changed settings, if running. */
    static void reload(Context context) {
        if (sRunning) {
            context.startService(new Intent(context, StabilizerService.class).setAction(ACTION_RELOAD));
        }
    }

    /**
     * Applies changed settings, including the picture's size and position. Those hold while not
     * stabilizing too, so if they're set and the service isn't running, this starts it (without
     * stabilizing).
     */
    static void reloadPicture(Context context) {
        if (!sRunning && Options.load(context).adjusted()) {
            context.startForegroundService(new Intent(context, StabilizerService.class).setAction(ACTION_RELOAD));
        } else {
            reload(context);
        }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Binder mClientToken = new Binder();  // the helper restores the display if this process dies
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mRefreshPending = new AtomicBoolean();
    private volatile Options mOptions;
    private Shizuku.UserServiceArgs mHelperArgs;
    private boolean mHelperBound;
    private volatile Helper mHelper;      // attached and stabilizing
    private Helper mConnectedHelper;      // main thread; told to exit when this service stops
    private volatile int mDisplayId = -1;
    private String mDisplayName;
    private DisplayManager mDisplayManager;
    private XrealImuSource mImu;
    private volatile boolean mStreaming;
    private boolean mStopping;
    private boolean mFailed;  // keep the failure as the status after stopping

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
        sInstance = this;
        sRunning = true;
        sStabilizing = false;  // set by the start actions
        sMouseOn = false;
        sPictureOn = false;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Stabilizer", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        mOptions = Options.load(this);
        mStabThread = new HandlerThread("xrdesk-stab", Process.THREAD_PRIORITY_DISPLAY);
        mStabThread.start();
        mStab = new Handler(mStabThread.getLooper());
        mStab.post(() -> mChoreographer = Choreographer.getInstance());
        mStab.post(this::configure);
        mStab.postDelayed(mResend, RESEND_MS);
        // Android opens its USB permission dialog on the glasses (the desktop display), where
        // it's easy to miss; the helper (attached before the IMU starts) moves it to the phone.
        mImu = new XrealImuSource(this, mImuListener, () -> {
            Helper helper = mHelper;
            if (helper != null) {
                helper.usbDialogTo(Display.DEFAULT_DISPLAY);
            }
        });
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mMain);

        setStatus("Connecting to Shizuku…");
        Shizuku.addBinderReceivedListenerSticky(mShizukuReady, mMain);
        mMain.postDelayed(() -> {
            if (mHelper == null && !mHelperBound && !Shizuku.pingBinder()) {
                fail("Shizuku isn't running. Start it in the Shizuku app, then start XR Desk again.");
            }
        }, 3000);
        Log.i(TAG, "service started, options: " + mOptions.text);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Started with startForegroundService(): confirm being in the foreground every time.
        startForeground(NOTIFICATION_ID, notification(sStatus), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        String action = intent != null ? intent.getAction() : null;
        if (action == null || ACTION_START.equals(action)) {
            setStabilizing(true);
        } else if (ACTION_STOP.equals(action)) {
            setStabilizing(false);
        } else if (ACTION_MOUSE_ON.equals(action)) {
            sMouseOn = true;
            showIdle();
        } else if (ACTION_MOUSE_OFF.equals(action)) {
            sMouseOn = false;
            if (!stopIfUnused()) {
                showIdle();
            }
        } else if (ACTION_PICTURE_ON.equals(action)) {
            sPictureOn = true;
            showIdle();
        } else if (ACTION_PICTURE_OFF.equals(action)) {
            sPictureOn = false;
            if (!stopIfUnused()) {
                showIdle();
            }
        } else if (ACTION_RECENTER.equals(action)) {
            mStab.post(this::recenter);
        } else if (ACTION_RELOAD.equals(action)) {
            Options o = Options.load(this);
            mOptions = o;
            Log.i(TAG, "options: " + o.text);
            mStab.post(this::configure);
            Helper helper = mHelper;
            if (helper != null) {
                helper.layout(o.fill, o.offsetX, o.offsetY);
            }
            if (!stopIfUnused()) {
                showIdle();
            }
        }
        return START_NOT_STICKY;
    }

    private static final String MOUSE_ONLY = "Mouse only (not stabilizing)";
    private static final String PICTURE_ONLY = "Not stabilizing; the picture size and position still apply";

    /** The status while running without stabilizing. */
    private static String idleStatus() {
        return sMouseOn ? MOUSE_ONLY : PICTURE_ONLY;
    }

    /** Main thread. Shows why the service runs if it isn't stabilizing, once the helper is attached. */
    private void showIdle() {
        if (!sStabilizing && mHelper != null && !idleStatus().equals(sStatus)) {
            setStatus(idleStatus());
        }
    }

    /** Main thread. Starts or stops stabilizing; the service keeps running for the other wishes (see the top). */
    private void setStabilizing(boolean on) {
        if (on == sStabilizing) {
            return;
        }
        sStabilizing = on;
        Log.i(TAG, on ? "stabilizing on" : "stabilizing off");
        if (on) {
            if (mHelper != null) {
                setStatus("Waiting for the glasses' motion sensor…");
                mImu.start();
            }
        } else if (!stopIfUnused()) {
            mImu.stop();
            mStreaming = false;
            mStab.post(this::recenter);  // back to the unstabilized picture
            setStatus(idleStatus());
        }
    }

    /** Stops the service when neither stabilizing, the mouse or picture screen nor the picture's size and position needs it. */
    private boolean stopIfUnused() {
        if (!sStabilizing && !sMouseOn && !sPictureOn && !mOptions.adjusted()) {
            stopSelf();
            return true;
        }
        return false;
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
        sStabilizing = false;
        sMouseOn = false;
        sPictureOn = false;
        if (sInstance == this) {
            sInstance = null;
        }
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
        if (!mFailed) {
            sStatus = "Stopped";
        }
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
            fail("XR Desk isn't allowed to use Shizuku. Open XR Desk and press Start Stabilization to allow it.");
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
                new Thread(helper::detach, "xrdesk-detach").start();
                return;
            }
            mConnectedHelper = helper;
            mWorker.execute(() -> {
                Options o = mOptions;
                Helper.Attached a = helper.attach(mClientToken, o.display, o.fill, o.offsetX, o.offsetY);
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
            new Thread(helper::detach, "xrdesk-detach").start();  // the worker is shut down
            return;
        }
        if (a.error != null) {
            fail("Can't stabilize: " + a.error);
            return;
        }
        mHelper = helper;
        mDisplayId = a.displayId;
        mDisplayName = a.name;
        Log.i(TAG, "attached to display " + a.displayId + " (" + a.name + ")");
        Options o = mOptions;  // in case they changed while attaching
        helper.layout(o.fill, o.offsetX, o.offsetY);
        if (sStabilizing) {
            setStatus("Waiting for the glasses' motion sensor…");
            mImu.start();
        } else {
            setStatus(idleStatus());
        }
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
                if (mStopping || !sStabilizing) {
                    return;  // stopped on purpose; the status already says so
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
        if (!mStreaming) {
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

    /** Stab thread. Applies the current settings. */
    private void configure() {
        Options o = mOptions;
        mStabilizer.configure(o.focal, o.drift, o.maxYaw, o.maxPitch, o.predictMs / 1000f, o.deadband, o.curve);
    }

    /** Stab thread. Back to center (also used when stopping or losing the IMU). */
    private void recenter() {
        mStabilizer.reset();
        mLastSampleNs = 0;
        scheduleFrame();
    }

    /** Stab thread, every RESEND_MS: frames stop without samples, but the picture's size and position still get resent. */
    private final Runnable mResend = new Runnable() {
        @Override
        public void run() {
            if (!mStreaming && mOptions.adjusted()) {
                scheduleFrame();
            }
            mStab.postDelayed(this, RESEND_MS);
        }
    };

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
        int x = mStreaming ? mStabilizer.x() : 0;
        int y = mStreaming ? mStabilizer.y() : 0;
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
        mFailed = true;
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
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(sStabilizing || !sMouseOn ? "XR Desk" : "XR Desk (mouse)")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true);
        if (sStabilizing) {  // nothing to recenter or stop while only the mouse screen runs it
            b.addAction(new Notification.Action.Builder(null, "Recenter", action(ACTION_RECENTER, 2)).build())
                    .addAction(new Notification.Action.Builder(null, "Stop Stabilization", action(ACTION_STOP, 3)).build());
        }
        return b.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, StabilizerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
