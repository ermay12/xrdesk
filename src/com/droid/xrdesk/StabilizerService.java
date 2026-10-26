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
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Vibrator;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.os.VibrationEffect;
import android.view.Display;

import com.droid.xrdesk.motion.Stabilizer;
import com.droid.xrdesk.xreal.RateRing;
import com.droid.xrdesk.xreal.XrealProtocol;

import java.util.Locale;
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
 * <p>3D: switches the glasses between 2D and 3D (side by side, each eye seeing half of the
 * picture). The glasses' display then reconnects, about 3 s later, under a new display id; the
 * helper is attached to it again. In 3D, a sideways shift or a resize would put the halves in the
 * wrong eyes, so the picture is full size and centered, and only up-and-down motion is stabilized.
 *
 * <p>Once connected (XrealImuSource), the glasses send their buttons here instead of acting on
 * them, until they're unplugged: menu toggles 3D, the volume buttons change the media volume. So
 * the service runs until then, stabilizing or not.
 *
 * Threads: main (lifecycle, Shizuku, IMU state), "stab" (samples in, one move per vsync out) and
 * a worker for slow helper calls.
 */
public class StabilizerService extends Service implements Choreographer.FrameCallback {
    private static final String TAG = "XRDesk";
    // The service runs until the glasses are unplugged (or something fails); meanwhile it
    // stabilizes if asked, serves the mouse and picture screens and switches 2D/3D. All of it
    // needs the shell helper; only stabilizing reads the glasses' IMU.
    static final String ACTION_RUN = "com.droid.xrdesk.action.RUN";          // just run
    static final String ACTION_START = "com.droid.xrdesk.action.START";      // stabilize
    static final String ACTION_STOP = "com.droid.xrdesk.action.STOP";        // stop stabilizing
    static final String ACTION_MOUSE_ON = "com.droid.xrdesk.action.MOUSE_ON";
    static final String ACTION_MOUSE_OFF = "com.droid.xrdesk.action.MOUSE_OFF";
    static final String ACTION_PICTURE_ON = "com.droid.xrdesk.action.PICTURE_ON";
    static final String ACTION_PICTURE_OFF = "com.droid.xrdesk.action.PICTURE_OFF";
    static final String ACTION_RECENTER = "com.droid.xrdesk.action.RECENTER";
    static final String ACTION_RELOAD = "com.droid.xrdesk.action.RELOAD";  // settings changed; doesn't start stabilizing
    static final String ACTION_3D_ON = "com.droid.xrdesk.action.3D_ON";
    static final String ACTION_3D_OFF = "com.droid.xrdesk.action.3D_OFF";
    static final String ACTION_MCU = "com.droid.xrdesk.action.MCU";  // development, from adb: an MCU command (extra "mcu")
    private static final String CHANNEL = "stabilizer";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESEND_MS = 500;  // in case the display manager reset the projection
    private static final long DISPLAY_BACK_MS = 10000;     // a 2D/3D switch reconnects the glasses' display in about 3 s
    private static final long MENU_IGNORE_MS = 1000;       // after a switch, so a double press doesn't flip it back
    private static final long VOLUME_REPEAT_DELAY_MS = 400;  // a held volume button repeats
    private static final long VOLUME_REPEAT_MS = 120;
    private static final long VOLUME_REPEAT_MAX_MS = 5000;   // in case the release went missing
    private static final long FAILED_SWITCH_SHOWN_MS = 3000;
    private static final String FAILED_SWITCH = "Couldn't switch. Are the glasses plugged in?";
    static final String SWITCH_NOTE = "The glasses go dark for a few seconds.";
    private static final int REATTACH_TRIES = 6;  // the glasses' display may take a moment to be found once back
    private static final long REATTACH_RETRY_MS = 250;

