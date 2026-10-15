package com.droid.xrdesk;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * A standard mouse on one display, driven by the app's mouse screen (runs in the shell helper,
 * which may inject input). Events are injected like a real mouse's: hover while no button is
 * held, DOWN + BUTTON_PRESS / BUTTON_RELEASE + UP around clicks, MOVE while dragging, SCROLL for
 * the wheel. Android doesn't draw its cursor for injected events, so this shows its arrow icon
 * itself: a layer on the display's layer stack, which the stabilizer's projection shifts along
 * with the picture.
 *
 * <p>A click moves input focus to the clicked window's display (and brings the window to the
 * front); after the last button is released, focus goes back to {@code homeDisplay} (where the
 * mouse screen is) so the phone's volume keys keep reaching the app.
 */
final class MousePointer {
    private static final String TAG = ProjectionHelper.TAG;
    private static final int DRAWN_SIZE = 40;    // fallback arrow, px; about 34 px tall
    private static final int DRAWN_HOTSPOT = 3;  // its tip, from the top-left

    private final int mDisplayId;
    private final int mLayerStack;
    private final int mWidth;
    private final int mHeight;
    private final int mDensityDpi;
    private int mHotX;  // the cursor image's hotspot
    private int mHotY;
    private final SurfaceControl.Transaction mTx = new SurfaceControl.Transaction();
    private final MotionEvent.PointerProperties[] mProps = {new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] mCoords = {new MotionEvent.PointerCoords()};
    private Method mSetDisplayId;
    private Method mSetActionButton;
    private Method mInject;
    private Object mInputManager;
    private SurfaceControl mCursor;
    private Surface mCursorSurface;
    private boolean mShown;
    private boolean mHovering;
    private int mButtons;
    private long mDownTime;
    private float mX;
    private float mY;

