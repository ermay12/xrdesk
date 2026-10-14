package com.droid.xrdesk;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import com.droid.xrdesk.motion.DesktopTransform;
import com.droid.xrdesk.motion.HeadGestures;
import com.droid.xrdesk.motion.RollFilter;
import com.droid.xrdesk.xreal.RateRing;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts 1-4 trusted virtual displays ("monitors": full desktops with their own launchers), each
 * in a child SurfaceControl layer of this window. Only the visible desktop is shown; a quick head
 * flick switches monitors, and a head shake opens the settings menu. The visible desktop is
 * stabilized by sliding (and optionally rolling) its layer against small head rotations, always
 * drifting back to center. Moving a layer is a SurfaceFlinger transaction, so nothing is redrawn
 * per frame. Head rotation comes from XREAL xbx a01/a01+ glasses when attached
 * (XrealImuSource), else from the phone's gyroscope. Touch, mouse, keyboard and game controller
 * input are forwarded into the visible desktop, except while the menu is open.
 */
public class DesktopActivity extends Activity implements SensorEventListener, Choreographer.FrameCallback {
    private static final String TAG = ShellInstrumentation.TAG;
    private static final int MAX_DESKTOPS = SettingsMenu.MAX_MONITORS;
    private static final long HANDOVER_MS = 150;  // old desktop stays under the new one until it has drawn
    private static final int SOURCE_PHONE = 0;
    private static final int SOURCE_XREAL = 1;
    private static final String PREFS = "xrdesk";
    private static final int ADDED = 0;
    private static final int NOT_READY = 1;
    private static final int FAILED = 2;

    // The system can relaunch this activity (e.g. after a package update), briefly
    // overlapping two instances; only the last one to finish ends the shell session.
    private static int sLiveInstances;

    // Settings, passed as `am instrument -e key value`; monitors/swipe/roll also come from the menu.
    private float mFill;        // desktop size as a fraction of the screen, drawn 1:1
    private int mVdWidth;
    private int mVdHeight;
    private int mVdDpi;
    private float mFocalPx;     // on-screen pixels per radian of rotation
    private float mDriftSec;    // time constant for drifting back to center
    private float mMaxOffset;   // largest slide in pixels
    private float mPredictSec;  // look-ahead covering display latency
    private float mDeadband;    // rad/s; gyro noise below this is ignored
    private float mRollMax;     // largest roll correction, rad
    private float mRollTest;    // testing only: a fixed roll angle, rad (0 = off)
    private String mImuMode;    // auto | phone | xreal
    private boolean mSwapAxes;  // fix-ups for an unverified IMU mounting, applied to any source
    private boolean mInvertX;
    private boolean mInvertY;
    private boolean mInvertRoll;
    private int mMonitorCount;          // main thread: wanted number of desktops
    private boolean mSwipeHorizontal;   // main thread
    private volatile boolean mRollEnabled;
    private SharedPreferences mPrefs;

    private UiAutomation mUiAutomation;
    private Method mSetDisplayState;  // hidden VirtualDisplay.setDisplayState(boolean)
    // Hidden Transaction.setMatrix(sc, dsdx, dtdx, dtdy, dsdy); an exact-typed handle avoids
    // boxing and allocation per frame. Null = roll smoothing unavailable.
    private MethodHandle mSetMatrix;
    private Method mSetDisplayId;
    private Method mInject3;
    private HandlerThread mInjectThread;
    private Handler mInjectHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Desktops. Arrays are main thread only; the stab thread sees mLayerSnapshot.
    private View mContent;
    private final SurfaceControl[] mLayers = new SurfaceControl[MAX_DESKTOPS];
    private final Surface[] mSurfaces = new Surface[MAX_DESKTOPS];
    private final VirtualDisplay[] mDisplays = new VirtualDisplay[MAX_DESKTOPS];
    private final int[] mDisplayIds = {-1, -1, -1, -1};
    private final boolean[] mShown = new boolean[MAX_DESKTOPS];      // layer visible
    private final boolean[] mConnected = new boolean[MAX_DESKTOPS];  // display has its surface
    private boolean mDesktopsCreated;
    private volatile SurfaceControl[] mLayerSnapshot = new SurfaceControl[0];
    private final Runnable mSettle = this::settleHidden;
    private volatile int mActive;           // index of the visible desktop
    private volatile boolean mCanSwitch;

    // Input (main thread)
    private int mTouchDisplayId = -1;       // desktop the current touch gesture started on
    private int mHoverDisplayId = -1;       // desktop the mouse is currently hovering
    private boolean mPointerDown;           // a touch or mouse-button gesture is in progress
    private boolean mSwallowGesture;        // the menu opened mid-gesture: drop the rest of it
    private boolean mInputLog;              // log every forwarded event (debugging)
    private boolean mFullscreenRequested;
    private Method mSetActionButton;        // hidden MotionEvent.setActionButton(int)
    private int mRejected;                  // inject thread
    private final float[] mMapPoint = new float[2];

    // Menu (main thread)
    private SettingsMenu mMenu;
    private volatile boolean mMenuOpen;

