package com.droid.xrdesk;

import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku user service: runs in its own process as the shell user, which SurfaceFlinger lets
 * change a physical display's projection. Stabilization shifts the glasses' whole picture by
 * moving the display rect of that projection; nothing is redrawn. Reached through raw Binder
 * transactions (this build has no AIDL compiler); see Helper for the client side.
 *
 * It also runs the mouse screen's pointer (MousePointer), since injecting input needs the shell.
 *
 * The base projection (what the display manager set) is read from `dumpsys display` and put back
 * when the app detaches or dies. The display manager re-applies its own projection whenever the
 * display changes; the next move overrides it again.
 */
public class ProjectionHelper extends Binder {
    static final String TAG = "XRDesk";
    static final String DESCRIPTOR = "com.droid.xrdesk.ProjectionHelper";
    /** In: IBinder client, int display id (-1 = first external display), float fill. Out: String error, int display id, String name. */
    static final int ATTACH = FIRST_CALL_TRANSACTION;
    /** Oneway. In: int dx, int dy (pixels, + = right/down). */
    static final int MOVE = FIRST_CALL_TRANSACTION + 1;
    /** Re-reads the base projection after a display change. Out: String error. */
    static final int REFRESH = FIRST_CALL_TRANSACTION + 2;
    /** Puts the base projection back, then exits. */
    static final int DETACH = FIRST_CALL_TRANSACTION + 3;
    /** Oneway mouse calls (see MousePointer). In: boolean show. */
    static final int MOUSE_SHOW = FIRST_CALL_TRANSACTION + 4;
    /** In: float x, float y (display pixels). */
    static final int MOUSE_MOVE = FIRST_CALL_TRANSACTION + 5;
    /** In: int MotionEvent button, boolean down, int display to refocus after the last release (-1 = none). */
    static final int MOUSE_BUTTON = FIRST_CALL_TRANSACTION + 6;
    /** In: float wheel notches, positive = up. */
    static final int MOUSE_SCROLL = FIRST_CALL_TRANSACTION + 7;
    /** In: boolean: show the glasses' keyboard on the phone (typing mode). Out: boolean ok. */
    static final int KEYBOARD_ON_PHONE = FIRST_CALL_TRANSACTION + 8;
    /** Oneway. In: int display id whose top task gets input focus. */
    static final int FOCUS_DISPLAY = FIRST_CALL_TRANSACTION + 9;
    /** Out: boolean: a keyboard is showing (anywhere). */
    static final int KEYBOARD_SHOWING = FIRST_CALL_TRANSACTION + 10;
    private static final int IME_POLICY_FALLBACK_DISPLAY = 1;  // WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
    /** Reserved by Shizuku: sent when the service should exit. */
    private static final int TRANSACTION_DESTROY = 16777114;
    private static final int TYPE_EXTERNAL = 2;  // android.view.Display.TYPE_EXTERNAL (hidden)

    private final SurfaceControl.Transaction mTx = new SurfaceControl.Transaction();
    private final Rect mLayerStack = new Rect();
    private final Rect mBaseDisplay = new Rect();
    private final Rect mDisplayRect = new Rect();
    private Method mSetProjection;
    private IBinder mToken;
    private long mPhysicalId;
    private int mOrientation;
    private float mFill = 1f;
    private int mDx;
    private int mDy;
    private boolean mAttached;
    private volatile MousePointer mMouse;
    private int mDisplayId = -1;
    private int mSavedImePolicy = -1;  // the glasses' own keyboard policy while typing mode changed it