    private static volatile String sStatus = "Stopped";
    private static volatile StabilizerService sInstance;
    private static volatile boolean sRunning;
    private static volatile boolean sStabilizing;  // wanted, main thread writes
    private static volatile boolean sMouseOn;
    private static volatile boolean sPictureOn;
    private static volatile boolean s3d;         // the glasses are in 3D, as far as known
    private static volatile boolean sSwitching;  // main thread writes: switching 2D/3D until the display is back
    private static volatile Boolean sWant3d;     // main thread writes: a switch asked for before the glasses were ready

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

    /** The glasses are in 3D (main thread writes). */
    static boolean in3d() {
        return sRunning && s3d;
    }

    /** Switching between 2D and 3D (or about to), until the glasses' display is back. */
    static boolean switching() {
        return sRunning && (sSwitching || sWant3d != null);
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
    private boolean mSwitchTo3d;    // main thread: the switch under way
    private boolean mReconnecting;  // main thread: the glasses' display went away, likely for a 2D/3D switch
    private long mLastSwitchMs;
    private int mReattachTriesLeft;  // main thread
    private int mVolumeDirection;   // main thread: a held volume button, AudioManager.ADJUST_*
    private long mVolumeRepeatUntilMs;

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
        s3d = false;
        sSwitching = false;
        sWant3d = null;
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
        if (ACTION_RUN.equals(action)) {
            showIdle();
        } else if (action == null || ACTION_START.equals(action)) {
            setStabilizing(true);
        } else if (ACTION_STOP.equals(action)) {
            setStabilizing(false);
        } else if (ACTION_MOUSE_ON.equals(action)) {
            sMouseOn = true;
            showIdle();
        } else if (ACTION_MOUSE_OFF.equals(action)) {
            sMouseOn = false;
            showIdle();
        } else if (ACTION_PICTURE_ON.equals(action)) {
            sPictureOn = true;
            showIdle();
        } else if (ACTION_PICTURE_OFF.equals(action)) {
            sPictureOn = false;
            showIdle();
        } else if (ACTION_RECENTER.equals(action)) {
            mStab.post(this::recenter);
        } else if (ACTION_RELOAD.equals(action)) {
            Options o = Options.load(this);
            mOptions = o;
            Log.i(TAG, "options: " + o.text);
            mStab.post(this::configure);
            applyLayout();
            showIdle();
        } else if (ACTION_3D_ON.equals(action) || ACTION_3D_OFF.equals(action)) {
            request3d(ACTION_3D_ON.equals(action));
        } else if (ACTION_MCU.equals(action)) {
            mcuFromAdb(intent.getStringExtra("mcu"));
        }
        return START_NOT_STICKY;
    }

    /** Development: sends "msgid data..." (hex bytes, e.g. "07" or "08 08") to the glasses' MCU and logs the reply. */
    private void mcuFromAdb(String command) {
        try {
            String[] parts = command.trim().split("\\s+");
            int id = Integer.parseInt(parts[0], 16);
            byte[] data = new byte[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                data[i - 1] = (byte) Integer.parseInt(parts[i], 16);
            }
            mImu.command(id, data, reply -> Log.i(TAG, String.format(Locale.US, "MCU 0x%02x reply: %s", id,
                    reply == null ? "none" : XrealProtocol.hex(reply, 0, reply.length))));
        } catch (RuntimeException e) {
            Log.w(TAG, "bad MCU command \"" + command + "\": " + e);
        }
    }

    private static final String MOUSE_ONLY = "Mouse only (not stabilizing)";
    private static final String PICTURE_ONLY = "Not stabilizing; the picture size and position still apply";

    /** The status while running without stabilizing. */
    private String idleStatus() {
        return s3d ? "3D · not stabilizing" : sMouseOn ? MOUSE_ONLY : mOptions.adjusted() ? PICTURE_ONLY : "Not stabilizing";
    }

    /** The status while stabilizing, once the IMU streams. */
    private String stabilizingStatus() {
        return s3d ? "3D · stabilizing up and down only" : "Stabilizing " + mDisplayName;
    }

