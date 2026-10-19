package com.droid.xrdesk;

import android.app.Activity;
import android.content.Context;
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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The mouse screen: the phone becomes a mouse for the glasses. Where the phone's top edge points
 * sets the cursor, like a laser pointer; Center cursor recenters it. At the bottom, a block as tall
 * as the scroll strip holds Right click above Left click, with the scroll strip on the right
 * (drag down to scroll down, unless reversed). Above them are the mouse and scroll settings. The
 * pointer itself runs in the shell helper (MousePointer); this screen starts StabilizerService in
 * mouse mode if it isn't running, so stabilizing is optional.
 *
 * <p>Pointing: the rotation comes from Android's sensor fusion (game rotation vector: gyro +
 * accelerometer) at 100 Hz. The cursor follows the compass heading and elevation of the phone's
 * top edge relative to where it pointed when centered; sideways motion is scaled by the cosine
 * of that elevation, so a horizontal sweep is a straight line at the same speed however the phone
 * is tilted. Samples are handled on their own thread with the button and scroll events, in order.
 *
 * <p>Focus: Android has one input focus, and a click on the glasses moves it there. It stays
 * there: while the phone's display doesn't have focus, this screen doesn't take it when touched
 * (else every tap on a button would pull it back to the phone, and close the glasses'
 * keyboard). Something on the phone that takes focus, like the notification shade, brings it
 * back here until the next click; leaving this screen gives it back to the phone.
 *
 * <p>Typing: while this screen is open, the glasses' keyboard shows on the phone (Gboard, with
 * autocorrect), typing straight into the text field clicked on the glasses. Typing mode starts
 * by itself when a keyboard opens, which is usually right after a click, so the helper is asked
 * every KEYBOARD_POLL_MS for KEYBOARD_WAIT_MS after one and every KEYBOARD_WATCH_MS otherwise.
 * In typing mode the phone gives a short buzz as the keyboard opens, the controls fill the space
 * between the status and the keyboard, and the mode ends by itself once the keyboard closes.
 * The system Back only closes the keyboard; the Back button at the top leaves this screen.
 *
 * <p>Click lock: pressing a button jolts the phone, which would turn a click into a small drag.
 * So the cursor freezes while a button is held, until the phone turns past DRAG_START_RAD (a
 * deliberate drag), and for RELEASE_HOLD_MS after release; also while scrolling. The motion
 * meanwhile is absorbed into the center, so the cursor doesn't jump when it unfreezes.
 */
public class MouseActivity extends Activity implements SensorEventListener {
    private static final float CONTROLS_HEIGHT_IN = 2.5f;  // the click buttons and the scroll strip
    private static final float KEYBOARD_BUZZ_SCALE = 0.6f;  // a gentle click as the keyboard opens
    private static final float SCROLL_WIDTH_IN = 0.75f;
    private static final float SCROLL_NOTCH_IN = 0.15f;  // finger travel per wheel notch at scroll speed 1x
    private static final float DRAG_START_RAD = (float) Math.toRadians(3);
    private static final long RELEASE_HOLD_MS = 250;
    private static final long KEYBOARD_WAIT_MS = 900;     // after a click (whose release may wait 300 ms), for a keyboard
    private static final long KEYBOARD_POLL_MS = 50;      // meanwhile
    private static final long KEYBOARD_WATCH_MS = 300;    // otherwise, for one to open or close
    private static final String SIDEWAYS = "side to side";
    private static final String UP_AND_DOWN = "up and down";
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
    private float mPxPerRadX;           // cursor speeds: sideways, up and down
    private float mPxPerRadY;
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
    private int mKeyboardGoneChecks;

    private float mScrollNotchPx;       // main thread
    private Vibrator mVibrator;
    private LinearLayout mRoot;
    private Insets mBars = Insets.NONE;
    private View mSettings;             // the speed sliders and scroll switch
    private View mBlock;
    private TextView mSpeedXLabel;
    private SeekBar mSpeedX;
    private TextView mSpeedYLabel;
    private SeekBar mSpeedY;
    private TextView mScrollSpeedLabel;
    private SeekBar mScrollSpeedSlider;
    private Switch mScrollReverse;
    private volatile boolean mTyping;   // main thread writes
    private int mKeyboardTop = -1;      // main thread: the controls are fitted above a keyboard with this top edge
    private volatile int mHomeDisplay;  // this screen's display (the phone)
    private float mScrollLastY;         // main thread
    private float mScrollTicks;
    private float mScrollSpeed = 1f;    // main thread: wheel notches per SCROLL_NOTCH_IN
    private boolean mScrollInvert;      // main thread: drag down to scroll up

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
        mVibrator = getSystemService(Vibrator.class);
        mPointerThread = new HandlerThread("xrdesk-pointer", Process.THREAD_PRIORITY_DISPLAY);
        mPointerThread.start();
        mPointer = new Handler(mPointerThread.getLooper());