    MousePointer(int displayId, int layerStack, int width, int height, int densityDpi) {
        mDisplayId = displayId;
        mLayerStack = layerStack;
        mWidth = width;
        mHeight = height;
        mDensityDpi = densityDpi;
        mX = width / 2f;
        mY = height / 2f;
        mProps[0].id = 0;
        mProps[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    }

    synchronized void show(boolean show) {
        if (show == mShown) {
            return;
        }
        try {
            if (show) {
                ensureCursor();
                mShown = true;
                placeCursor();
                mTx.setVisibility(mCursor, true).apply();
                hover(MotionEvent.ACTION_HOVER_ENTER);
            } else {
                for (int button : new int[]{MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY}) {
                    if ((mButtons & button) != 0) {
                        button(button, false, -1);
                    }
                }
                if (mHovering) {
                    hover(MotionEvent.ACTION_HOVER_EXIT);
                }
                mShown = false;
                if (mCursor != null) {
                    mTx.setVisibility(mCursor, false).apply();
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "mouse: show(" + show + ") failed", e);
        }
    }

    synchronized void move(float x, float y) {
        if (!mShown) {
            return;
        }
        mX = Math.max(0f, Math.min(mWidth - 1, x));
        mY = Math.max(0f, Math.min(mHeight - 1, y));
        placeCursor();
        if (mButtons != 0) {
            inject(MotionEvent.ACTION_MOVE, mDownTime, 0, 0f);  // dragging
        } else {
            hover(mHovering ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER);
        }
    }

    /** button: MotionEvent.BUTTON_PRIMARY or BUTTON_SECONDARY. homeDisplay: refocus after the last release, or -1. */
    synchronized void button(int button, boolean down, int homeDisplay) {
        if (!mShown || down == ((mButtons & button) != 0)) {
            return;
        }
        Log.i(TAG, "mouse: " + (button == MotionEvent.BUTTON_PRIMARY ? "left" : "right") + (down ? " down" : " up")
                + " at " + Math.round(mX) + "," + Math.round(mY));
        if (down) {
            if (mHovering) {
                hover(MotionEvent.ACTION_HOVER_EXIT);
            }
            if (mButtons == 0) {
                mDownTime = SystemClock.uptimeMillis();
                mButtons = button;
                inject(MotionEvent.ACTION_DOWN, mDownTime, 0, 0f);
            } else {
                mButtons |= button;
            }
            inject(MotionEvent.ACTION_BUTTON_PRESS, mDownTime, button, 0f);
        } else {
            mButtons &= ~button;
            inject(MotionEvent.ACTION_BUTTON_RELEASE, mDownTime, button, 0f);
            if (mButtons == 0) {
                inject(MotionEvent.ACTION_UP, mDownTime, 0, 0f);
                hover(MotionEvent.ACTION_HOVER_ENTER);
                if (homeDisplay >= 0) {
                    focusDisplay(homeDisplay);
                }
            }
        }
    }

    /** One wheel notch = 1; positive scrolls up (toward the top of the content). */
    synchronized void scroll(float notches) {
        if (mShown && notches != 0f) {
            inject(MotionEvent.ACTION_SCROLL, SystemClock.uptimeMillis(), 0, notches);
        }
    }

    /** Removes the cursor (the display is being given back). */
    synchronized void release() {
        show(false);
        if (mCursor != null) {
            mTx.reparent(mCursor, null).apply();
            mCursor.release();
            mCursor = null;
        }
        if (mCursorSurface != null) {
            mCursorSurface.release();
            mCursorSurface = null;
        }
    }

    private void hover(int action) {
        inject(action, SystemClock.uptimeMillis(), 0, 0f);
        mHovering = action != MotionEvent.ACTION_HOVER_EXIT;
    }

    private void inject(int action, long downTime, int actionButton, float vscroll) {
        try {
            if (mInject == null) {
                mSetDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
                mSetActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
                Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
                mInputManager = global.getMethod("getInstance").invoke(null);
                mInject = global.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            }
            MotionEvent.PointerCoords c = mCoords[0];
            c.clear();
            c.x = mX;
            c.y = mY;
            c.pressure = mButtons != 0 ? 1f : 0f;
            if (vscroll != 0f) {
                c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
            }
            MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, mProps, mCoords,
                    0, mButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            mSetDisplayId.invoke(ev, mDisplayId);
            if (actionButton != 0) {
                mSetActionButton.invoke(ev, actionButton);
            }
            Object ok = mInject.invoke(mInputManager, ev, 0 /* InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */);
            if (!Boolean.TRUE.equals(ok)) {
                Log.w(TAG, "mouse: rejected " + MotionEvent.actionToString(action));
            }
            ev.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "mouse: inject failed", e);
        }
    }

    /** Gives input focus to a display's top task (the mouse screen on the phone, or the glasses). */
    static void focusDisplay(int displayId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            atm.getClass().getMethod("focusTopTask", int.class).invoke(atm, displayId);
        } catch (Throwable e) {
            Log.w(TAG, "mouse: focusTopTask failed: " + e);
        }
    }

    private void placeCursor() {
        if (mCursor != null) {
            mTx.setPosition(mCursor, Math.round(mX) - mHotX, Math.round(mY) - mHotY).apply();
        }
    }

