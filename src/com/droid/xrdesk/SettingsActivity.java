package com.droid.xrdesk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.droid.xrdesk.motion.Stabilizer;

import java.math.BigDecimal;

/**
 * Stabilization settings, opened from the main screen: how long the picture takes to drift back
 * to center (a number box: any value, so a large one anchors the picture) and along which curve,
 * and the largest rotation compensated side to side and up and down (all applied right away; the
 * drift time once typed). At the bottom, the saved options string, which adb can set too (see
 * Options).
 */
public class SettingsActivity extends Activity {
    // Drift curves, indexed by the Stabilizer constant: saved names, short names and labels.
    private static final String[] CURVE_NAMES = {"smooth", "exp", "linear", "accel"};
    private static final String[] CURVE_SHORT = {"Smooth", "Exponential", "Linear", "Speed up"};
    private static final String[] CURVE_LABELS = {
            "Smooth: eases in, speeds up, eases into center",
            "Exponential: fastest at first, then creeps",
            "Linear: constant speed, stops at center",
            "Speed up: accelerates, then stops suddenly (default)",
    };
    // The order they're listed in: the default first.
    private static final int[] CURVE_ORDER = {
            Stabilizer.ACCELERATE, Stabilizer.SMOOTH, Stabilizer.EXPONENTIAL, Stabilizer.LINEAR};

    private EditText mDriftInput;
    private RadioGroup mCurves;
    private final int[] mCurveIds = new int[CURVE_NAMES.length];  // the radio buttons' ids, by curve
    private TextView mYawLabel;
    private SeekBar mYaw;
    private TextView mPitchLabel;
    private SeekBar mPitch;
    private TextView mOptionsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.BLACK);
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(24 + bars.left, 16 + bars.top, 24 + bars.right, bars.bottom);
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.menuButton(this, "Back", v -> finish()), Ui.weightedWidth());
        TextView title = Ui.text(this, "Stabilization settings", 20);
        title.setPadding(24, 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        screen.addView(bar);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 0, 24, 48);
        label(form, 32).setText("Drift back to center, in seconds");
        mDriftInput = new EditText(this);
        mDriftInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        mDriftInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        mDriftInput.setSingleLine(true);
        mDriftInput.setTextSize(18);
        mDriftInput.setTextColor(Color.WHITE);
        // Applied when editing ends (Done, tapping elsewhere, or leaving), never half-typed.
        mDriftInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyDrift();
            }
        });
        mDriftInput.setOnEditorActionListener((v, actionId, event) -> {
            // Done on the on-screen keyboard, or Enter on a physical one.
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_DONE && !enter) {
                return false;
            }
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            form.requestFocus();  // takes focus off the box, which applies it
            return true;
        });
        // Lets the box give up focus, and keeps the keyboard from opening when the screen does.
        form.setFocusableInTouchMode(true);
        form.addView(mDriftInput);
        helper(form, "How long the picture takes to come back after you move your head; higher is slower. "
                + "A large number, like 400, anchors it: it stays put until you recenter. Default 1.");
        label(form, 32).setText("Drift back curve");
        mCurves = new RadioGroup(this);
        for (int curve : CURVE_ORDER) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(CURVE_LABELS[curve]);
            b.setTextSize(16);
            b.setTextColor(Color.WHITE);
            b.setMinHeight(Math.round(Ui.inches(this, 0.3f)));
            mCurveIds[curve] = b.getId();
            mCurves.addView(b);
        }
        mCurves.setOnCheckedChangeListener((group, id) -> {
            // Also called when show() checks the saved curve: only a change is saved.
            for (int curve = 0; curve < mCurveIds.length; curve++) {
                if (mCurveIds[curve] == id && curve != Options.load(this).curve) {
                    save("curve", CURVE_NAMES[curve]);
                }
            }
        });
        form.addView(mCurves);
        mYawLabel = label(form, 64);
        mYaw = Ui.slider(this, 2, 30, yaw -> saveMax("maxyaw", yaw));
        form.addView(mYaw);
        mPitchLabel = label(form, 32);
        mPitch = Ui.slider(this, 2, 30, pitch -> saveMax("maxpitch", pitch));
        form.addView(mPitch);
        helper(form, "How far it compensates in each direction before following you. Default 15°.");
        mOptionsText = Ui.text(this, "", 12);
        mOptionsText.setTextColor(Color.GRAY);
        mOptionsText.setTextIsSelectable(true);
        mOptionsText.setPadding(0, 64, 0, 0);
        form.addView(mOptionsText);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDriftInput.hasFocus()) {
            applyDrift();  // left while typing
        }
    }

    /** Shows the saved settings (they may have been changed over adb), except a drift time being typed. */
    private void show() {
        Options o = Options.load(this);
        if (!mDriftInput.hasFocus()) {
            mDriftInput.setText(seconds(o.drift));
            mDriftInput.setError(null);
        }
        mCurves.check(mCurveIds[o.curve]);
        int yaw = degrees(o, o.maxYaw);
        int pitch = degrees(o, o.maxPitch);
        mYawLabel.setText("Max side-to-side rotation (yaw): " + yaw + "°");
        mYaw.setProgress(yaw);
        mPitchLabel.setText("Max up-and-down rotation (pitch): " + pitch + "°");
        mPitch.setProgress(pitch);
        mOptionsText.setText("Saved options: " + (o.text.isEmpty() ? "defaults" : o.text));
    }

    private void save(String key, String value) {
        Options.put(this, key, value);
        StabilizerService.reload(this);
        show();
    }

    /** Saves the typed drift time if it's a valid number of seconds. */
    private void applyDrift() {
        float value;
        try {
            value = Float.parseFloat(mDriftInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            value = Float.NaN;
        }
        if (!(value > 0f) || Float.isInfinite(value)) {
            mDriftInput.setError("Enter a number of seconds above 0");
            return;
        }
        mDriftInput.setError(null);
        if (value != Options.load(this).drift) {
            save("drift", seconds(value));
        }
    }

    /** Saves a max rotation slider's value as the picture offset in px. */
    private void saveMax(String key, int degrees) {
        save(key, String.valueOf(Math.round(Math.toRadians(degrees) * Options.load(this).focal)));
    }

    /** The settings in one line for the main screen, as "Drift back 1 s · Speed up · Max 15°". */
    static String describe(Options o) {
        int yaw = degrees(o, o.maxYaw);
        int pitch = degrees(o, o.maxPitch);
        return "Drift back " + seconds(o.drift) + " s · " + CURVE_SHORT[o.curve] + " · Max " + yaw + "°"
                + (pitch != yaw ? " / " + pitch + "°" : "");
    }

    /** A max rotation option (picture offset in px) as degrees of head rotation. */
    private static int degrees(Options o, float maxPx) {
        return (int) Math.round(Math.toDegrees(maxPx / o.focal));
    }

    /** 1, 0.3 or 1000000: no ".0" and no exponent. */
    private static String seconds(float value) {
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    private TextView label(LinearLayout form, int top) {
        TextView t = Ui.text(this, "", 16);
        t.setPadding(0, top, 0, 8);
        form.addView(t);
        return t;
    }

    private void helper(LinearLayout form, String text) {
        TextView t = Ui.text(this, text, 14);
        t.setTextColor(Ui.SECONDARY_TEXT);
        t.setPadding(0, 8, 0, 0);
        form.addView(t);
    }
}
