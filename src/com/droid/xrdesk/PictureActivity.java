package com.droid.xrdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The picture's size and position, adjusted while watching the glasses, where the change shows
 * right away. Drag on Move to move the picture and up and down on Size to resize it, like on a
 * trackpad: only the finger's travel counts, not where it lands, 1% per STEP_IN. A Move drag
 * locks to the direction it starts in, so a side-to-side drag by feel doesn't creep up or down.
 * Size and position are independent: the picture is MIN_FILL to MAX_FILL % (above 100 zooms in,
 * cutting off its edges) and moves up to MAX_OFFSET each way at any size, partly off the display
 * if wanted (see Options). The phone ticks every 5%, clicks at the center and at 100%, and thuds
 * at the limits, so it can be done without looking. A preview shows the result. Saved as the fill, offsetx and offsety options; the main screen sums it up.
 * While this screen is open the service runs (with the helper attached), so the first drag shows
 * on the glasses right away.
 *
 * <p>The system Back does nothing, as on the mouse screen: the touch areas reach the screen's
 * edges and are used by feel. The Back button at the top leaves this screen.
 */
public class PictureActivity extends Activity {
    private static final float STEP_IN = 0.04f;            // finger travel per 1%
    private static final float CONTROLS_HEIGHT_IN = 2.5f;  // Move and Size, as tall as the mouse screen's controls
    private static final float SIZE_WIDTH_IN = 0.75f;
    private static final long REFRESH_MS = 500;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Button mCenter;
    private Button mFullSize;
    private TextView mReadout;
    private Preview mPreview;
    private TextView mHint;
    private TextView mMove;
    private float mStepPx;
    private int mSlop;
    private int mSize;  // %, Options.MIN_FILL to MAX_FILL
    private int mX;     // % of the display's width from center, + = right
    private int mY;     // % of its height, + = down