    public ProjectionHelper() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case TRANSACTION_DESTROY:
                restoreAndExit();
                return true;
            case ATTACH: {
                data.enforceInterface(DESCRIPTOR);
                IBinder client = data.readStrongBinder();
                int displayId = data.readInt();
                float fill = data.readFloat();
                String[] result = attach(client, displayId, fill);
                reply.writeNoException();
                reply.writeString(result[0]);
                reply.writeInt(result[1] == null ? -1 : Integer.parseInt(result[1]));
                reply.writeString(result[2]);
                return true;
            }
            case MOVE:
                data.enforceInterface(DESCRIPTOR);
                move(data.readInt(), data.readInt());
                return true;
            case REFRESH: {
                data.enforceInterface(DESCRIPTOR);
                String error = refresh();
                reply.writeNoException();
                reply.writeString(error);
                return true;
            }
            case DETACH:
                data.enforceInterface(DESCRIPTOR);
                restore();
                reply.writeNoException();
                exitSoon();
                return true;
            case KEYBOARD_ON_PHONE: {
                data.enforceInterface(DESCRIPTOR);
                boolean ok = keyboardOnPhone(data.readBoolean());
                reply.writeNoException();
                reply.writeBoolean(ok);
                return true;
            }
            case FOCUS_DISPLAY:
                data.enforceInterface(DESCRIPTOR);
                MousePointer.focusDisplay(data.readInt());
                return true;
            case KEYBOARD_SHOWING: {
                data.enforceInterface(DESCRIPTOR);
                boolean showing = keyboardShowing();
                reply.writeNoException();
                reply.writeBoolean(showing);
                return true;
            }
            case MOUSE_SHOW:
            case MOUSE_MOVE:
            case MOUSE_BUTTON:
            case MOUSE_SCROLL: {
                data.enforceInterface(DESCRIPTOR);
                MousePointer mouse = mMouse;
                if (mouse == null) {
                    return true;
                }
                if (code == MOUSE_SHOW) {
                    mouse.show(data.readBoolean());
                } else if (code == MOUSE_MOVE) {
                    mouse.move(data.readFloat(), data.readFloat());
                } else if (code == MOUSE_BUTTON) {
                    mouse.button(data.readInt(), data.readBoolean(), data.readInt());
                } else {
                    mouse.scroll(data.readFloat());
                }
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /** Returns {error or null, display id, display name}. */
    private synchronized String[] attach(IBinder client, int requestedId, float fill) {
        try {
            if (mSetProjection == null) {
                mSetProjection = SurfaceControl.Transaction.class.getMethod("setDisplayProjection",
                        IBinder.class, int.class, Rect.class, Rect.class);
            }
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            int[] ids;
            try {
                ids = (int[]) global.getClass().getMethod("getDisplayIds").invoke(global);
            } catch (NoSuchMethodException e) {
                ids = (int[]) global.getClass().getMethod("getDisplayIds", boolean.class).invoke(global, false);
            }
            Method getInfo = global.getClass().getMethod("getDisplayInfo", int.class);
            for (int id : ids) {
                Object info = getInfo.invoke(global, id);
                if (info == null || (requestedId >= 0 ? id != requestedId : intField(info, "type") != TYPE_EXTERNAL)) {
                    continue;
                }
                // Physical displays have unique ids "local:<physical display id>".
                String unique = (String) field(info, "uniqueId");
                if (unique == null || !unique.startsWith("local:")) {
                    return new String[]{"display " + id + " isn't a physical display (" + unique + ")", null, null};
                }
                mPhysicalId = Long.parseLong(unique.substring("local:".length()));
                mToken = physicalDisplayToken(mPhysicalId);
                if (mToken == null) {
                    return new String[]{"no display token for " + mPhysicalId, null, null};
                }
                Base base = readBase(mPhysicalId);
                if (base.error != null) {
                    return new String[]{base.error, null, null};
                }
                setBase(base);
                if (mMouse != null) {
                    mMouse.release();
                }
                mMouse = new MousePointer(id, base.layerStackId, intField(info, "logicalWidth"),
                        intField(info, "logicalHeight"), intField(info, "logicalDensityDpi"));
                mDisplayId = id;
                mFill = Math.max(0.5f, Math.min(1f, fill));
                mDx = 0;
                mDy = 0;
                mAttached = true;
                client.linkToDeath(() -> {
                    Log.i(TAG, "helper: app died; restoring the display");
                    restoreAndExit();
                }, 0);
                apply();
                String name = (String) field(info, "name");
                Log.i(TAG, "helper: attached to display " + id + " (" + name + ", physical " + mPhysicalId
                        + "), base " + mLayerStack.toShortString() + " -> " + mBaseDisplay.toShortString()
                        + " orientation " + mOrientation + ", fill " + mFill);
                return new String[]{null, String.valueOf(id), name};
            }
            return new String[]{requestedId >= 0 ? "no display " + requestedId : "no external display (are the glasses connected?)",
                    null, null};
        } catch (Throwable e) {
            Log.e(TAG, "helper: attach failed", e);
            return new String[]{e.toString(), null, null};
        }
    }

    /** Applied even if unchanged: the app resends now and then in case the display manager reset it. */
    private synchronized void move(int dx, int dy) {
        if (!mAttached) {
            return;
        }
        mDx = dx;
        mDy = dy;
        apply();
    }

    /** dumpsys takes a while, so it runs outside the lock and moves continue meanwhile. */
    private String refresh() {
        long physicalId;
        synchronized (this) {
            if (!mAttached) {
                return "not attached";
            }
            physicalId = mPhysicalId;
        }
        try {
            IBinder token = physicalDisplayToken(physicalId);
            Base base = token == null ? null : readBase(physicalId);
            synchronized (this) {
                if (!mAttached) {
                    return "not attached";
                }
                if (base == null || base.error != null) {
                    mAttached = false;
                    return base == null ? "the display is gone" : base.error;
                }
                mToken = token;
                setBase(base);
                apply();
            }
            Log.i(TAG, "helper: base now " + base.layerStack.toShortString() + " -> " + base.display.toShortString()
                    + " orientation " + base.orientation);
            return null;
        } catch (Throwable e) {
            synchronized (this) {
                mAttached = false;
            }
            return e.toString();
        }
    }

    /** The base display rect, scaled by fill about its center and shifted by the offset. */
    private void apply() {
        int w = Math.round(mBaseDisplay.width() * mFill);
        int h = Math.round(mBaseDisplay.height() * mFill);
        int left = mBaseDisplay.centerX() - w / 2 + mDx;
        int top = mBaseDisplay.centerY() - h / 2 + mDy;
        mDisplayRect.set(left, top, left + w, top + h);
        project(mDisplayRect);
    }

    private void project(Rect displayRect) {
        try {
            mSetProjection.invoke(mTx, mToken, mOrientation, mLayerStack, displayRect);
            mTx.apply();
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "helper: setDisplayProjection failed", e);
        }
    }