        LinearLayout root = new LinearLayout(this);
        mRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            mBars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            applyPadding();
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

        // Settings, applied right away: mouse speeds, sideways and up and down (1.0x = the cursor
        // goes where the phone points), scroll speed and direction. They take the room left
        // above the buttons, scrolling if it's short, and make way for a keyboard.
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        mSpeedXLabel = speedLabel();
        mSpeedX = speedSlider(mSpeedXLabel, SIDEWAYS, "pointerx");
        mSpeedYLabel = speedLabel();
        mSpeedY = speedSlider(mSpeedYLabel, UP_AND_DOWN, "pointery");
        mScrollSpeedLabel = speedLabel();
        mScrollSpeedSlider = scrollSpeedSlider(mScrollSpeedLabel);
        mScrollReverse = scrollReverseSwitch();
        settings.addView(mSpeedXLabel);
        settings.addView(mSpeedX);
        settings.addView(mSpeedYLabel);
        settings.addView(mSpeedY);
        settings.addView(mScrollSpeedLabel);
        settings.addView(mScrollSpeedSlider);
        settings.addView(mScrollReverse);
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(settings);
        mSettings = settingsScroll;
        root.addView(settingsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom: Right click above Left click, the scroll strip beside them. Above a keyboard
        // they take the settings' room too.
        LinearLayout block = new LinearLayout(this);
        mBlock = block;
        LinearLayout clicks = new LinearLayout(this);
        clicks.setOrientation(LinearLayout.VERTICAL);
        clicks.addView(clickButton("Right click", MotionEvent.BUTTON_SECONDARY), weightedHeight(0, 0, 0, 8));
        clicks.addView(clickButton("Left click", MotionEvent.BUTTON_PRIMARY), weightedHeight(0, 8, 0, 0));
        block.addView(clicks, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                Math.round(inches(SCROLL_WIDTH_IN)), LinearLayout.LayoutParams.MATCH_PARENT);
        scrollParams.leftMargin = 16;
        block.addView(scrollStrip(), scrollParams);
        root.addView(block, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, blockHeight()));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Options options = Options.load(this);
        showSpeed(mSpeedX, mSpeedXLabel, SIDEWAYS, options.pointerX / options.focal);
        showSpeed(mSpeedY, mSpeedYLabel, UP_AND_DOWN, options.pointerY / options.focal);
        mScrollSpeed = options.scrollSpeed;
        mScrollInvert = options.scrollInvert;
        mScrollSpeedSlider.setProgress(Math.round(mScrollSpeed * 10f));
        mScrollSpeedLabel.setText(scrollSpeedText(mScrollSpeedSlider.getProgress()));
        mScrollReverse.setChecked(mScrollInvert);
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
        Helper helper = mHelper;
        if (isFinishing() && helper != null) {
            helper.focusDisplay(mHomeDisplay);  // for the screen underneath, if the glasses have it
        }
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