    // Stabilization runs on its own thread: gyro samples in, at most one transaction per vsync out.
    private HandlerThread mStabThread;
    private Handler mStabHandler;
    private Choreographer mStabChoreographer;
    private SensorManager mSensorManager;
    private Sensor mGyro;
    private boolean mResumed;               // main thread
    private boolean mGyroRegistered;        // main thread
    private XrealImuSource mXreal;
    private volatile boolean mXrealStreaming;
    private volatile boolean mPaused = true;
    private final AtomicBoolean mDrainPending = new AtomicBoolean();
    private final SurfaceControl.Transaction mTx = new SurfaceControl.Transaction();
    private volatile int mCenterX;
    private volatile int mCenterY;
    private volatile int mPosX = Integer.MIN_VALUE;  // last applied unrotated top-left; read by input
    private volatile int mPosY = Integer.MIN_VALUE;
    private volatile float mMapAngle;                // last applied roll; read by input
    private volatile boolean mStabilize = true;
    private volatile int mScreenRotation;
    private final HeadGestures mGestures = new HeadGestures();  // stab thread only from here down
    private final RollFilter mRoll = new RollFilter();
    private float mOffX;
    private float mOffY;
    private float mRateX;
    private float mRateY;
    private long mLastSampleNs;
    private int mSampleSource = -1;  // SOURCE_PHONE or SOURCE_XREAL; timestamps differ per source
    private boolean mFrameScheduled;
    private boolean mMatrixApplied;
    private float mAppliedAngle;
    private SurfaceControl[] mAppliedLayers;
    private final float[] mXform = new float[6];
    private int mMoves;
    private long mStatsSinceMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        getWindow().setDecorFitsSystemWindows(false);

        ShellInstrumentation shell = ShellInstrumentation.get();
        if (shell == null) {
            // No shell access in this process yet; LaunchActivity gets it through Shizuku.
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
            return;
        }
        sLiveInstances++;

        Bundle args = shell.args();
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mFill = floatArg(args, "fill", 0.9f);
        mVdWidth = intArg(args, "width", 0);  // 0 = fill the screen
        mVdHeight = intArg(args, "height", 0);
        mVdDpi = intArg(args, "dpi", 240);
        mFocalPx = floatArg(args, "focal", 2262f);  // ~46 degree horizontal FOV across 1920px
        mDriftSec = floatArg(args, "drift", 0.5f);
        mMaxOffset = floatArg(args, "max", 300f);
        mPredictSec = floatArg(args, "predict", 8f) / 1000f;
        mDeadband = floatArg(args, "deadband", 0.004f);
        mRollMax = (float) Math.toRadians(floatArg(args, "rollmax", 15f));
        mRollTest = (float) Math.toRadians(floatArg(args, "rolltest", 0f));
        mImuMode = args.getString("imu", "auto");
        mSwapAxes = intArg(args, "imuswap", 0) != 0;
        mInvertX = intArg(args, "imuinvx", 0) != 0;
        mInvertY = intArg(args, "imuinvy", 0) != 0;
        mInvertRoll = intArg(args, "imuinvroll", 0) != 0;
        mInputLog = intArg(args, "inputlog", 0) != 0;
        // Menu settings: saved values, unless a start option overrides them for this session.
        mMonitorCount = clampMonitors(intArg(args, "monitors", mPrefs.getInt("monitors", 2)));
        mSwipeHorizontal = "leftright".equals(args.getString("swipe", mPrefs.getString("swipe", "updown")));
        mRollEnabled = intArg(args, "roll", mPrefs.getBoolean("roll", false) ? 1 : 0) != 0;
        mGestures.setHorizontal(mSwipeHorizontal);
        mGestures.setFlicksEnabled(mMonitorCount > 1);
        mGestures.setFlick(floatArg(args, "flick", 2.5f), (float) Math.toRadians(floatArg(args, "flickangle", 12f)),
                (long) (floatArg(args, "flickms", 300f) * 1e6), (long) (floatArg(args, "cooldown", 800f) * 1e6));
        mGestures.setShake(floatArg(args, "shake", 1.5f), (float) Math.toRadians(floatArg(args, "shakeangle", 6f)),
                intArg(args, "shakecount", 3), (long) (floatArg(args, "shakems", 1000f) * 1e6), 300_000_000L);
        Log.i(TAG, "settings: monitors " + mMonitorCount + ", swipe " + (mSwipeHorizontal ? "leftright" : "updown")
                + ", roll " + (mRollEnabled ? "on" : "off"));
        mUiAutomation = shell.uiAutomation();