    /**
     * Typing mode: Android shows the keyboard for a display's text fields on that display, or with
     * the "fallback" policy on the phone. While on, Gboard opens on the phone and types straight
     * into the focused field on the glasses. The display manager keeps the policy per display, so
     * the glasses' own policy is put back afterwards (also on detach).
     */
    private synchronized boolean keyboardOnPhone(boolean on) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "window");
            Object wm = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Method get = wm.getClass().getMethod("getDisplayImePolicy", int.class);
            Method set = wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
            if (on && mSavedImePolicy < 0) {
                mSavedImePolicy = (int) get.invoke(wm, mDisplayId);
                set.invoke(wm, mDisplayId, IME_POLICY_FALLBACK_DISPLAY);
            } else if (!on && mSavedImePolicy >= 0) {
                set.invoke(wm, mDisplayId, mSavedImePolicy);
                mSavedImePolicy = -1;
            }
            Log.i(TAG, "helper: keyboard for display " + mDisplayId + (on ? " on the phone" : " back to its own policy"));
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "helper: couldn't change the keyboard policy", e);
            return false;
        }
    }

    private Object mWindowManager;
    private Method mImeTouchRegion;

    private Method mImeDisplay;
    private Method mWindowInsets;
    private String mLastKeyboardState = "";

    /**
     * A keyboard is showing if its window has a touchable region and its insets source is
     * visible. The region alone can stay set when Android starts showing the keyboard and then
     * aborts (as when focus comes back to an app whose text box still has the cursor).
     */
    private boolean keyboardShowing() {
        try {
            if (mImeTouchRegion == null) {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "window");
                mWindowManager = Class.forName("android.view.IWindowManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
                Class<?> wm = mWindowManager.getClass();
                mImeTouchRegion = wm.getMethod("getCurrentImeTouchRegion");
                mImeDisplay = wm.getMethod("getImeDisplayId");
                mWindowInsets = wm.getMethod("getWindowInsets", int.class, IBinder.class,
                        Class.forName("android.view.InsetsState"));
            }
            boolean region = !((android.graphics.Region) mImeTouchRegion.invoke(mWindowManager)).isEmpty();
            boolean visible = region && imeInsetsVisible((int) mImeDisplay.invoke(mWindowManager));
            String state = "region " + region + ", insets visible " + visible;
            if (!state.equals(mLastKeyboardState)) {
                Log.i(TAG, "helper: keyboard " + state);
                mLastKeyboardState = state;
            }
            return visible;
        } catch (Throwable e) {
            Log.w(TAG, "helper: keyboard state unknown: " + e);
            return false;
        }
    }

    /** Whether the display's IME insets source is visible (from its InsetsState). */
    private boolean imeInsetsVisible(int displayId) throws ReflectiveOperationException {
        Object state = Class.forName("android.view.InsetsState").getConstructor().newInstance();
        mWindowInsets.invoke(mWindowManager, displayId, null, state);
        int ime = android.view.WindowInsets.Type.ime();
        Class<?> c = state.getClass();
        int n = (int) c.getMethod("sourceSize").invoke(state);
        for (int i = 0; i < n; i++) {
            Object source = c.getMethod("sourceAt", int.class).invoke(state, i);
            if ((int) source.getClass().getMethod("getType").invoke(source) == ime) {
                return (boolean) source.getClass().getMethod("isVisible").invoke(source);
            }
        }
        return false;
    }

    private synchronized void restore() {
        keyboardOnPhone(false);
        MousePointer mouse = mMouse;
        mMouse = null;
        if (mouse != null) {
            mouse.release();
        }
        if (mAttached) {
            mAttached = false;
            project(mBaseDisplay);
            Log.i(TAG, "helper: restored " + mLayerStack.toShortString() + " -> " + mBaseDisplay.toShortString());
        }
    }

    private void restoreAndExit() {
        restore();
        System.exit(0);
    }

    /** Lets the reply reach the caller before the process goes away. */
    private static void exitSoon() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "xrdesk-helper-exit");
        t.setDaemon(true);
        t.start();
    }

    private static final Pattern RECT = Pattern.compile("Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)");

    /**
     * The display manager's current projection for this display, from its DisplayDevice dump:
     * mUniqueId=local:<physical id>, then mCurrentOrientation, mCurrentLayerStackRect and
     * mCurrentDisplayRect. These keep the display manager's values even while ours is applied.
     */
    private static Base readBase(long physicalId) throws Exception {
        Process p = new ProcessBuilder("/system/bin/dumpsys", "display").redirectErrorStream(true).start();
        String unique = "mUniqueId=local:" + physicalId;
        boolean inDevice = false;
        Integer orientation = null;
        Integer layerStackId = null;
        Rect layerStack = null;
        Rect display = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.startsWith("mUniqueId=")) {
                    if (inDevice && display != null) {
                        break;
                    }
                    inDevice = s.equals(unique);
                } else if (inDevice && s.startsWith("mCurrentLayerStack=")) {
                    layerStackId = Integer.parseInt(s.substring("mCurrentLayerStack=".length()));
                } else if (inDevice && s.startsWith("mCurrentOrientation=")) {
                    orientation = Integer.parseInt(s.substring("mCurrentOrientation=".length()));
                } else if (inDevice && s.startsWith("mCurrentLayerStackRect=")) {
                    layerStack = parseRect(s);
                } else if (inDevice && s.startsWith("mCurrentDisplayRect=")) {
                    display = parseRect(s);
                }
            }
        }
        p.waitFor();
        Base base = new Base();
        if (orientation == null || layerStackId == null || layerStack == null || display == null || display.isEmpty()) {
            base.error = "couldn't read the projection of physical display " + physicalId + " from dumpsys display";
            return base;
        }
        base.orientation = orientation;
        base.layerStackId = layerStackId;
        base.layerStack = layerStack;
        base.display = display;
        return base;
    }

    private static final class Base {
        String error;
        int orientation;
        int layerStackId;
        Rect layerStack;
        Rect display;
    }

    private void setBase(Base base) {
        mOrientation = base.orientation;
        mLayerStack.set(base.layerStack);
        mBaseDisplay.set(base.display);
    }

    private static Rect parseRect(String s) {
        Matcher m = RECT.matcher(s);
        if (!m.find()) {
            return null;
        }
        return new Rect(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
    }

    // ---- Hidden APIs (this process isn't subject to hidden API checks) ----

    private static Class<?> sDisplayControl;

    /** DisplayControl lives in services.jar and needs libandroid_servers (Android 14+), as in scrcpy. */
    private static IBinder physicalDisplayToken(long physicalId) throws Exception {
        if (sDisplayControl == null) {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class,
                    String.class, ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader cl = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), dc, "android_servers");
            sDisplayControl = dc;
        }
        return (IBinder) sDisplayControl.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physicalId);
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getField(name);
        return f.get(o);
    }

    private static int intField(Object o, String name) throws ReflectiveOperationException {
        return o.getClass().getField(name).getInt(o);
    }
}