    /**
     * Whether input focus is on the phone's display (with this screen on top) or elsewhere, as
     * after a click on the glasses. Only while it's on the phone may touching this screen take
     * it; see the class comment.
     */
    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        if (top) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
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
        mPointer.post(() -> {
            mWidth = size.x;
            mHeight = size.y;
            mPxPerRadX = options.pointerX;
            mPxPerRadY = options.pointerY;
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
            mKeyboardGoneChecks = 0;
            mPointer.postDelayed(mWatchKeyboard, KEYBOARD_WATCH_MS);
        });
        mSensors.registerListener(this, mRotation, 10_000 /* us */, mPointer);
        showStatus();
    }

    private void showStatus() {
        if (mTyping) {
            mStatus.setText("Typing on the glasses. Ends when the keyboard closes.");
        } else {
            mStatus.setText(StabilizerService.stabilizing()
                    ? "Point with the top of the phone. Click a text box to type."
                    : "Mouse only (not stabilizing). Click a text box to type.");
        }
    }

    /** Typing mode on/off (main thread); see the class comment. */
    private void setTyping(boolean on) {
        if (on == mTyping || (on && mHelper == null)) {
            return;
        }
        mTyping = on;
        Log.i(TAG, "typing mode " + (on ? "on" : "off"));
        if (!on) {
            keyboardShown(-1);
        }
        showStatus();
    }

    /**
     * Main thread. While a keyboard is on screen (top: its top edge, screen px; -1 = none), the
     * controls fill the space between the status and it. The phone buzzes as a keyboard opens.
     */
    private void keyboardShown(int top) {
        if (top == mKeyboardTop) {
            return;
        }
        boolean shown = top >= 0;
        if (shown && mKeyboardTop < 0) {
            buzzKeyboardOpened();
        }
        mKeyboardTop = top;
        mSettings.setVisibility(shown ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams block = (LinearLayout.LayoutParams) mBlock.getLayoutParams();
        block.height = shown ? 0 : blockHeight();
        block.weight = shown ? 1f : 0f;
        mBlock.setLayoutParams(block);
        applyPadding();
        showStatus();
    }

    /**
     * Main thread. Keeps the controls clear of the system bars and, while one is showing, of the
     * keyboard: it covers this screen without resizing it, as this screen doesn't type into it.
     */
    private void applyPadding() {
        int bottom = mBars.bottom;
        if (mKeyboardTop >= 0) {
            int[] at = new int[2];
            mRoot.getLocationOnScreen(at);
            int covered = at[1] + mRoot.getHeight() - mKeyboardTop;
            bottom = Math.max(bottom, Math.min(covered, mRoot.getHeight() * 2 / 3));  // a sane keyboard
        }
        mRoot.setPadding(24 + mBars.left, 16 + mBars.top, 24 + mBars.right, 24 + bottom);
    }

    /**
     * A short, gentle click, so you can feel the keyboard open without looking at the phone. As
     * hardware feedback (the phone is the glasses' mouse and keyboard), so it still plays when
     * touch feedback is turned off in the phone's settings or the phone is on silent.
     */
    private void buzzKeyboardOpened() {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }
        VibrationEffect effect = mVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                ? VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, KEYBOARD_BUZZ_SCALE)
                        .compose()
                : VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        mVibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK));
    }

    private void disconnect() {
        mSensors.unregisterListener(this);
        mPointer.post(() -> {
            Helper helper = mHelper;
            mHelper = null;
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

    /**
     * Pointer thread, while the mouse is on: a keyboard showing starts typing mode, which fits the
     * controls above it (it can change height), and ends once it has been gone for two checks in
     * a row. Asks often while a click may still open one.
     */
    private final Runnable mWatchKeyboard = new Runnable() {
        @Override
        public void run() {
            Helper helper = mHelper;
            if (helper == null) {
                return;
            }
            int top = helper.keyboardTop();
            if (top >= 0) {
                mKeyboardGoneChecks = 0;
                mMain.post(() -> {
                    setTyping(true);
                    keyboardShown(top);
                });
            } else if (mTyping) {
                boolean gone = ++mKeyboardGoneChecks >= 2;
                mMain.post(() -> {
                    if (gone) {
                        setTyping(false);
                    } else if (mTyping) {
                        keyboardShown(top);
                    }
                });
            }
            boolean waiting = top < 0 && !mTyping && SystemClock.uptimeMillis() < mKeyboardWaitUntilMs;
            mPointer.postDelayed(this, waiting ? KEYBOARD_POLL_MS : KEYBOARD_WATCH_MS);
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
        float x = mWidth / 2f + (mA - mOffA) * mPxPerRadX;
        float y = mHeight / 2f - (mB - mOffB) * mPxPerRadY;
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
        mOffA += (x - cx) / mPxPerRadX;
        mOffB -= (y - cy) / mPxPerRadY;
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
                    if (mButtonsDown == 0) {
                        mDragging = false;
                        mPressA = mA;
                        mPressB = mB;
                    }
                    mButtonsDown |= button;
                    Helper helper = mHelper;
                    if (helper != null) {
                        helper.mouseButton(button, true);
                    }
                });
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                bg.setColor(BUTTON_COLOR);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                mPointer.post(() -> {
                    mButtonsDown &= ~button;
                    Helper helper = mHelper;
                    if (helper != null) {
                        helper.mouseButton(button, false);
                    }
                    if (mButtonsDown == 0) {
                        mHoldUntilMs = SystemClock.uptimeMillis() + RELEASE_HOLD_MS;
                        if (!mTyping) {
                            // Did the click open a keyboard? Ask often for a while.
                            mKeyboardWaitUntilMs = SystemClock.uptimeMillis() + KEYBOARD_WAIT_MS;
                            mPointer.removeCallbacks(mWatchKeyboard);
                            mPointer.postDelayed(mWatchKeyboard, KEYBOARD_POLL_MS);
                        }
                    }
                });
            }
            return true;
        });
        return v;
    }

    /**
     * Drag down to scroll down, like a scroll bar (or up, if reversed); scroll speed wheel notches
     * per SCROLL_NOTCH_IN of travel. The phone ticks every SCROLL_NOTCH_IN whatever the speed,
     * like a wheel's detents.
     */
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
                    float notches = -(ev.getY() - mScrollLastY) / mScrollNotchPx;  // of travel
                    mScrollLastY = ev.getY();
                    float wheel = notches * mScrollSpeed * (mScrollInvert ? -1f : 1f);
                    mPointer.post(() -> {
                        Helper helper = mHelper;
                        if (helper != null) {
                            helper.mouseScroll(wheel);
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

    /** The click buttons and the scroll strip, when no keyboard is showing. */
    private int blockHeight() {
        return Math.round(inches(CONTROLS_HEIGHT_IN));
    }

    private TextView speedLabel() {
        TextView v = label("");
        v.setTextSize(16);
        v.setPadding(0, 32, 0, 8);
        return v;
    }

    /**
     * 0.3x to 3x in tenths; saved as one direction's pointer option, pointerx or pointery
     * (pixels per radian = speed x focal).
     */
    private SeekBar speedSlider(TextView label, String direction, String key) {
        SeekBar slider = new SeekBar(this);
        slider.setMin(3);
        slider.setMax(30);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int tenths, boolean fromUser) {
                label.setText(speedText(direction, tenths));
                if (!fromUser) {
                    return;
                }
                Context context = MouseActivity.this;
                Options.put(context, key, String.valueOf(Math.round(tenths / 10f * Options.load(context).focal)));
                Options options = Options.load(context);
                mPointer.post(() -> setSpeed(options.pointerX, options.pointerY));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return slider;
    }

    /** 0.2x to 5x in tenths, saved as the scroll option. */
    private SeekBar scrollSpeedSlider(TextView label) {
        SeekBar slider = new SeekBar(this);
        slider.setMin(2);
        slider.setMax(50);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int tenths, boolean fromUser) {
                label.setText(scrollSpeedText(tenths));
                if (fromUser) {
                    mScrollSpeed = tenths / 10f;
                    Options.put(MouseActivity.this, "scroll", String.valueOf(mScrollSpeed));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return slider;
    }

    private static String scrollSpeedText(int tenths) {
        return String.format(java.util.Locale.US, "Scroll speed: %.1f×", tenths / 10f);
    }

    /** Off: drag down to scroll down, like a scroll bar. On: like a touch screen. Saved as scrollinv. */
    private Switch scrollReverseSwitch() {
        Switch s = new Switch(this);
        s.setText("Reverse scroll direction");
        s.setTextColor(Color.WHITE);
        s.setTextSize(16);
        s.setPadding(32, 24, 32, 0);
        s.setOnCheckedChangeListener((v, checked) -> {
            if (checked != mScrollInvert) {
                mScrollInvert = checked;
                Options.put(this, "scrollinv", checked ? "1" : "0");
            }
        });
        return s;
    }

    /** Shows a saved speed (× where the phone points) on a slider and its label. */
    private void showSpeed(SeekBar slider, TextView label, String direction, float speed) {
        slider.setProgress(Math.round(speed * 10f));
        label.setText(speedText(direction, slider.getProgress()));  // setProgress only reports a change
    }

    private static String speedText(String direction, int tenths) {
        return String.format(java.util.Locale.US, "Mouse speed, %s: %.1f×", direction, tenths / 10f);
    }

    /** Pointer thread. New speeds, keeping the cursor where it is. */
    private void setSpeed(float pxPerRadX, float pxPerRadY) {
        if (mPxPerRadX > 0f && mPxPerRadY > 0f) {
            // Same (angle - offset) * speed as before.
            mOffA = mA - (mA - mOffA) * (mPxPerRadX / pxPerRadX);
            mOffB = mB - (mB - mOffB) * (mPxPerRadY / pxPerRadY);
        }
        mPxPerRadX = pxPerRadX;
        mPxPerRadY = pxPerRadY;
    }

    private float inches(float in) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_IN, in, getResources().getDisplayMetrics());
    }
}