    /** A root layer on the display's layer stack, above everything, showing the arrow. */
    private void ensureCursor() throws ReflectiveOperationException {
        if (mCursor != null) {
            return;
        }
        Bitmap arrow = systemArrow();
        if (arrow == null) {
            arrow = drawnArrow();
            mHotX = mHotY = DRAWN_HOTSPOT;
        }
        mCursor = new SurfaceControl.Builder()
                .setName("XR Desk pointer")
                .setBufferSize(arrow.getWidth(), arrow.getHeight())
                .setFormat(PixelFormat.TRANSLUCENT)
                .build();
        mCursorSurface = new Surface(mCursor);
        Canvas c = mCursorSurface.lockCanvas(null);
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        c.drawBitmap(arrow, 0f, 0f, null);
        mCursorSurface.unlockCanvasAndPost(c);
        SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class)
                .invoke(mTx, mCursor, mLayerStack);
        // Android drops touches (and our clicks) on a window that another app's surface covers
        // (untrusted touch occlusion), and the arrow covers the very pixel being clicked. Trusted
        // overlays, like the system's own pointer, don't count. Needs ACCESS_SURFACE_FLINGER (shell).
        SurfaceControl.Transaction.class.getMethod("setTrustedOverlay", SurfaceControl.class, boolean.class)
                .invoke(mTx, mCursor, true);
        mTx.setLayer(mCursor, Integer.MAX_VALUE).setVisibility(mCursor, false).apply();
        Log.i(TAG, "mouse: cursor " + arrow.getWidth() + "x" + arrow.getHeight() + " on layer stack " + mLayerStack
                + ", display " + mDisplayId + " " + mWidth + "x" + mHeight + " @" + mDensityDpi + "dpi");
    }

    /**
     * Android's own arrow pointer, as drawn for a real mouse on this display: the framework's
     * vector arrow, rendered at the display's density in the default colors (black, white
     * outline), with a soft drop shadow like the system cursor's. Null if that fails.
     */
    private Bitmap systemArrow() {
        try {
            // Bitmaps default to the phone's density; the icon would be drawn scaled up by
            // phone/glasses density and cropped.
            Bitmap.class.getMethod("setDefaultDensity", int.class).invoke(null, mDensityDpi);
            Resources sys = Resources.getSystem();
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setTo(sys.getDisplayMetrics());
            metrics.densityDpi = mDensityDpi;
            metrics.density = mDensityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
            metrics.scaledDensity = metrics.density;
            Configuration config = new Configuration(sys.getConfiguration());
            config.densityDpi = mDensityDpi;
            Resources res = new Resources(sys.getAssets(), metrics, config);
            // The vector icon takes its colors from the theme (as set in accessibility settings).
            Resources.Theme theme = res.newTheme();
            theme.applyStyle((int) PointerIcon.class.getMethod("vectorFillStyleToResource", int.class)
                    .invoke(null, 0 /* black */), true);
            theme.applyStyle((int) PointerIcon.class.getMethod("vectorStrokeStyleToResource", int.class)
                    .invoke(null, 0 /* white */), true);
            Constructor<PointerIcon> ctor = PointerIcon.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            PointerIcon icon = ctor.newInstance(PointerIcon.TYPE_ARROW);
            Method load = PointerIcon.class.getDeclaredMethod("loadResource", Resources.class, int.class,
                    Resources.Theme.class, float.class);
            load.setAccessible(true);
            load.invoke(icon, res, res.getIdentifier("pointer_arrow_vector_icon", "drawable", "android"), theme, 1f);
            Bitmap arrow = (Bitmap) PointerIcon.class.getMethod("getBitmap").invoke(icon);
            int hotX = Math.round(floatField(icon, "mHotSpotX"));
            int hotY = Math.round(floatField(icon, "mHotSpotY"));

            // Room for the shadow below and to the right.
            float blur = 2f * metrics.density;
            int pad = Math.round(2 * blur);
            Bitmap out = Bitmap.createBitmap(arrow.getWidth() + pad, arrow.getHeight() + pad, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            shadow.setColor(0x60000000);
            c.drawBitmap(arrow.extractAlpha(), 0f, blur * 0.75f, shadow);
            c.drawBitmap(arrow, 0f, 0f, null);
            mHotX = hotX;
            mHotY = hotY;
            return out;
        } catch (Throwable e) {
            Log.w(TAG, "mouse: no system pointer icon, drawing one: " + e);
            return null;
        }
    }

    private static float floatField(Object o, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(o);
    }

    /** A plain white arrow with a black outline. */
    private static Bitmap drawnArrow() {
        Bitmap bitmap = Bitmap.createBitmap(DRAWN_SIZE, DRAWN_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Path arrow = new Path();
        float[] pts = {0, 0, 0, 28, 7, 22, 12, 33, 17, 31, 12, 20, 21, 20};
        arrow.moveTo(DRAWN_HOTSPOT + pts[0], DRAWN_HOTSPOT + pts[1]);
        for (int i = 2; i < pts.length; i += 2) {
            arrow.lineTo(DRAWN_HOTSPOT + pts[i], DRAWN_HOTSPOT + pts[i + 1]);
        }
        arrow.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawPath(arrow, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.BLACK);
        c.drawPath(arrow, p);
        return bitmap;
    }
}
