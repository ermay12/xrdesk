package com.droid.xrdesk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The mouse screen: the phone becomes a mouse for the glasses. Where the phone's top edge points
 * sets the cursor, like a laser pointer; volume down recenters it. At the bottom, a block as tall
 * as the scroll strip holds Right click above Left click, with the scroll strip on the right
 * (drag down to scroll down). The pointer itself runs in the shell helper (MousePointer); this
 * screen starts StabilizerService in mouse mode if it isn't running, so stabilizing is optional.
 *
 * <p>Pointing: the rotation comes from Android's sensor fusion (game rotation vector: gyro +
 * accelerometer) at 100 Hz. The cursor follows the compass heading and elevation of the phone's
 * top edge relative to where it pointed when centered; sideways motion is scaled by the cosine
 * of that elevation, so a horizontal sweep is a straight line at the same speed however the phone
 * is tilted. Samples are handled on their own thread with the button and scroll events, in order.
 *
 * <p>Typing: while this screen is open, the glasses' keyboard shows on the phone (Gboard, with
 * autocorrect), typing straight into the text field clicked on the glasses. That needs input
 * focus on the glasses, so after a click focus stays there for up to KEYBOARD_WAIT_MS while the
 * helper checks whether a keyboard opened: if so, typing mode starts by itself; if not, focus
 * goes back to the phone for the volume keys. In typing mode this screen stops taking focus
 * when touched (else tapping a button would close the keyboard), the controls move up above the
 * keyboard, and the mode ends by itself once the keyboard closes. Volume down can't reach this
 * screen meanwhile. The Type button switches typing mode by hand, for apps that don't open the
 * keyboard themselves; the controls only move up while a keyboard is actually showing. The
 * system Back only closes the keyboard; the Back button at the top leaves this screen.
 *
 * <p>Click lock: pressing a button jolts the phone, which would turn a click into a small drag.
 * So the cursor freezes while a button is held, until the phone turns past DRAG_START_RAD (a
 * deliberate drag), and for RELEASE_HOLD_MS after release; also while scrolling. The motion
 * meanwhile is absorbed into the center, so the cursor doesn't jump when it unfreezes.
 */
public class MouseActivity extends Activity implements SensorEventListener {
    private static final float SCROLL_HEIGHT_IN = 2.5f;
    private static final float TYPING_SCROLL_HEIGHT_IN = 1.5f;  // smaller, to fit above the keyboard
    private static final float TYPE_BUTTON_HEIGHT_IN = 0.45f;
    private static final int TYPING_COLOR = 0xFF1E4F8C;
    private static final float SCROLL_WIDTH_IN = 0.75f;
    private static final float SCROLL_NOTCH_IN = 0.15f;  // finger travel per wheel notch
    private static final float DRAG_START_RAD = (float) Math.toRadians(3);
    private static final long RELEASE_HOLD_MS = 250;
    private static final long KEYBOARD_WAIT_MS = 600;     // after a click, for a keyboard to open
    private static final long KEYBOARD_POLL_MS = 50;
    private static final long KEYBOARD_WATCH_MS = 300;    // in typing mode, for it to close
    private static final int BUTTON_COLOR = 0xFF2A2A2A;
    private static final int PRESSED_COLOR = 0xFF5A5A5A;

    private static final String TAG = "XRDesk";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mPointerThread;
    private Handler mPointer;           // sensor samples, buttons and scrolling, in order
    private SensorManager mSensors;
    private Sensor mRotation;
    private TextView mStatus;
    private volatile Helper mHelper;    // null until the helper is attached to the glasses

    // Pointer thread from here down (set up on main before the sensor is registered)
    private final float[] mMatrix = new float[9];
    private int mWidth;                 // the glasses' display, px
    private int mHeight;
    private float mPxPerRad;
    private boolean mCentered;          // false = the next sample becomes the center
    private float mYaw0;                // the heading and elevation at the center
    private float mPitch0;
    private float mCosE0;               // sideways scale: the cosine of that elevation
    private float mOffA;                // angle offsets from edge pushes and the click lock
    private float mOffB;
    private float mA;                   // the latest angles from the center: right, up
    private float mB;
    private float mSentX = Float.NaN;
    private float mSentY = Float.NaN;
    private int mButtonsDown;
    private boolean mScrolling;
    private boolean mDragging;          // a button is held and the phone turned past DRAG_START_RAD
    private long mHoldUntilMs;          // frozen until then after a release
    private float mPressA;              // the angles when the button went down
    private float mPressB;
    private long mLastSampleNs;         // stats
    private long mMaxGapNs;
    private int mSamples;
    private long mStatsSinceMs;
    private long mKeyboardWaitUntilMs;
    private boolean mKeyboardSeen;      // typing mode: the keyboard has shown since it started
    private int mKeyboardGoneChecks;