        try {
            mSetDisplayState = VirtualDisplay.class.getMethod("setDisplayState", boolean.class);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "VirtualDisplay.setDisplayState unavailable; hidden desktops keep drawing");
        }
        try {
            mSetMatrix = MethodHandles.lookup().unreflect(SurfaceControl.Transaction.class.getMethod(
                    "setMatrix", SurfaceControl.class, float.class, float.class, float.class, float.class));
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "SurfaceControl.Transaction.setMatrix unavailable; roll smoothing disabled", e);
        }
        try {
            mSetDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "InputEvent.setDisplayId unavailable; input forwarding disabled", e);
        }
        try {
            mInject3 = UiAutomation.class.getMethod("injectInputEvent", InputEvent.class, boolean.class, boolean.class);
        } catch (ReflectiveOperationException e) {
            mInject3 = null;
        }
        try {
            mSetActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "MotionEvent.setActionButton unavailable; mouse button press/release not forwarded", e);
        }
        mInjectThread = new HandlerThread("xrdesk-inject");
        mInjectThread.start();
        mInjectHandler = new Handler(mInjectThread.getLooper());

        mSensorManager = getSystemService(SensorManager.class);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        // Started after mGestures is configured, so the stab thread sees that configuration.
        mStabThread = new HandlerThread("xrdesk-stab", Process.THREAD_PRIORITY_DISPLAY);
        mStabThread.start();
        mStabHandler = new Handler(mStabThread.getLooper());
        mStabHandler.post(() -> mStabChoreographer = Choreographer.getInstance());

        if (!"phone".equals(mImuMode)) {
            mXreal = new XrealImuSource(this, mXrealListener);
            mXreal.start();
        } else {
            Log.i(TAG, "imu source: phone (imu=phone)");
        }

        // The window itself is a static black background; the desktops are child layers on top.
        mContent = new View(this);
        mContent.setBackgroundColor(Color.BLACK);
        setContentView(mContent);
        mContent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                mContent.post(() -> onScreenSize(r - l, b - t)));
        // The window's own surface is recreated when it comes back from the background, which
        // detaches child layers; re-attach whenever the window draws (only on such changes).
        mContent.getViewTreeObserver().addOnDrawListener(() -> {
            if (mDesktopsCreated) {
                mContent.post(this::attachLayers);
            }
        });
    }

    private void onScreenSize(int w, int h) {
        if (w <= 0 || h <= 0 || isDestroyed()) {
            return;
        }
        // The phone gyro moves with the phone's own screen, so map it with that screen's rotation
        // even when this window is on an external display (e.g. the glasses).
        Display display = getDisplay();
        if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
            Display builtIn = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            display = builtIn != null ? builtIn : display;
        }
        mScreenRotation = display.getRotation();
        if (!mDesktopsCreated && (mVdWidth <= 0 || mVdHeight <= 0)) {
            mVdWidth = Math.round(w * mFill);
            mVdHeight = Math.round(h * mFill);
        }
        mCenterX = (w - mVdWidth) / 2;
        mCenterY = (h - mVdHeight) / 2;
        if (!mDesktopsCreated) {
            createDesktops();
        }
        mStabHandler.post(this::scheduleFrame);
    }

    // ---- Desktops (main thread) ----

    private void createDesktops() {
        AttachedSurfaceControl root = getWindow().getRootSurfaceControl();
        for (int i = 0; i < mMonitorCount; i++) {
            int result = root != null ? addDesktop(root, i) : NOT_READY;
            if (result == NOT_READY && i == 0) {
                Log.w(TAG, "window surface not ready; retrying");
                mContent.postDelayed(() -> onScreenSize(mContent.getWidth(), mContent.getHeight()), 100);
                return;
            }
            if (result != ADDED) {
                Log.e(TAG, "could not create desktop " + (i + 1) + "; continuing with " + i);
                break;
            }
        }
        mDesktopsCreated = true;
        float snap = RollFilter.snapFor(mVdWidth, mVdHeight);
        mStabHandler.post(() -> mRoll.configure(mDeadband, mDriftSec, mRollMax, snap));
        publishLayers();
        mCanSwitch = true;
    }

    /** Creates desktop i: its layer under this window and its virtual display. */
    private int addDesktop(AttachedSurfaceControl root, int i) {
        SurfaceControl layer = new SurfaceControl.Builder()
                .setName("XR Desk desktop " + (i + 1))
                .setBufferSize(mVdWidth, mVdHeight)
                .setOpaque(true)
                .build();
        SurfaceControl.Transaction t = root.buildReparentTransaction(layer);
        if (t == null) {
            layer.release();
            return NOT_READY;
        }
        boolean visible = i == mActive;
        int x = mPosX != Integer.MIN_VALUE ? mPosX : mCenterX;
        int y = mPosY != Integer.MIN_VALUE ? mPosY : mCenterY;
        t.setLayer(layer, visible ? 2 : 1).setVisibility(layer, visible).setPosition(layer, x, y).apply();
        Surface surface = new Surface(layer);
        // Only the visible desktop gets a surface where possible; the others run but aren't composited.
        boolean connect = visible || !canKeepOn();
        VirtualDisplay vd = createDisplay("XR Desk " + (i + 1), connect ? surface : null);
        if (vd == null) {
            new SurfaceControl.Transaction().reparent(layer, null).apply();
            layer.release();
            surface.release();
            return FAILED;
        }
        if (!connect) {
            keepOn(vd);
        }
        mLayers[i] = layer;
        mSurfaces[i] = surface;
        mDisplays[i] = vd;
        mDisplayIds[i] = vd.getDisplay().getDisplayId();
        mShown[i] = visible;
        mConnected[i] = connect;
        return ADDED;
    }

    /** Main thread. Hands the current set of layers to the stab thread. */
    private void publishLayers() {
        List<SurfaceControl> layers = new ArrayList<>();
        for (SurfaceControl l : mLayers) {
            if (l != null) {
                layers.add(l);
            }
        }
        mLayerSnapshot = layers.toArray(new SurfaceControl[0]);
        mStabHandler.post(this::scheduleFrame);
    }

    private int desktopCount() {
        int n = 0;
        for (VirtualDisplay d : mDisplays) {
            if (d != null) {
                n++;
            }
        }
        return n;
    }

    /** Re-attaches the layers under this window: the visible desktop on top. */
    private boolean attachLayers() {
        AttachedSurfaceControl root = getWindow().getRootSurfaceControl();
        if (root == null) {
            return false;
        }
        int active = mActive;
        for (int i = 0; i < MAX_DESKTOPS; i++) {
            if (mLayers[i] == null) {
                continue;
            }
            SurfaceControl.Transaction t = root.buildReparentTransaction(mLayers[i]);
            if (t == null) {
                return false;
            }
            t.setLayer(mLayers[i], i == active ? 2 : 1).setVisibility(mLayers[i], mShown[i]).apply();
        }
        return true;
    }

    private boolean canKeepOn() {
        return mSetDisplayState != null;
    }

    /**
     * A virtual display without a surface counts as off: its apps stop and it loses its
     * wallpaper and status bar. Keep it on; with no surface it still isn't composited.
     */
    private void keepOn(VirtualDisplay vd) {
        if (mSetDisplayState == null) {
            return;
        }
        try {
            mSetDisplayState.invoke(vd, true);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "setDisplayState failed", e);
        }
    }

    private VirtualDisplay createDisplay(String name, Surface surface) {
        int publicFlag = flag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1);
        int ownContentOnly = flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 1 << 3);
        int supportsTouch = flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 << 6);
        int systemDecorations = flag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS", 1 << 9);
        int trusted = flag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 << 10);
        int ownGroup = flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 1 << 11);
        int alwaysUnlocked = flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 1 << 12);
        int noTouchFeedback = flag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED", 1 << 13);
        int ownFocus = flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 1 << 14);
        int keepTopFocus = flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED", 1 << 16);

        // No DESTROY_CONTENT_ON_REMOVAL: when a desktop goes away, its apps move to the phone
        // screen instead of closing (closing the Terminal would shut down the Linux VM).
        int base = publicFlag | ownContentOnly | supportsTouch | trusted | systemDecorations;
        int[] attempts = {
                base | ownGroup | alwaysUnlocked | noTouchFeedback | ownFocus | keepTopFocus,
                base | ownFocus | keepTopFocus,
                base,
        };
        DisplayManager dm = getSystemService(DisplayManager.class);
        for (int flags : attempts) {
            try {
                VirtualDisplay vd = dm.createVirtualDisplay(name, mVdWidth, mVdHeight, mVdDpi, surface, flags);
                if (vd != null) {
                    Log.i(TAG, name + " is display " + vd.getDisplay().getDisplayId() + ", " + mVdWidth + "x"
                            + mVdHeight + " @" + mVdDpi + "dpi, flags 0x" + Integer.toHexString(flags));
                    return vd;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "createVirtualDisplay failed with flags 0x" + Integer.toHexString(flags), e);
            }
        }
        return null;
    }

    private static int flag(String name, int fallback) {
        try {
            return DisplayManager.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    /** Shows desktop {@code to} on top; the previous one is hidden once the new one has drawn. */
    private void switchTo(int to) {
        int from = mActive;
        if (!mCanSwitch || to == from || to < 0 || to >= MAX_DESKTOPS || mDisplays[to] == null) {
            return;
        }
        if (!mConnected[to]) {
            mDisplays[to].setSurface(mSurfaces[to]);
            mConnected[to] = true;
        }
        // The new layer is transparent until its first frame, so the old one shows until then.
        SurfaceControl.Transaction t = new SurfaceControl.Transaction()
                .setLayer(mLayers[to], 2)
                .setVisibility(mLayers[to], true);
        if (mLayers[from] != null) {
            t.setLayer(mLayers[from], 1);
        }
        t.apply();
        mShown[to] = true;
        mActive = to;
        Log.i(TAG, "switched to desktop " + (to + 1) + " of " + desktopCount());
        mMainHandler.removeCallbacks(mSettle);
        mMainHandler.postDelayed(mSettle, HANDOVER_MS);
    }

    /** Hides every desktop but the visible one, and disconnects it where possible. */
    private void settleHidden() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        int active = mActive;
        for (int i = 0; i < MAX_DESKTOPS; i++) {
            if (i == active || mLayers[i] == null) {
                continue;
            }
            if (mShown[i]) {
                t.setVisibility(mLayers[i], false).setLayer(mLayers[i], 1);
                mShown[i] = false;
            }
            if (mConnected[i] && canKeepOn()) {
                mDisplays[i].setSurface(null);
                keepOn(mDisplays[i]);
                mConnected[i] = false;
            }
        }
        t.apply();
    }

    private void stepMonitor(int delta) {
        int count = Math.min(mMonitorCount, desktopCount());
        switchTo(HeadGestures.step(mActive, count, delta));
    }

    /** Main thread. Adds or removes desktops at the end so there are {@code count}. */
    private void setMonitorCount(int count) {
        count = clampMonitors(count);
        if (count == mMonitorCount) {
            return;
        }
        mMonitorCount = count;
        mPrefs.edit().putInt("monitors", count).apply();
        Log.i(TAG, "monitors: " + count);
        postGestureConfig();
        if (!mDesktopsCreated) {
            return;
        }
        if (mActive >= count) {
            switchTo(count - 1);
        }
        AttachedSurfaceControl root = getWindow().getRootSurfaceControl();
        for (int i = 0; i < count; i++) {
            if (mDisplays[i] == null) {
                if (root == null || addDesktop(root, i) != ADDED) {
                    Log.e(TAG, "could not add desktop " + (i + 1));
                    break;
                }
            }
        }
        publishLayers();
        // Removal waits until a switch away from a removed desktop has been handed over.
        mMainHandler.postDelayed(this::removeExtraDesktops, HANDOVER_MS + 50);
    }

    private void removeExtraDesktops() {
        List<SurfaceControl> layers = new ArrayList<>();
        List<Surface> surfaces = new ArrayList<>();
        for (int i = mMonitorCount; i < MAX_DESKTOPS; i++) {
            if (mDisplays[i] == null || i == mActive) {
                continue;
            }
            Log.i(TAG, "removing desktop " + (i + 1) + " (display " + mDisplayIds[i]
                    + "); Android moves its apps to the phone screen");
            mDisplays[i].release();
            layers.add(mLayers[i]);
            surfaces.add(mSurfaces[i]);
            mDisplays[i] = null;
            mLayers[i] = null;
            mSurfaces[i] = null;
            mDisplayIds[i] = -1;
            mShown[i] = false;
            mConnected[i] = false;
        }
        if (layers.isEmpty()) {
            return;
        }
        publishLayers();
        // Release on the stab thread, after any doFrame that still uses the old snapshot.
        mStabHandler.post(() -> {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (SurfaceControl l : layers) {
                t.reparent(l, null);
            }
            t.apply();
            for (SurfaceControl l : layers) {
                l.release();
            }
            for (Surface s : surfaces) {
                s.release();
            }
        });
    }

    private static int clampMonitors(int n) {
        return Math.max(1, Math.min(MAX_DESKTOPS, n));
    }

    // ---- Menu (main thread) ----

    private final SettingsMenu.Host mMenuHost = new SettingsMenu.Host() {
        @Override
        public int monitorCount() {
            return mMonitorCount;
        }

        @Override
        public void setMonitorCount(int count) {
            DesktopActivity.this.setMonitorCount(count);
        }

        @Override
        public boolean swipeHorizontal() {
            return mSwipeHorizontal;
        }

        @Override
        public void setSwipeHorizontal(boolean horizontal) {
            if (horizontal == mSwipeHorizontal) {
                return;
            }
            mSwipeHorizontal = horizontal;
            mPrefs.edit().putString("swipe", horizontal ? "leftright" : "updown").apply();
            Log.i(TAG, "swipe: " + (horizontal ? "leftright" : "updown"));
            postGestureConfig();
        }

        @Override
        public boolean rollEnabled() {
            return mRollEnabled;
        }

        @Override
        public void setRollEnabled(boolean enabled) {
            if (enabled == mRollEnabled) {
                return;
            }
            mRollEnabled = enabled;
            mPrefs.edit().putBoolean("roll", enabled).apply();
            Log.i(TAG, "roll smoothing: " + (enabled ? "on" : "off"));
            mStabHandler.post(() -> {
                mRoll.reset();
                scheduleFrame();  // turning off puts the layers back to unrotated
            });
        }

        @Override
        public int megabytesPerMonitor() {
            // Roughly three full-size RGBA buffers per desktop.
            return (int) Math.round(mVdWidth * (double) mVdHeight * 4 * 3 / (1024 * 1024));
        }

        @Override
        public void onMenuClosed(String how) {
            mMenuOpen = false;
            postGestureConfig();
            Log.i(TAG, "menu closed (" + how + ")");
        }
    };

    /** {@code how} is logged, e.g. "shake" or "F12". */
    private void toggleMenu(String how) {
        if (mStabHandler == null || isFinishing() || isDestroyed()) {
            return;
        }
        if (mMenu == null) {
            mMenu = new SettingsMenu(this, mMenuHost);
        }
        if (mMenu.isShowing()) {
            mMenu.dismiss(how);
            return;
        }
        mMenuOpen = true;  // stops forwarding before the menu takes focus
        postGestureConfig();
        mMenu.show();
        Log.i(TAG, "menu opened (" + how + ")");
    }

    private void postGestureConfig() {
        boolean flicks = mMonitorCount > 1 && !mMenuOpen;
        boolean horizontal = mSwipeHorizontal;
        mStabHandler.post(() -> {
            mGestures.setFlicksEnabled(flicks);
            mGestures.setHorizontal(horizontal);
        });
    }

    private void onGesture(int event) {
        if (isDestroyed()) {
            return;
        }
        if (event == HeadGestures.SHAKE) {
            toggleMenu("shake");
        } else if (!mMenuOpen) {
            stepMonitor(event == HeadGestures.NEXT ? 1 : -1);
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
        if (mStabHandler == null) {
            return;
        }
        mResumed = true;
        mPaused = false;
        updatePhoneGyro();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mStabHandler == null) {
            return;
        }
        mResumed = false;
        mPaused = true;
        updatePhoneGyro();
        mStabHandler.post(this::resetMotion);
    }

    /** Main thread. The phone gyro runs only while visible and the glasses aren't streaming. */
    private void updatePhoneGyro() {
        boolean want = mResumed && !mXrealStreaming;
        if (want == mGyroRegistered) {
            return;
        }
        if (want) {
            if (mGyro == null) {
                Log.e(TAG, "no gyroscope; stabilization disabled");
                return;
            }
            mSensorManager.registerListener(this, mGyro, 5000 /* us, 200 Hz */, mStabHandler);
        } else {
            mSensorManager.unregisterListener(this);
        }
        mGyroRegistered = want;
    }

    private final XrealImuSource.Listener mXrealListener = new XrealImuSource.Listener() {
        @Override
        public void onSamples() {
            if (mDrainPending.compareAndSet(false, true)) {
                mStabHandler.post(mDrainXreal);
            }
        }

        @Override
        public void onStateChanged(boolean streaming, String detail) {
            mMainHandler.post(() -> {
                if (mStabHandler == null || isDestroyed() || streaming == mXrealStreaming) {
                    if (!streaming) {
                        logFallback(detail);
                    }
                    return;
                }
                mXrealStreaming = streaming;
                if (streaming) {
                    Log.i(TAG, "imu source: xreal (" + detail + ")");
                } else {
                    logFallback(detail);
                }
                updatePhoneGyro();
            });
        }
    };

    private void logFallback(String detail) {
        String msg = "imu source: phone (imu=" + mImuMode + ", glasses unavailable: " + detail + ")";
        if ("xreal".equals(mImuMode)) {
            Log.e(TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    private final RateRing.Consumer mXrealConsumer = (t, right, up, roll) -> deliver(SOURCE_XREAL, t, right, up, roll);

    /** Stab thread: hands queued glasses samples to the stabilizer. */
    private final Runnable mDrainXreal = () -> {
        mDrainPending.set(false);  // before draining, so samples queued meanwhile post again
        RateRing ring = mXreal.ring();
        if (mPaused || !mXrealStreaming) {
            ring.clear();
            return;
        }
        ring.drain(mXrealConsumer);
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isInMultiWindowMode() && !mFullscreenRequested) {
            // On external displays in desktop mode the activity may open as a freeform window;
            // the glasses need it fullscreen.
            mFullscreenRequested = true;
            requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, new android.os.OutcomeReceiver<Void, Throwable>() {
                @Override
                public void onResult(Void result) {
                    Log.i(TAG, "left freeform window for fullscreen");
                }

                @Override
                public void onError(Throwable error) {
                    Log.w(TAG, "couldn't leave freeform window: " + error);
                }
            });
        }
        if (hasFocus) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mMenu != null && mMenu.isShowing()) {
            mMenu.dismiss("activity closing");  // before the window goes, so the dialog doesn't leak
        }
        super.onDestroy();
        if (mStabHandler == null) {
            return;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (mXreal != null) {
            mXreal.stop();
        }
        mSensorManager.unregisterListener(this);
        mLayerSnapshot = new SurfaceControl[0];
        mStabThread.quitSafely();
        try {
            mStabThread.join(500);  // no doFrame may touch a layer after it's released below
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mInjectThread.quitSafely();
        for (int i = 0; i < MAX_DESKTOPS; i++) {
            if (mDisplays[i] != null) {
                mDisplays[i].release();
            }
            if (mLayers[i] != null) {
                new SurfaceControl.Transaction().reparent(mLayers[i], null).apply();
                mLayers[i].release();
            }
            if (mSurfaces[i] != null) {
                mSurfaces[i].release();
            }
        }
        sLiveInstances--;
        if (isFinishing() && sLiveInstances == 0) {
            ShellInstrumentation shell = ShellInstrumentation.get();
            if (shell != null) {
                shell.shutdown();
            }
        }
    }

    // ---- Stabilization, roll and head gestures (stab thread) ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mXrealStreaming) {
            return;  // the glasses took over; the listener is being unregistered
        }
        long t = event.timestamp;
        float gx = event.values[0];
        float gy = event.values[1];
        // Device axes to screen axes (right, up) for the current display rotation.
        float right;
        float up;
        switch (mScreenRotation) {
            case Surface.ROTATION_90:
                right = -gy;
                up = gx;
                break;
            case Surface.ROTATION_180:
                right = -gx;
                up = -gy;
                break;
            case Surface.ROTATION_270:
                right = gy;
                up = -gx;
                break;
            default:
                right = gx;
                up = gy;
        }
        // Roll is about the screen's normal (device z), the same at every display rotation;
        // positive = counter-clockwise as seen by the user (Android sensor convention).
        deliver(SOURCE_PHONE, t, right, up, event.values[2]);
    }

    private void resetMotion() {
        mOffX = mOffY = mRateX = mRateY = 0;
        mLastSampleNs = 0;
        mGestures.reset();
        mRoll.reset();
        scheduleFrame();
    }

    /**
     * Stab thread. One rotation-rate sample from any source, in rad/s about the viewing screen's
     * right, up and back (roll) axes, with that source's timestamp in ns.
     */
    private void deliver(int source, long t, float right, float up, float roll) {
        if (source != mSampleSource) {
            // Sources have unrelated clocks; start over rather than integrate across them.
            mSampleSource = source;
            resetMotion();
        }
        if (mSwapAxes) {
            float tmp = right;
            right = up;
            up = tmp;
        }
        if (mInvertX) {
            up = -up;     // up-axis rate drives horizontal motion (and left/right flicks, shakes)
        }
        if (mInvertY) {
            right = -right;  // right-axis rate drives vertical motion (and up/down flicks)
        }
        if (mInvertRoll) {
            roll = -roll;
        }
        float dt = mLastSampleNs == 0 ? 0f : Math.min((t - mLastSampleNs) * 1e-9f, 0.05f);
        if (dt < 0f) {
            dt = 0f;
        }
        mLastSampleNs = t;

        int event = mCanSwitch ? mGestures.update(t, right, up, dt) : HeadGestures.NONE;
        if (event != HeadGestures.NONE || mGestures.inCooldown(t)) {
            // A flick or shake just happened (or the head is coming back from one): keep the
            // desktop centered and level.
            mOffX = mOffY = mRateX = mRateY = 0;
            mRoll.reset();
            if (event != HeadGestures.NONE) {
                mMainHandler.post(() -> onGesture(event));
            }
            scheduleFrame();
            return;
        }

        // Turning left (positive rotation about "up") slides the desktop right; tilting up slides it down.
        mRateX = Math.abs(up) < mDeadband ? 0f : mFocalPx * up;
        mRateY = Math.abs(right) < mDeadband ? 0f : mFocalPx * right;
        // Integrate the rotation but leak it away, so the desktop always drifts back to center.
        float decay = (float) Math.exp(-dt / mDriftSec);
        mOffX = clamp(mOffX * decay + mRateX * dt, mMaxOffset);
        mOffY = clamp(mOffY * decay + mRateY * dt, mMaxOffset);
        if (mRollEnabled) {
            mRoll.update(roll, dt);
        }
        scheduleFrame();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void scheduleFrame() {
        if (!mFrameScheduled && mStabChoreographer != null) {
            mFrameScheduled = true;
            mStabChoreographer.postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        SurfaceControl[] layers = mLayerSnapshot;
        if (layers.length == 0) {
            return;
        }
        float x = 0f;
        float y = 0f;
        float angle = 0f;
        if (mStabilize) {
            x = clamp(mOffX + mRateX * mPredictSec, mMaxOffset);
            y = clamp(mOffY + mRateY * mPredictSec, mMaxOffset);
        }
        if (mRollEnabled && mSetMatrix != null) {
            if (mRollTest != 0f) {
                angle = mRollTest;  // testing only: a fixed tilt, even with stabilization off
            } else if (mStabilize) {
                angle = mRoll.output(mPredictSec);  // exactly 0 when level
            }
        }
        // Whole pixels keep text sharp and the layer eligible for a hardware overlay.
        int px = mCenterX + Math.round(x);
        int py = mCenterY + Math.round(y);
        boolean layersChanged = layers != mAppliedLayers;
        if (px == mPosX && py == mPosY && angle == mAppliedAngle && !layersChanged) {
            return;
        }
        // Move every desktop so a hidden one is already in place when switched to.
        try {
            if (angle == 0f) {
                for (SurfaceControl l : layers) {
                    if (mSetMatrix != null && (mMatrixApplied || layersChanged)) {
                        SurfaceControl.Transaction unused =
                                (SurfaceControl.Transaction) mSetMatrix.invokeExact(mTx, l, 1f, 0f, 0f, 1f);
                    }
                    mTx.setPosition(l, px, py);
                }
                mMatrixApplied = false;
            } else {
                // Rotated about the desktop's center; the same transform maps input back.
                DesktopTransform.layerTransform(px, py, mVdWidth, mVdHeight, angle, mXform);
                for (SurfaceControl l : layers) {
                    SurfaceControl.Transaction unused = (SurfaceControl.Transaction) mSetMatrix.invokeExact(
                            mTx, l, mXform[0], mXform[1], mXform[2], mXform[3]);
                    mTx.setPosition(l, mXform[4], mXform[5]);
                }
                mMatrixApplied = true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "setMatrix failed; roll smoothing disabled", e);
            mSetMatrix = null;
            angle = 0f;
        }
        mTx.apply();
        mPosX = px;
        mPosY = py;
        mMapAngle = angle;
        mAppliedAngle = angle;
        mAppliedLayers = layers;
        mMoves++;
        long now = SystemClock.uptimeMillis();
        if (now - mStatsSinceMs >= 10000) {
            Log.i(TAG, "layer moves in last " + (now - mStatsSinceMs) / 1000 + "s: " + mMoves);
            mMoves = 0;
            mStatsSinceMs = now;
        }
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    // ---- Input forwarding (main thread) ----

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mCanSwitch || mSetDisplayId == null) {
            return super.dispatchTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        if (mSwallowGesture || mMenuOpen) {
            // The menu takes over: end any gesture already in a desktop, and drop the rest of it.
            if (mPointerDown) {
                forwardAs(ev, MotionEvent.ACTION_CANCEL, mTouchDisplayId, true);
                mPointerDown = false;
                mSwallowGesture = true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mSwallowGesture = false;
            }
            return true;
        }
        // Touches and mouse drags (button held). A gesture stays with the desktop it started on,
        // even if a switch happens mid-gesture.
        if (action == MotionEvent.ACTION_DOWN) {
            mTouchDisplayId = mDisplayIds[mActive];
            mPointerDown = true;
        }
        forward(ev, mTouchDisplayId, true);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mPointerDown = false;
        }
        return true;
    }

    /**
     * Mouse/touchpad hover, scroll and button press/release (mapped into the desktop like touches),
     * and joystick/gamepad axes (passed through). The system cursor itself is drawn on this
     * window's display and isn't stabilized; desktop content slides under it.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (!mCanSwitch || mSetDisplayId == null) {
            return super.dispatchGenericMotionEvent(ev);
        }
        boolean pointer = ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER);
        if (mMenuOpen) {
            if (pointer && mHoverDisplayId != -1) {
                forwardAs(ev, MotionEvent.ACTION_HOVER_EXIT, mHoverDisplayId);
                mHoverDisplayId = -1;
            }
            return true;
        }
        int active = mDisplayIds[mActive];
        if (!pointer) {
            forward(ev, active, false);  // joystick, gamepad, trackball, rotary...
            return true;
        }
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            forward(ev, mPointerDown ? mTouchDisplayId : active, true);
            return true;
        }
        boolean hover = action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_EXIT;
        if (hover && mHoverDisplayId != -1 && mHoverDisplayId != active) {
            // The visible desktop changed under the cursor: let the old one clear its hover state.
            forwardAs(ev, MotionEvent.ACTION_HOVER_EXIT, mHoverDisplayId);
            mHoverDisplayId = -1;
        }
        forward(ev, active, true);
        if (hover) {
            mHoverDisplayId = action == MotionEvent.ACTION_HOVER_EXIT ? -1 : active;
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0;
        InputDevice device = ev.getDevice();
        boolean builtIn = device == null || !device.isExternal();
        // The phone's own volume keys are XR Desk controls; volume keys on an external keyboard
        // or controller go to the desktop like any other key (and still change the volume there).
        if (builtIn && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (down) {
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    stepMonitor(1);
                } else {
                    mStabilize = !mStabilize;
                    Log.i(TAG, "stabilization " + (mStabilize ? "on" : "off"));
                    Toast.makeText(this, "Stabilization " + (mStabilize ? "on" : "off"), Toast.LENGTH_SHORT).show();
                    mStabHandler.post(this::scheduleFrame);
                }
            }
            return true;
        }
        // Menu shortcuts that desktop apps rarely need: F12, and a controller's Home/Guide button.
        if (code == KeyEvent.KEYCODE_F12 || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down) {
                toggleMenu(KeyEvent.keyCodeToString(code));
            }
            return true;
        }
        if (mMenuOpen) {
            return true;  // nothing reaches the desktops while the menu is open
        }
        if (!mCanSwitch || mSetDisplayId == null) {
            return super.dispatchKeyEvent(ev);
        }
        // Keyboards and gamepad/D-pad buttons, with repeats and meta state as delivered; also the
        // back gesture, which arrives as KEYCODE_BACK. The copy keeps deviceId and source.
        KeyEvent copy = new KeyEvent(ev);
        if (mInputLog) {
            Log.d(TAG, "fwd key " + KeyEvent.keyCodeToString(code) + " action=" + ev.getAction()
                    + " repeat=" + ev.getRepeatCount() + " src=0x" + Integer.toHexString(ev.getSource())
                    + " dev=" + ev.getDeviceId() + " -> display " + mDisplayIds[mActive]);
        }
        inject(copy, mDisplayIds[mActive]);
        return true;
    }

    private void forward(MotionEvent ev, int displayId, boolean mapToDesktop) {
        forwardAs(ev, ev.getAction(), displayId, mapToDesktop);
    }

    private void forwardAs(MotionEvent ev, int action, int displayId) {
        forwardAs(ev, action, displayId, true);
    }

    /**
     * Rebuilds the event for the desktop: all axes, buttons, meta state, deviceId, source and flags
     * are kept; pointer events are mapped into the desktop (drawn 1:1, possibly rolled about its
     * center). Historical samples are dropped.
     */
    private void forwardAs(MotionEvent ev, int action, int displayId, boolean mapToDesktop) {
        int masked = action & MotionEvent.ACTION_MASK;
        boolean buttonAction = masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE;
        if (buttonAction && mSetActionButton == null) {
            return;
        }
        int left = mPosX != Integer.MIN_VALUE ? mPosX : mCenterX;
        int top = mPosY != Integer.MIN_VALUE ? mPosY : mCenterY;
        float angle = mMapAngle;
        int n = ev.getPointerCount();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            ev.getPointerProperties(i, props[i]);
            coords[i] = new MotionEvent.PointerCoords();
            ev.getPointerCoords(i, coords[i]);
            if (mapToDesktop) {
                DesktopTransform.toDesktop(coords[i].x, coords[i].y, left, top, mVdWidth, mVdHeight, angle, mMapPoint);
                coords[i].x = mMapPoint[0];
                coords[i].y = mMapPoint[1];
            }
        }
        MotionEvent out = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), action, n, props, coords,
                ev.getMetaState(), ev.getButtonState(), ev.getXPrecision(), ev.getYPrecision(),
                ev.getDeviceId(), ev.getEdgeFlags(), ev.getSource(), ev.getFlags());
        if (buttonAction) {
            try {
                mSetActionButton.invoke(out, ev.getActionButton());
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "setActionButton failed", e);
                out.recycle();
                return;
            }
        }
        if (mInputLog) {
            Log.d(TAG, "fwd " + MotionEvent.actionToString(action) + " src=0x" + Integer.toHexString(ev.getSource())
                    + " dev=" + ev.getDeviceId() + " buttons=" + ev.getButtonState()
                    + " x=" + coords[0].x + " y=" + coords[0].y + " -> display " + displayId);
        }
        inject(out, displayId);
    }

    private void inject(InputEvent event, int displayId) {
        try {
            mSetDisplayId.invoke(event, displayId);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "setDisplayId failed", e);
            return;
        }
        mInjectHandler.post(() -> {
            try {
                boolean ok;
                if (mInject3 != null) {
                    // sync=false, waitForAnimations=false: don't stall on pending transactions.
                    ok = Boolean.TRUE.equals(mInject3.invoke(mUiAutomation, event, false, false));
                } else {
                    ok = mUiAutomation.injectInputEvent(event, false);
                }
                if (!ok && (mInputLog || ++mRejected <= 5)) {
                    Log.w(TAG, "injection rejected: " + event);
                }
            } catch (Throwable t) {
                Log.w(TAG, "inject failed: " + event, t);
            }
            if (event instanceof MotionEvent) {
                ((MotionEvent) event).recycle();
            }
        });
    }

    // ---- Arguments ----

    private static int intArg(Bundle args, String key, int def) {
        String v = args.getString(key);
        try {
            return v != null ? Integer.parseInt(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float floatArg(Bundle args, String key, float def) {
        String v = args.getString(key);
        try {
            return v != null ? Float.parseFloat(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