    /** Main thread. The status when all is well: switching, stabilizing or why the service runs. */
    private String okStatus() {
        if (sSwitching) {
            return mSwitchTo3d ? "Switching to 3D…" : "Switching to 2D…";
        }
        if (sStabilizing) {
            return mStreaming ? stabilizingStatus() : "Waiting for the glasses' motion sensor…";
        }
        return idleStatus();
    }

    /** Main thread. Shows why the service runs if it isn't stabilizing, once the helper is attached. */
    private void showIdle() {
        if (!sStabilizing && mHelper != null && !idleStatus().equals(sStatus)) {
            setStatus(idleStatus());
        }
    }

    /** Main thread. Starts or stops stabilizing; the service keeps running either way (see the top). */
    private void setStabilizing(boolean on) {
        if (on == sStabilizing) {
            return;
        }
        sStabilizing = on;
        Log.i(TAG, on ? "stabilizing on" : "stabilizing off");
        mImu.setStreaming(on);
        if (!on) {
            mStreaming = false;
            mStab.post(this::recenter);  // back to the unstabilized picture
        }
        if (mHelper != null) {
            setStatus(okStatus());
        }
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
        s3d = false;
        sSwitching = false;
        sWant3d = null;
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
            fail("XR Desk isn't allowed to use Shizuku. Open XR Desk to allow it.");
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
            attach(helper, mOptions.display);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!mStopping) {
                fail("The shell helper stopped (did Shizuku stop?).");
            }
        }
    };

    /** Main thread. Attaches the helper to the glasses' display (-1: the first external display). */
    private void attach(Helper helper, int displayId) {
        boolean flat = s3d;
        mWorker.execute(() -> {
            Options o = mOptions;
            Helper.Attached a = flat ? helper.attach(mClientToken, displayId, 1f, 0f, 0f, true)
                    : helper.attach(mClientToken, displayId, o.fill, o.offsetX, o.offsetY, false);
            mMain.post(() -> onAttached(helper, a));
        });
    }

    private void onAttached(Helper helper, Helper.Attached a) {
        if (mStopping) {
            new Thread(helper::detach, "xrdesk-detach").start();  // the worker is shut down
            return;
        }
        if (a.error != null) {
            if (mReattachTriesLeft-- > 0) {
                Log.i(TAG, "the glasses' display isn't ready yet (" + a.error + "); trying again");
                mMain.postDelayed(() -> attach(helper, -1), REATTACH_RETRY_MS);
                return;
            }
            fail("Can't use the glasses' display: " + a.error);
            return;
        }
        mReattachTriesLeft = 0;
        mHelper = helper;
        mDisplayId = a.displayId;
        mDisplayName = a.name;
        Log.i(TAG, "attached to display " + a.displayId + " (" + a.name + ")");
        applyLayout();  // in case the options or 3D changed while attaching
        mImu.start();  // nothing if already connected (the display may have just come back)
        if (sSwitching) {
            switchDone();
            return;
        }
        setStatus(okStatus());
        whenReady();
    }

    // ---- 2D and 3D (main thread) ----

    private void request3d(boolean on) {
        if (mHelper == null || !mImu.connected()) {
            if (!mStopping) {
                sWant3d = on;  // see whenReady
            }
        } else {
            set3d(on);
        }
    }

    /** Once the helper is attached and the glasses connected: a switch asked for meanwhile, or else learn their mode. */
    private void whenReady() {
        if (mHelper == null || !mImu.connected() || sSwitching) {
            return;
        }
        Boolean want = sWant3d;
        sWant3d = null;
        if (want != null) {
            set3d(want);
        } else {
            readMode();
        }
    }

    /** Has the glasses switch; see the class comment. */
    private void set3d(boolean on) {
        if (sSwitching || on == s3d) {
            return;
        }
        sSwitching = true;
        mSwitchTo3d = on;
        mMain.removeCallbacks(mVolumeRepeat);  // a volume button whose release goes missing during the switch
        setStatus(okStatus());
        int mode = on ? XrealProtocol.DISPLAY_MODE_3D : XrealProtocol.DISPLAY_MODE_2D;
        mImu.command(XrealProtocol.MCU_MSG_SET_DISPLAY_MODE, XrealProtocol.displayModeData(mode), reply -> mMain.post(() -> {
            if (reply == null || reply.length == 0 || reply[0] != 0) {
                Log.w(TAG, "the glasses didn't switch to " + (on ? "3D" : "2D") + ": "
                        + (reply == null ? "no reply" : XrealProtocol.hex(reply, 0, reply.length)));
                sSwitching = false;
                switchFailed();
                return;
            }
            Log.i(TAG, "the glasses are switching to " + (on ? "3D" : "2D"));
            s3d = on;
            applyLayout();
            mStab.post(this::recenter);
            mMain.postDelayed(mSwitchTimeout, DISPLAY_BACK_MS);
        }));
    }

    /** The glasses' display is back after a switch: two firm clicks, felt even on silent. */
    private void switchDone() {
        sSwitching = false;
        mMain.removeCallbacks(mSwitchTimeout);
        mLastSwitchMs = SystemClock.uptimeMillis();
        Vibrator v = getSystemService(Vibrator.class);
        Ui.buzz(this, v != null && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                ? VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 120)
                        .compose()
                : VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
        setStatus(okStatus());
    }

    private void switchFailed() {
        Ui.buzz(this, VibrationEffect.Composition.PRIMITIVE_THUD, 1f, VibrationEffect.EFFECT_HEAVY_CLICK);
        setStatus(FAILED_SWITCH);
        mMain.postDelayed(() -> {
            if (FAILED_SWITCH.equals(sStatus)) {
                setStatus(okStatus());
            }
        }, FAILED_SWITCH_SHOWN_MS);
    }

    /** The glasses switched, but their display didn't go away and come back. */
    private final Runnable mSwitchTimeout = () -> {
        if (sSwitching && !mReconnecting) {
            Log.w(TAG, "the glasses' display didn't reconnect after the switch");
            switchDone();
        }
    };

    /** Asks the glasses whether they're in 3D (e.g. after the app restarted) and follows. */
    private void readMode() {
        mImu.command(XrealProtocol.MCU_MSG_GET_DISPLAY_MODE, new byte[0], reply -> mMain.post(() -> {
            int mode = XrealProtocol.displayModeFromReply(reply);
            if (mode < 0 || sSwitching || mStopping) {
                return;
            }
            boolean on = mode == XrealProtocol.DISPLAY_MODE_3D;
            Log.i(TAG, String.format(Locale.US, "the glasses are in %s (mode 0x%02x)", on ? "3D" : "2D", mode));
            if (on != s3d) {
                s3d = on;
                applyLayout();
                mStab.post(this::recenter);
                setStatus(okStatus());
            }
        }));
    }

    /** The picture's size and position: as set, or in 3D full size and centered. */
    private void applyLayout() {
        Helper helper = mHelper;
        Options o = mOptions;
        if (helper == null) {
            return;
        }
        if (s3d) {
            helper.layout(1f, 0f, 0f, true);
        } else {
            helper.layout(o.fill, o.offsetX, o.offsetY, false);
        }
    }

    /** A button on the glasses (see the class comment). */
    private void onGlassesButton(int button, int event) {
        if (button == XrealProtocol.BUTTON_MENU) {
            // A short press; not right after a switch, so a bounce or double press doesn't flip it back.
            if (event == XrealProtocol.BUTTON_CLICK && !sSwitching
                    && SystemClock.uptimeMillis() - mLastSwitchMs > MENU_IGNORE_MS) {
                Log.i(TAG, "menu button: " + (s3d ? "2D" : "3D"));
                request3d(!s3d);
            }
        } else if (button == XrealProtocol.BUTTON_VOLUME_UP || button == XrealProtocol.BUTTON_VOLUME_DOWN) {
            if (event == XrealProtocol.BUTTON_DOWN) {
                mMain.removeCallbacks(mVolumeRepeat);
                mVolumeDirection = button == XrealProtocol.BUTTON_VOLUME_UP
                        ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                mVolumeRepeatUntilMs = SystemClock.uptimeMillis() + VOLUME_REPEAT_MAX_MS;
                adjustVolume();
                mMain.postDelayed(mVolumeRepeat, VOLUME_REPEAT_DELAY_MS);
            } else if (event == XrealProtocol.BUTTON_UP) {
                mMain.removeCallbacks(mVolumeRepeat);
            }
        }
    }

    private final Runnable mVolumeRepeat = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() < mVolumeRepeatUntilMs) {
                adjustVolume();
                mMain.postDelayed(this, VOLUME_REPEAT_MS);
            }
        }
    };

    private void adjustVolume() {
        getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, mVolumeDirection,
                AudioManager.FLAG_SHOW_UI);
    }

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            if (mReconnecting && mConnectedHelper != null && !mStopping) {
                mReconnecting = false;
                mMain.removeCallbacks(mDisplayGone);
                Log.i(TAG, "the glasses' display is back, as display " + displayId);
                mReattachTriesLeft = REATTACH_TRIES;
                attach(mConnectedHelper, -1);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId != mDisplayId) {
                return;
            }
            mHelper = null;
            mDisplayId = -1;
            if (!sSwitching && !mImu.attached()) {
                fail("The glasses were disconnected.");
                return;
            }
            // Switching between 2D and 3D, here or in the glasses' menu, reconnects their display.
            Log.i(TAG, "the glasses' display went away; waiting for it to come back");
            mReconnecting = true;
            mMain.postDelayed(mDisplayGone, DISPLAY_BACK_MS);
            if (!sSwitching) {
                setStatus("Waiting for the glasses' display…");
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
                    mMain.post(() -> fail("Can't use the glasses' display: " + error));
                }
            });
        }
    };

    private final Runnable mDisplayGone = () -> {
        mReconnecting = false;
        sSwitching = false;
        fail("The glasses were disconnected.");
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
        public void onConnected(boolean connected, String detail) {
            mMain.post(() -> {
                if (mStopping) {
                    return;
                }
                if (connected) {
                    if (mHelper != null) {
                        setStatus(okStatus());
                    }
                    whenReady();
                    return;
                }
                mStreaming = false;
                mMain.removeCallbacks(mVolumeRepeat);
                mStab.post(StabilizerService.this::recenter);
                setStatus((sStabilizing ? "Glasses' motion sensor: " : "Glasses' buttons: ") + detail);
                if (sWant3d != null) {
                    sWant3d = null;
                    switchFailed();
                }
            });
        }

        @Override
        public void onStreaming() {
            mMain.post(() -> {
                if (mStopping || !sStabilizing) {
                    return;
                }
                mStreaming = true;
                setStatus(okStatus());
            });
        }

        @Override
        public void onButton(int button, int event) {
            mMain.post(() -> onGlassesButton(button, event));
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
        int x = mStreaming && !s3d ? mStabilizer.x() : 0;  // in 3D, up and down only
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
        if (sStabilizing) {
            b.addAction(new Notification.Action.Builder(null, "Recenter", action(ACTION_RECENTER, 2)).build())
                    .addAction(new Notification.Action.Builder(null, "Stop Stabilization", action(ACTION_STOP, 3)).build());
        } else {
            b.addAction(new Notification.Action.Builder(null, "Start Stabilization", action(ACTION_START, 5)).build());
        }
        if (s3d && !sSwitching) {  // last, so a habitual tap on the first action still recenters
            b.addAction(new Notification.Action.Builder(null, "Switch to 2D", action(ACTION_3D_OFF, 4)).build());
        }
        return b.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, StabilizerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