    private float mScrollNotchPx;       // main thread
    private LinearLayout mRoot;
    private View mSpacer;
    private View mBlock;
    private GradientDrawable mTypeBg;
    private TextView mSpeedLabel;
    private SeekBar mSpeedSlider;
    private volatile boolean mTyping;   // main thread writes
    private boolean mKeyboardShown;     // main thread: the controls are moved up for it
    private volatile int mHomeDisplay;  // this screen's display (the phone)
    private float mScrollLastY;         // main thread
    private float mScrollTicks;

    /** Picks up the helper when stabilizing starts or restarts while this screen is open. */
    private final Runnable mWatch = new Runnable() {
        @Override
        public void run() {
            Helper current = StabilizerService.helper();
            if (current != mHelper) {
                connect();
            } else if (current == null) {
                mStatus.setText(StabilizerService.status());  // starting, or why it couldn't
            }
            mMain.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mSensors = getSystemService(SensorManager.class);
        mRotation = mSensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        mScrollNotchPx = inches(SCROLL_NOTCH_IN);
        mPointerThread = new HandlerThread("xrdesk-pointer", Process.THREAD_PRIORITY_DISPLAY);
        mPointerThread.start();
        mPointer = new Handler(mPointerThread.getLooper());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(24 + bars.left, 16 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });

        // Top: Back, Center cursor, Center screen; the status below them.
        LinearLayout bar = new LinearLayout(this);
        bar.addView(menuButton("Back", v -> finish()), weightedWidth());
        bar.addView(menuButton("Center cursor", this::recenter), weightedWidth());
        bar.addView(menuButton("Center screen", v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (StabilizerService.running()) {
                startService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_RECENTER));
            }
        }), weightedWidth());
        root.addView(bar);
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.LTGRAY);
        mStatus.setTextSize(14);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, 24, 0, 0);
        root.addView(mStatus);

        // Mouse speed, applied right away; 1.0x = the cursor goes where the phone points.
        mSpeedLabel = label("");
        mSpeedLabel.setTextSize(16);
        mSpeedLabel.setPadding(0, 32, 0, 8);
        root.addView(mSpeedLabel);
        root.addView(speedSlider());

        // Bottom: two columns of the same height. Left: Right click above Left click. Right: the
        // scroll strip above the Type button. In typing mode the spacer moves below them.
        mRoot = root;
        mSpacer = new View(this);
        root.addView(mSpacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout block = new LinearLayout(this);
        mBlock = block;
        LinearLayout clicks = new LinearLayout(this);
        clicks.setOrientation(LinearLayout.VERTICAL);
        clicks.addView(clickButton("Right click", MotionEvent.BUTTON_SECONDARY), weightedHeight(0, 0, 0, 8));
        clicks.addView(clickButton("Left click", MotionEvent.BUTTON_PRIMARY), weightedHeight(0, 8, 0, 0));
        block.addView(clicks, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(scrollStrip(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mTypeBg = rounded(BUTTON_COLOR);
        TextView type = label("Type");
        type.setTextSize(16);
        type.setBackground(mTypeBg);
        type.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            setTyping(!mTyping);
        });
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(inches(TYPE_BUTTON_HEIGHT_IN)));
        typeParams.topMargin = 16;
        side.addView(type, typeParams);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(
                Math.round(inches(SCROLL_WIDTH_IN)), LinearLayout.LayoutParams.MATCH_PARENT);
        sideParams.leftMargin = 16;
        block.addView(side, sideParams);
        root.addView(block, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, blockHeight(false)));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service runs while stabilizing or while this screen is open; it attaches the helper.
        startForegroundService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_MOUSE_ON));
        connect();
        mMain.postDelayed(mWatch, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMain.removeCallbacks(mWatch);
        setTyping(false);
        disconnect();
        if (StabilizerService.running()) {
            startService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_MOUSE_OFF));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPointerThread.quitSafely();
    }

    /** (Re)connects to the helper and shows the cursor on its display. */
    private void connect() {
        disconnect();
        Helper helper = StabilizerService.helper();
        Display display = getSystemService(DisplayManager.class).getDisplay(StabilizerService.displayId());
        if (helper == null || display == null) {
            mStatus.setText(StabilizerService.running() ? StabilizerService.status() : "Starting…");
            return;
        }
        if (mRotation == null) {
            mStatus.setText("This phone has no rotation sensor");
            return;
        }
        mHomeDisplay = getDisplay().getDisplayId();
        Point size = new Point();
        display.getRealSize(size);
        Options options = Options.load(this);
        float pxPerRad = options.pointer;
        mSpeedSlider.setProgress(Math.round(options.pointer / options.focal * 10f));
        mPointer.post(() -> {
            mWidth = size.x;
            mHeight = size.y;
            mPxPerRad = pxPerRad;
            mCentered = false;
            mSentX = mSentY = Float.NaN;
            mButtonsDown = 0;
            mScrolling = false;
            mHoldUntilMs = 0;
            mSamples = 0;
            mMaxGapNs = 0;
            mLastSampleNs = 0;
            mStatsSinceMs = SystemClock.uptimeMillis();
            helper.mouseShow(true);
            if (!helper.keyboardOnPhone(true)) {
                Log.w(TAG, "couldn't move the glasses' keyboard to the phone");
            }
            mHelper = helper;
        });
        mSensors.registerListener(this, mRotation, 10_000 /* us */, mPointer);
        showStatus();
    }

    private void showStatus() {
        if (mTyping) {
            mStatus.setText(mKeyboardShown
                    ? "Typing on the glasses. Ends when the keyboard closes, or press Back or Type."
                    : "Click a text box on the glasses to type. Type or Back cancels.");
        } else {
            mStatus.setText(StabilizerService.stabilizing()
                    ? "Point with the top of the phone. Volume down centers the cursor. Click a text box to type."
                    : "Mouse only (not stabilizing). Volume down centers the cursor. Click a text box to type.");
        }
    }

    /** Typing mode on/off (main thread); see the class comment. */
    private void setTyping(boolean on) {
        Helper helper = mHelper;
        if (on == mTyping || (on && helper == null)) {
            return;
        }
        mTyping = on;
        Log.i(TAG, "typing mode " + (on ? "on" : "off"));
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            helper.focusDisplay(StabilizerService.displayId());  // already there after a click
            mPointer.post(() -> {
                mPointer.removeCallbacks(mAfterClick);
                mKeyboardSeen = false;
                mKeyboardGoneChecks = 0;
                mPointer.removeCallbacks(mWatchKeyboard);
                mPointer.postDelayed(mWatchKeyboard, KEYBOARD_WATCH_MS);
            });
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            if (helper != null) {
                helper.focusDisplay(mHomeDisplay);  // volume keys reach this screen again
            }
        }
        mTypeBg.setColor(on ? TYPING_COLOR : BUTTON_COLOR);
        if (!on) {
            keyboardShown(false);
        }
        showStatus();
    }

    /**
     * Main thread. While a keyboard is on screen, the controls sit just under the status, above
     * it; typing mode alone (Type pressed outside a text box) doesn't move them.
     */
    private void keyboardShown(boolean shown) {
        if (shown == mKeyboardShown) {
            return;
        }
        mKeyboardShown = shown;
        mRoot.removeView(mSpacer);
        mRoot.addView(mSpacer, shown ? mRoot.getChildCount() : mRoot.indexOfChild(mBlock),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mBlock.getLayoutParams().height = blockHeight(shown);
        int speedVisibility = shown ? View.GONE : View.VISIBLE;  // room above the keyboard
        mSpeedLabel.setVisibility(speedVisibility);
        mSpeedSlider.setVisibility(speedVisibility);
        mBlock.requestLayout();
        showStatus();
    }

    private void disconnect() {
        mSensors.unregisterListener(this);
        mPointer.post(() -> {
            Helper helper = mHelper;
            mHelper = null;
            mPointer.removeCallbacks(mAfterClick);
            mPointer.removeCallbacks(mWatchKeyboard);
            if (helper != null) {
                helper.mouseShow(false);  // also releases any held button
                helper.keyboardOnPhone(false);
            }
        });
    }

    private void recenter(View feedback) {
        mPointer.post(() -> mCentered = false);
        feedback.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    /** Pointer thread, after a click: a keyboard opened means typing; else focus back to the phone. */
    private final Runnable mAfterClick = new Runnable() {
        @Override
        public void run() {
            Helper helper = mHelper;
            if (helper == null || mTyping) {
                return;
            }
            if (helper.keyboardShowing()) {
                mMain.post(() -> {
                    setTyping(true);
                    keyboardShown(true);
                });
            } else if (SystemClock.uptimeMillis() < mKeyboardWaitUntilMs) {
                mPointer.postDelayed(this, KEYBOARD_POLL_MS);
            } else {
                helper.focusDisplay(mHomeDisplay);  // volume keys reach this screen again
            }
        }
    };

    /** Pointer thread, in typing mode: ends it once the keyboard has shown and then closed. */
    private final Runnable mWatchKeyboard = new Runnable() {
        @Override
        public void run() {
            Helper helper = mHelper;
            if (helper == null || !mTyping) {
                return;
            }
            boolean showing = helper.keyboardShowing();
            if (showing) {
                mKeyboardSeen = true;
                mKeyboardGoneChecks = 0;
            } else if (mKeyboardSeen && ++mKeyboardGoneChecks >= 2) {
                mMain.post(() -> setTyping(false));
                return;
            }
            mMain.post(() -> {
                if (mTyping) {
                    keyboardShown(showing);
                }
            });
            mPointer.postDelayed(this, KEYBOARD_WATCH_MS);
        }
    };

    /**
     * The system Back only closes the keyboard (leaves typing mode); otherwise it does nothing, as
     * it's easy to hit by accident here. The Back button at the top leaves this screen.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mTyping) {
            setTyping(false);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                recenter(mStatus);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || super.onKeyUp(keyCode, event);
    }

    // ---- Pointing ----

    /** Pointer thread. */
    @Override
    public void onSensorChanged(SensorEvent event) {
        Helper helper = mHelper;
        if (helper == null) {
            return;
        }
        stats(event.timestamp);
        SensorManager.getRotationMatrixFromVector(mMatrix, event.values);
        // Where the phone's top edge (device +y) points, in world coordinates (z up).
        float px = mMatrix[1];
        float py = mMatrix[4];
        float pz = mMatrix[7];
        float yaw = (float) Math.atan2(px, py);                             // + = turned right
        float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, pz)));  // + = pointing up
        if (!mCentered) {
            mYaw0 = yaw;
            mPitch0 = pitch;
            mCosE0 = Math.max(0.2f, (float) Math.cos(pitch));
            mOffA = mOffB = 0f;
            mCentered = true;
        }
        mA = wrap(yaw - mYaw0) * mCosE0;  // the sideways angle as seen at the centering tilt
        mB = pitch - mPitch0;
        if (mButtonsDown != 0 && !mDragging && Math.hypot(mA - mPressA, mB - mPressB) > DRAG_START_RAD) {
            mDragging = true;  // a deliberate drag: let the cursor go
        }
        float x = mWidth / 2f + (mA - mOffA) * mPxPerRad;
        float y = mHeight / 2f - (mB - mOffB) * mPxPerRad;
        float cx;
        float cy;
        if (frozen()) {
            // Hold the cursor where it is and absorb the motion into the offsets.
            cx = mSentX;
            cy = mSentY;
        } else {
            // Past an edge, the offsets follow, so the pointer leaves the edge as soon as the phone turns back.
            cx = Math.max(0f, Math.min(mWidth - 1, x));
            cy = Math.max(0f, Math.min(mHeight - 1, y));
        }
        mOffA += (x - cx) / mPxPerRad;
        mOffB -= (y - cy) / mPxPerRad;
        if (Math.abs(cx - mSentX) >= 0.5f || Math.abs(cy - mSentY) >= 0.5f || Float.isNaN(mSentX)) {
            helper.mouseMove(cx, cy);
            mSentX = cx;
            mSentY = cy;
        }
    }

    private boolean frozen() {
        if (Float.isNaN(mSentX)) {
            return false;  // nothing sent yet to hold
        }
        return mScrolling || (mButtonsDown != 0 && !mDragging) || SystemClock.uptimeMillis() < mHoldUntilMs;
    }

    /** Logs the sensor rate and the longest gap every 10 s (adb logcat -s XRDesk). */
    private void stats(long t) {
        if (mLastSampleNs != 0) {
            mMaxGapNs = Math.max(mMaxGapNs, t - mLastSampleNs);
        }
        mLastSampleNs = t;
        mSamples++;
        long now = SystemClock.uptimeMillis();
        if (now - mStatsSinceMs >= 10_000) {
            Log.i(TAG, "pointer: " + mSamples * 1000L / (now - mStatsSinceMs) + " samples/s, longest gap "
                    + mMaxGapNs / 1_000_000 + " ms");
            mSamples = 0;
            mMaxGapNs = 0;
            mStatsSinceMs = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float wrap(float a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    // ---- Buttons and scrolling ----

    private View clickButton(String label, int button) {
        GradientDrawable bg = rounded(BUTTON_COLOR);
        TextView v = label(label);
        v.setBackground(bg);
        v.setOnTouchListener((view, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                bg.setColor(PRESSED_COLOR);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                mPointer.post(() -> {
                    mPointer.removeCallbacks(mAfterClick);  // this click's release decides again
                    if (mButtonsDown == 0) {
                        mDragging = false;
                        mPressA = mA;
                        mPressB = mB;
                    }
                    mButtonsDown |= button;
                    Helper helper = mHelper;
                    if (helper != null) {
                        helper.mouseButton(button, true, -1);
                    }
                });
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                bg.setColor(BUTTON_COLOR);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                mPointer.post(() -> {
                    mButtonsDown &= ~button;
                    Helper helper = mHelper;
                    if (helper != null) {
                        helper.mouseButton(button, false, -1);  // focus stays on the glasses for now
                    }
                    if (mButtonsDown == 0) {
                        mHoldUntilMs = SystemClock.uptimeMillis() + RELEASE_HOLD_MS;
                        if (!mTyping) {
                            // Did the click open a keyboard? Typing mode if so, else focus back here.
                            mKeyboardWaitUntilMs = SystemClock.uptimeMillis() + KEYBOARD_WAIT_MS;
                            mPointer.postDelayed(mAfterClick, KEYBOARD_POLL_MS);
                        }
                    }
                });
            }
            return true;
        });
        return v;
    }

    /** Drag down to scroll down, like a scroll bar; one wheel notch per SCROLL_NOTCH_IN of travel. */
    private View scrollStrip() {
        GradientDrawable bg = rounded(BUTTON_COLOR);
        TextView v = label("▲\n\n\nScroll\n\n\n▼");
        v.setBackground(bg);
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bg.setColor(PRESSED_COLOR);
                    mPointer.post(() -> mScrolling = true);
                    mScrollLastY = ev.getY();
                    mScrollTicks = 0f;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float notches = -(ev.getY() - mScrollLastY) / mScrollNotchPx;
                    mScrollLastY = ev.getY();
                    mPointer.post(() -> {
                        Helper helper = mHelper;
                        if (helper != null) {
                            helper.mouseScroll(notches);
                        }
                    });
                    mScrollTicks += notches;
                    if (Math.abs(mScrollTicks) >= 1f) {
                        mScrollTicks -= Math.signum(mScrollTicks);
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    bg.setColor(BUTTON_COLOR);
                    mPointer.post(() -> {
                        mScrolling = false;
                        mHoldUntilMs = SystemClock.uptimeMillis() + RELEASE_HOLD_MS;
                    });
                    break;
                default:
                    break;
            }
            return true;
        });
        return v;
    }

    private Button menuButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(20);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private static GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(36f);
        return d;
    }

    private static LinearLayout.LayoutParams weightedWidth() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(4, 0, 4, 0);
        return p;
    }

    private static LinearLayout.LayoutParams weightedHeight(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    /** Both columns: the scroll strip (or the click buttons) plus the Type button below it. */
    private int blockHeight(boolean typing) {
        return Math.round(inches((typing ? TYPING_SCROLL_HEIGHT_IN : SCROLL_HEIGHT_IN) + TYPE_BUTTON_HEIGHT_IN)) + 16;
    }

    /** 0.3x to 3x in tenths; saved as the pointer option (pixels per radian = speed x focal). */
    private SeekBar speedSlider() {
        mSpeedSlider = new SeekBar(this);
        mSpeedSlider.setMin(3);
        mSpeedSlider.setMax(30);
        mSpeedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int tenths, boolean fromUser) {
                showSpeed(tenths);
                if (!fromUser) {
                    return;
                }
                float pxPerRad = tenths / 10f * Options.load(MouseActivity.this).focal;
                Options.put(MouseActivity.this, "pointer", String.valueOf(Math.round(pxPerRad)));
                mPointer.post(() -> setSpeed(pxPerRad));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return mSpeedSlider;
    }

    private void showSpeed(int tenths) {
        mSpeedLabel.setText(String.format(java.util.Locale.US, "Mouse speed: %.1f×", tenths / 10f));
    }

    /** Pointer thread. A new speed, keeping the cursor where it is. */
    private void setSpeed(float pxPerRad) {
        if (mPxPerRad > 0f) {
            float keep = mPxPerRad / pxPerRad;  // same (angle - offset) * speed as before
            mOffA = mA - (mA - mOffA) * keep;
            mOffB = mB - (mB - mOffB) * keep;
        }
        mPxPerRad = pxPerRad;
    }

    private float inches(float in) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_IN, in, getResources().getDisplayMetrics());
    }
}
