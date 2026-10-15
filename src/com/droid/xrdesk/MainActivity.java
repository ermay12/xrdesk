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
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * The app's screen: shows the stabilizer's status and starts, pauses, recenters and stops it,
 * with a number box for the drift time, a slider for the largest compensated rotation and a
 * choice of drift curve (all applied right away).
 * Starting asks for Shizuku permission the first time. Over adb:
 *   am start -n com.droid.xrdesk/.MainActivity --ez start true [--es options "key=value ..."]
 */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
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
    private TextView mMaxLabel;
    private SeekBar mMax;
    private RadioGroup mCurve;
    private boolean mLoading;  // showing saved settings, not a user change
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // Edge to edge: keep clear of the status and navigation bars.
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(48 + bars.left, 48 + bars.top, 48 + bars.right, 48 + bars.bottom);
            return insets;
        });
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
        Button mouse = button("Mouse", v -> startActivity(new Intent(this, MouseActivity.class)));
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
        mMaxLabel = text("", 16);
        mMaxLabel.setPadding(0, 32, 0, 8);
        mMax = slider(2, 30, degrees -> {
            Options.put(this, "max", String.valueOf(Math.round(Math.toRadians(degrees) * Options.load(this).focal)));
            showMax(degrees);
            send(StabilizerService.ACTION_RELOAD);
        });
        TextView curveLabel = text("Drift back curve", 16);
        curveLabel.setPadding(0, 32, 0, 8);
        mCurve = new RadioGroup(this);
        for (int i = 0; i < CURVE_LABELS.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(i + 1);  // RadioGroup ids must be positive: curve + 1
            b.setText(CURVE_LABELS[i]);
            b.setTextSize(16);
            b.setTextColor(Color.WHITE);
            mCurve.addView(b);
        }
        mCurve.setOnCheckedChangeListener((group, id) -> {
            if (!mLoading && id > 0) {
                Options.put(this, "curve", CURVE_NAMES[id - 1]);
                send(StabilizerService.ACTION_RELOAD);
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
        root.addView(mouse);
        root.addView(mDriftLabel);
        root.addView(mDriftInput);
        root.addView(mMaxLabel);
        root.addView(mMax);
        root.addView(curveLabel);
        root.addView(mCurve);
        root.addView(mOptionsText);
        ScrollView scroll = new ScrollView(this);  // fits short screens, e.g. the glasses
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(root);
        setContentView(scroll);

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
        int degrees = (int) Math.round(Math.toDegrees(o.max / o.focal));
        mDriftInput.setText(seconds(o.drift));
        mDriftInput.setError(null);
        mMax.setProgress(degrees);
        showMax(degrees);

        mLoading = true;
        mCurve.check(o.curve + 1);
        mLoading = false;
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

    private void showMax(int degrees) {
        mMaxLabel.setText("Max rotation: " + degrees + "° of head movement");
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

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        return b;
    }
}
