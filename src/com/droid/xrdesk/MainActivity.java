package com.droid.xrdesk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * The app's screen: shows the stabilizer's status and starts, pauses, recenters and stops it,
 * with a number box for the drift time, sliders for the largest compensated rotation sideways and
 * up and down, and a drop-down of drift curves (all applied right away). The Mouse button is
 * pinned to the bottom and large, to find with a thumb while wearing the glasses.
 * Starting asks for Shizuku permission the first time. Over adb:
 *   am start -n com.droid.xrdesk/.MainActivity --ez start true [--es options "key=value ..."]
 */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
    private static final float MOUSE_BUTTON_HEIGHT_IN = 1f;
    // Drift curves, indexed by the Stabilizer constant: saved names and labels.
    private static final String[] CURVE_NAMES = {"smooth", "exp", "linear", "accel"};
    private static final String[] CURVE_LABELS = {
            "Smooth: eases in, speeds up, eases into center",
            "Exponential: fastest at first, then creeps",
            "Linear: constant speed, stops at center",
            "Speed up: accelerates, then stops suddenly",
    };

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private TextView mOptionsText;
    private Button mStartStop;
    private Button mPause;
    private Button mRecenter;
    private TextView mDriftLabel;
    private EditText mDriftInput;
    private TextView mYawLabel;
    private SeekBar mYaw;
    private TextView mPitchLabel;
    private SeekBar mPitch;
    private Spinner mCurve;
    private boolean mStartWhenReady;

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = (requestCode, result) -> {
        if (requestCode != SHIZUKU_REQUEST) {
            return;
        }
        if (result == PackageManager.PERMISSION_GRANTED) {
            startStabilizer();
        } else {
            mStatus.setText("XR Desk wasn't allowed to use Shizuku. Allow it in the Shizuku app.");
        }
    };

    private final Shizuku.OnBinderReceivedListener mBinderListener = () -> {
        if (mStartWhenReady) {
            mStartWhenReady = false;
            start();
        }
    };

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            mMain.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The settings scroll; the Mouse button stays at the bottom below them.
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.BLACK);
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            // Edge to edge: keep clear of the status and navigation bars.
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 32);
        root.setBackgroundColor(Color.BLACK);
        TextView title = text("XR Desk", 28);
        mStatus = text("", 18);
        mStatus.setPadding(0, 24, 0, 24);
        mStartStop = button("Start", v -> {
            if (StabilizerService.stabilizing()) {
                send(StabilizerService.ACTION_STOP);
            } else {
                start();
            }
        });
        mPause = button("Pause", v -> send(StabilizerService.ACTION_PAUSE));
        mRecenter = button("Recenter", v -> send(StabilizerService.ACTION_RECENTER));
        Button mouse = button("Mouse", v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            startActivity(new Intent(this, MouseActivity.class));
        });
        mouse.setTextSize(24);
        mDriftLabel = text("Drift back to center, in seconds (higher = slower)", 16);
        mDriftLabel.setPadding(0, 48, 0, 8);
        mDriftInput = new EditText(this);
        mDriftInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        mDriftInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        mDriftInput.setSingleLine(true);
        mDriftInput.setTextSize(18);
        mDriftInput.setTextColor(Color.WHITE);
        // Applied when editing ends (Done, or tapping elsewhere), never half-typed.
        mDriftInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyDrift();
            }
        });
        mDriftInput.setOnEditorActionListener((v, actionId, event) -> {
            // Done on the on-screen keyboard, or Enter on a physical one.
            boolean enter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_DONE && !enter) {
                return false;
            }
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            root.requestFocus();  // takes focus off the box, which applies it
            return true;
        });
        // Lets the box give up focus, and keeps the keyboard from opening when the screen does.
        root.setFocusableInTouchMode(true);
        mYawLabel = text("", 16);
        mYawLabel.setPadding(0, 32, 0, 8);
        mYaw = slider(2, 30, yaw -> {
            saveMax("maxyaw", yaw);
            showMax(yaw, degrees(Options.load(this).maxPitch));
        });
        mPitchLabel = text("", 16);
        mPitchLabel.setPadding(0, 32, 0, 8);
        mPitch = slider(2, 30, pitch -> {
            saveMax("maxpitch", pitch);
            showMax(degrees(Options.load(this).maxYaw), pitch);
        });
        TextView curveLabel = text("Drift back curve", 16);
        curveLabel.setPadding(0, 32, 0, 8);
        mCurve = new Spinner(this, Spinner.MODE_DROPDOWN);  // the list is as wide as its longest line
        mCurve.setAdapter(new ArrayAdapter<String>(this, 0, CURVE_LABELS) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return curveRow(position, convertView, 0);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return curveRow(position, convertView, 40);
            }
        });
        mCurve.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Also called after loadSliders shows the saved curve: only a change is saved.
                if (position != Options.load(MainActivity.this).curve) {
                    Options.put(MainActivity.this, "curve", CURVE_NAMES[position]);
                    send(StabilizerService.ACTION_RELOAD);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mOptionsText = text("", 14);
        mOptionsText.setTextColor(Color.GRAY);
        mOptionsText.setPadding(0, 32, 0, 0);
        root.addView(title);
        root.addView(mStatus);
        root.addView(mStartStop);
        root.addView(mPause);
        root.addView(mRecenter);
        root.addView(mDriftLabel);
        root.addView(mDriftInput);
        root.addView(mYawLabel);
        root.addView(mYaw);
        root.addView(mPitchLabel);
        root.addView(mPitch);
        root.addView(curveLabel);
        root.addView(mCurve);
        root.addView(mOptionsText);
        ScrollView scroll = new ScrollView(this);  // fits short screens, e.g. the glasses
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams mouseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(inches(MOUSE_BUTTON_HEIGHT_IN)));
        mouseParams.setMargins(48, 0, 48, 48);
        screen.addView(mouse, mouseParams);
        setContentView(screen);

        Shizuku.addRequestPermissionResultListener(mPermissionListener, mMain);
        Shizuku.addBinderReceivedListenerSticky(mBinderListener, mMain);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
        handleIntent(getIntent());
    }

    /** singleTask: a later `am start` arrives here instead of onCreate. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        loadSliders();
    }

    /** Extras from adb: "options" saves settings (applied right away), "start" starts, "mouse" opens the mouse screen. */
    private void handleIntent(Intent intent) {
        String options = intent.getStringExtra("options");
        if (options != null) {
            Options.save(this, options);
            send(StabilizerService.ACTION_RELOAD);
        }
        if (intent.getBooleanExtra("start", false) && !StabilizerService.stabilizing()) {
            start();
        }
        if (intent.getBooleanExtra("mouse", false)) {
            startActivity(new Intent(this, MouseActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSliders();
        mMain.post(mRefresh);
    }

    /** Shows the saved settings on the sliders (they may have been changed over adb). */
    private void loadSliders() {
        Options o = Options.load(this);
        int yaw = degrees(o.maxYaw);
        int pitch = degrees(o.maxPitch);
        mDriftInput.setText(seconds(o.drift));
        mDriftInput.setError(null);
        mYaw.setProgress(yaw);
        mPitch.setProgress(pitch);
        showMax(yaw, pitch);
        mCurve.setSelection(o.curve);
    }

    /** A max rotation option (picture offset in px) as degrees of head rotation. */
    private int degrees(float maxPx) {
        return (int) Math.round(Math.toDegrees(maxPx / Options.load(this).focal));
    }

    /** Saves a max rotation slider's value as the picture offset in px. */
    private void saveMax(String key, int degrees) {
        Options.put(this, key, String.valueOf(Math.round(Math.toRadians(degrees) * Options.load(this).focal)));
        send(StabilizerService.ACTION_RELOAD);
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
            Options.put(this, "drift", seconds(value));
            send(StabilizerService.ACTION_RELOAD);
        }
    }

    /** 3 rather than 3.0. */
    private static String seconds(float value) {
        return value == Math.rint(value) && value < 1e9f ? String.valueOf((long) value) : String.valueOf(value);
    }

    private void showMax(int yaw, int pitch) {
        mYawLabel.setText("Max side-to-side rotation (yaw): " + yaw + "°");
        mPitchLabel.setText("Max up-and-down rotation (pitch): " + pitch + "°");
    }

    /** A drift curve in the drop-down: closed (no padding) or in the open list. */
    private TextView curveRow(int position, View convertView, int padding) {
        TextView t = convertView instanceof TextView ? (TextView) convertView : text("", 16);
        t.setText(CURVE_LABELS[position]);
        t.setPadding(padding, padding, padding, padding);
        return t;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMain.removeCallbacks(mRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        Shizuku.removeBinderReceivedListener(mBinderListener);
    }

    /** Checks Shizuku and its permission, then starts the service. */
    private void start() {
        if (!Shizuku.pingBinder()) {
            // Shizuku hands its binder to this process shortly after start, if it's running.
            mStartWhenReady = true;
            mStatus.setText("Waiting for Shizuku… (is it running?)");
            mMain.postDelayed(() -> {
                if (mStartWhenReady && !Shizuku.pingBinder()) {
                    mStartWhenReady = false;
                    mStatus.setText("Shizuku isn't running. Start it in the Shizuku app, then press Start.");
                }
            }, 3000);
            return;
        }
        if (Shizuku.isPreV11()) {
            mStatus.setText("This Shizuku version is too old. Update Shizuku.");
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startStabilizer();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            mStatus.setText("XR Desk was denied access to Shizuku. Allow it in the Shizuku app.");
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
        }
    }

    private void startStabilizer() {
        startForegroundService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_START));
        mMain.postDelayed(this::refresh, 100);
    }

    private void send(String action) {
        if (StabilizerService.running()) {
            startService(new Intent(this, StabilizerService.class).setAction(action));
        }
        mMain.postDelayed(this::refresh, 100);
    }

    private void refresh() {
        boolean running = StabilizerService.stabilizing();
        if (!mStartWhenReady) {
            mStatus.setText(StabilizerService.status());
        }
        mStartStop.setText(running ? "Stop" : "Start");
        mPause.setText(StabilizerService.paused() ? "Resume" : "Pause");
        mPause.setEnabled(running);
        mRecenter.setEnabled(running);
        String options = Options.load(this).text;
        mOptionsText.setText(options.isEmpty() ? "Options: defaults" : "Options: " + options);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        return t;
    }

    /** A slider from min to max; onChange gets the value when the user moves it. */
    private SeekBar slider(int min, int max, java.util.function.IntConsumer onChange) {
        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return s;
    }

    private float inches(float in) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_IN, in, getResources().getDisplayMetrics());
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        return b;
    }
}