    /** The hint follows the service (starting, or why the picture isn't on the glasses). */
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            showHint();
            mPreview.setAspect(displayAspect());
            mMain.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);  // while watching the glasses
        mStepPx = Ui.inches(this, STEP_IN);
        mSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(24 + bars.left, 16 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });

        // Top: Back and the two resets, one for each.
        LinearLayout bar = new LinearLayout(this);
        bar.addView(Ui.menuButton(this, "Back", v -> finish()), Ui.weightedWidth());
        mCenter = Ui.menuButton(this, "Center picture", v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            mX = mY = 0;
            apply();
        });
        bar.addView(mCenter, Ui.weightedWidth());
        mFullSize = Ui.menuButton(this, "Full size", v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            mSize = 100;
            apply();
        });
        bar.addView(mFullSize, Ui.weightedWidth());
        root.addView(bar);

        mReadout = Ui.label(this, "");
        mReadout.setPadding(0, 32, 0, 0);
        root.addView(mReadout);
        mPreview = new Preview(this);
        root.addView(mPreview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mHint = Ui.text(this, "", 14);
        mHint.setTextColor(Color.LTGRAY);
        mHint.setGravity(Gravity.CENTER);
        mHint.setPadding(0, 16, 0, 16);
        root.addView(mHint);

        // Bottom: Move, with Size beside it.
        LinearLayout block = new LinearLayout(this);
        mMove = dragArea("▲\n◀     Move     ▶\n▼", true);
        mMove.setContentDescription("Move the picture: drag");
        // Near the left edge, a drag to the right would be the system's Back gesture.
        mMove.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t))));
        block.addView(mMove, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        TextView size = dragArea("+\n\n\nSize\n\n\n−", false);
        size.setContentDescription("Picture size: drag up for bigger, down for smaller");
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                Math.round(Ui.inches(this, SIZE_WIDTH_IN)), LinearLayout.LayoutParams.MATCH_PARENT);
        sizeParams.leftMargin = 16;
        block.addView(size, sizeParams);
        root.addView(block, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(Ui.inches(this, CONTROLS_HEIGHT_IN))));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Options o = Options.load(this);  // may have been set over adb
        mSize = Math.round(o.fill * 100f);
        mX = Math.round(o.offsetX);
        mY = Math.round(o.offsetY);
        show();
        startForegroundService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_PICTURE_ON));
        mMain.post(mRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMain.removeCallbacks(mRefresh);
        if (StabilizerService.running()) {
            startService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_PICTURE_OFF));
        }
    }

    /** See the class comment. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
    }

    // ---- Changes ----

    /** Moves the picture along one axis; false if the limit cut it short. */
    private boolean move(boolean horizontal, int steps) {
        int limit = Options.MAX_OFFSET;
        int from = horizontal ? mX : mY;
        int to = clamp(from + steps, -limit, limit);
        if (to != from) {
            if (horizontal) {
                mX = to;
            } else {
                mY = to;
            }
            feel(from, to, 0, -limit, limit);
            apply();
        }
        return to == from + steps;
    }

    /** Resizes the picture about its center; false if a limit cut it short. */
    private boolean resize(int steps) {
        int from = mSize;
        int to = clamp(from + steps, Options.MIN_FILL, Options.MAX_FILL);
        if (to != from) {
            mSize = to;
            feel(from, to, 100, Options.MIN_FILL, Options.MAX_FILL);
            apply();
        }
        return to == from + steps;
    }

    /** Saves and applies the size and position (live on the glasses), and shows them. */
    private void apply() {
        Options.put(this, "fill", String.valueOf(mSize / 100f));
        Options.put(this, "offsetx", String.valueOf(mX));
        Options.put(this, "offsety", String.valueOf(mY));
        StabilizerService.reloadPicture(this);
        show();
    }

    /**
     * Haptics for a change, so it can be made by feel, each different: a click on reaching home
     * (centered, or 100%), a thud on reaching a limit, else a light tick on reaching a
     * multiple of 5.
     */
    private void feel(int from, int to, int home, int min, int max) {
        if (reached(from, to, home)) {
            Ui.buzz(this, VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, VibrationEffect.EFFECT_CLICK);
        } else if (to == min || to == max) {
            Ui.buzz(this, VibrationEffect.Composition.PRIMITIVE_THUD, 1f, VibrationEffect.EFFECT_HEAVY_CLICK);
        } else if (from < to ? Math.floorDiv(to, 5) > Math.floorDiv(from, 5) : Math.floorDiv(from - 1, 5) * 5 >= to) {
            Ui.buzz(this, VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, VibrationEffect.EFFECT_TICK);
        }
    }

    /** Whether going from one value to another got to a value (not counting where it started). */
    private static boolean reached(int from, int to, int value) {
        return from < to ? from < value && value <= to : to <= value && value < from;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- Showing ----

    /** Shows the saved size and position: readout, preview, which resets apply, and the hint. */
    private void show() {
        Options o = Options.load(this);
        mReadout.setText(describe(o));
        mPreview.setPicture(o.fill, o.offsetX, o.offsetY);
        mCenter.setEnabled(o.offsetX != 0f || o.offsetY != 0f);
        mFullSize.setEnabled(o.fill != 1f);
        showHint();
    }

    private void showHint() {
        mHint.setText(StabilizerService.helper() == null
                ? StabilizerService.status()  // starting, or why it can't show on the glasses
                : "Drag on Move or Size and watch the glasses.");
    }

    /** As "Size 80% · 5% right, 3% up", or "Full size, centered"; also for the main screen. */
    static String describe(Options o) {
        int size = Math.round(o.fill * 100f);
        int x = Math.round(o.offsetX);
        int y = Math.round(o.offsetY);
        if (size == 100 && x == 0 && y == 0) {
            return "Full size, centered";
        }
        StringBuilder sb = new StringBuilder("Size ").append(size).append("% · ");
        if (x == 0 && y == 0) {
            sb.append("centered");
        }
        if (x != 0) {
            sb.append(Math.abs(x)).append(x < 0 ? "% left" : "% right");
        }
        if (y != 0) {
            sb.append(x != 0 ? ", " : "").append(Math.abs(y)).append(y < 0 ? "% up" : "% down");
        }
        return sb.toString();
    }

    /** The glasses' display's width / height, or 16:9 if they aren't attached. */
    private float displayAspect() {
        Display display = getSystemService(DisplayManager.class).getDisplay(StabilizerService.displayId());
        if (display == null) {
            return 16f / 9f;
        }
        Point size = new Point();
        display.getRealSize(size);
        return size.y > 0 ? (float) size.x / size.y : 16f / 9f;
    }

    // ---- Touch ----

    /** Move (true) or Size: a touch area that turns drags into steps. */
    private TextView dragArea(String text, boolean move) {
        GradientDrawable bg = Ui.rounded(Ui.BUTTON_COLOR);
        TextView v = Ui.label(this, text);
        v.setBackground(bg);
        v.setOnTouchListener(new Drag(bg, move));
        return v;
    }

    /**
     * A drag on Move or Size, following the finger that started it: nothing until it passes the
     * touch slop, then a step per mStepPx of travel, with the remainder carried over. Travel past
     * a limit is dropped, so turning back moves the picture back right away.
     */
    private final class Drag implements View.OnTouchListener {
        private final GradientDrawable mBg;
        private final boolean mMove;
        private boolean mActive;     // a drag is on
        private int mPointerId;
        private float mDownX;
        private float mDownY;
        private float mLastX;
        private float mLastY;
        private float mCarry;        // travel short of the next step, in steps
        private boolean mDragging;   // past the slop
        private boolean mHorizontal; // the axis a Move drag locked to (Size: vertical)

        Drag(GradientDrawable bg, boolean move) {
            mBg = bg;
            mMove = move;
        }

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mActive = true;
                    mBg.setColor(Ui.PRESSED_COLOR);
                    mPointerId = ev.getPointerId(0);
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mDragging = false;
                    mCarry = 0f;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    int i = ev.findPointerIndex(mPointerId);
                    if (!mActive || i < 0) {
                        break;
                    }
                    float x = ev.getX(i);
                    float y = ev.getY(i);
                    if (!mDragging) {
                        float dx = x - mDownX;
                        float dy = y - mDownY;
                        if (Math.hypot(dx, dy) <= mSlop) {
                            break;
                        }
                        mDragging = true;
                        mHorizontal = mMove && Math.abs(dx) > Math.abs(dy);
                        mLastX = x;
                        mLastY = y;
                        break;
                    }
                    float travel = mHorizontal ? x - mLastX : y - mLastY;
                    mLastX = x;
                    mLastY = y;
                    mCarry += travel / mStepPx;
                    int steps = (int) mCarry;
                    if (steps != 0) {
                        mCarry -= steps;
                        // Size: up is bigger. Move: the picture follows the finger.
                        boolean whole = mMove ? move(mHorizontal, steps) : resize(-steps);
                        if (!whole) {
                            mCarry = 0f;
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (ev.getPointerId(ev.getActionIndex()) == mPointerId) {
                        end();  // the drag's finger lifted; another one doesn't take over
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    end();
                    break;
                default:
                    break;
            }
            return true;
        }

        private void end() {
            mActive = false;
            mBg.setColor(Ui.BUTTON_COLOR);
        }
    }

    /**
     * The glasses' display (gray outline, center lines) with the picture on it (white outline),
     * zoomed out as needed to show the whole picture: the part off the display is dimmer.
     */
    private static final class Preview extends View {
        private static final float INSET = 16f;
        private static final int OFF_FILL = 0xFF161616;
        private static final int OFF_OUTLINE = 0xFF666666;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mMatrix = new Matrix();
        private final RectF mDisplay = new RectF();
        private final RectF mPicture = new RectF();
        private final RectF mBounds = new RectF();
        private float mAspect = 16f / 9f;
        private float mFill = 1f;
        private float mX;  // %, as the options
        private float mY;

        Preview(Context context) {
            super(context);
        }

        void setAspect(float aspect) {
            if (aspect != mAspect) {
                mAspect = aspect;
                invalidate();
            }
        }

        void setPicture(float fill, float offsetX, float offsetY) {
            mFill = fill;
            mX = offsetX;
            mY = offsetY;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth() - 2 * INSET;
            float h = getHeight() - 2 * INSET;
            if (w <= 0f || h <= 0f) {
                return;
            }
            // In display widths from the display's center, then fitted so both show.
            float dh = 1f / mAspect;
            mDisplay.set(-0.5f, -dh / 2f, 0.5f, dh / 2f);
            float cx = mX / 100f;
            float cy = dh * mY / 100f;
            mPicture.set(cx - mFill / 2f, cy - dh * mFill / 2f, cx + mFill / 2f, cy + dh * mFill / 2f);
            mBounds.set(mDisplay);
            mBounds.union(mPicture);
            float scale = Math.min(w / mBounds.width(), h / mBounds.height());
            mMatrix.setTranslate(-mBounds.centerX(), -mBounds.centerY());
            mMatrix.postScale(scale, scale);
            mMatrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
            mMatrix.mapRect(mDisplay);
            mMatrix.mapRect(mPicture);

            draw(canvas, mPicture, Paint.Style.FILL, OFF_FILL);
            draw(canvas, mPicture, Paint.Style.STROKE, OFF_OUTLINE);
            canvas.save();
            canvas.clipRect(mDisplay);
            draw(canvas, mPicture, Paint.Style.FILL, Ui.BUTTON_COLOR);
            mPaint.setStrokeWidth(2f);
            mPaint.setColor(0xFF333333);
            canvas.drawLine(mDisplay.centerX(), mDisplay.top, mDisplay.centerX(), mDisplay.bottom, mPaint);
            canvas.drawLine(mDisplay.left, mDisplay.centerY(), mDisplay.right, mDisplay.centerY(), mPaint);
            draw(canvas, mPicture, Paint.Style.STROKE, Color.WHITE);
            canvas.restore();
            draw(canvas, mDisplay, Paint.Style.STROKE, Ui.PRESSED_COLOR);
        }

        private void draw(Canvas canvas, RectF r, Paint.Style style, int color) {
            mPaint.setStyle(style);
            mPaint.setStrokeWidth(4f);
            mPaint.setColor(color);
            canvas.drawRect(r, mPaint);
        }
    }
}
