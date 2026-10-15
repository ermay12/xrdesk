package com.droid.xrdesk2;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * The app's screen: shows the stabilizer's status and starts, pauses, recenters and stops it,
 * with sliders for the drift time and the largest compensated rotation (applied right away).
 * Starting asks for Shizuku permission the first time. Over adb:
 *   am start -n com.droid.xrdesk2/.MainActivity --ez start true [--es options "key=value ..."]
 */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
    private static final int DRIFT_STEPS_PER_SEC = 10;  // the drift slider moves in 0.1 s steps

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private TextView mOptionsText;
    private Button mStartStop;
    private Button mPause;
    private Button mRecenter;
    private TextView mDriftLabel;
    private SeekBar mDrift;
    private TextView mMaxLabel;
    private SeekBar mMax;
    private boolean mStartWhenReady;

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = (requestCode, result) -> {
        if (requestCode != SHIZUKU_REQUEST) {
            return;
        }
        if (result == PackageManager.PERMISSION_GRANTED) {
            startStabilizer();
        } else {
            mStatus.setText("XR Desk 2 wasn't allowed to use Shizuku. Allow it in the Shizuku app.");
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
        TextView title = text("XR Desk 2", 28);
        mStatus = text("", 18);
        mStatus.setPadding(0, 24, 0, 24);
        mStartStop = button("Start", v -> {
            if (StabilizerService.running()) {
                send(StabilizerService.ACTION_STOP);
            } else {
                start();
            }
        });
        mPause = button("Pause", v -> send(StabilizerService.ACTION_PAUSE));
        mRecenter = button("Recenter", v -> send(StabilizerService.ACTION_RECENTER));
        mDriftLabel = text("", 16);
        mDriftLabel.setPadding(0, 48, 0, 8);
        mDrift = slider(2, 30, v -> {
            String seconds = String.valueOf(v / (float) DRIFT_STEPS_PER_SEC);
            Options.put(this, "drift", seconds);
            showDrift(v);
            send(StabilizerService.ACTION_RELOAD);
        });
        mMaxLabel = text("", 16);
        mMaxLabel.setPadding(0, 32, 0, 8);
        mMax = slider(2, 30, degrees -> {
            Options.put(this, "max", String.valueOf(Math.round(Math.toRadians(degrees) * Options.load(this).focal)));
            showMax(degrees);
            send(StabilizerService.ACTION_RELOAD);
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
        root.addView(mDrift);
        root.addView(mMaxLabel);
        root.addView(mMax);
        root.addView(mOptionsText);
        setContentView(root);

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

    /** Extras from adb: "options" saves settings (applied right away), "start" starts. */
    private void handleIntent(Intent intent) {
        String options = intent.getStringExtra("options");
        if (options != null) {
            Options.save(this, options);
            send(StabilizerService.ACTION_RELOAD);
        }
        if (intent.getBooleanExtra("start", false) && !StabilizerService.running()) {
            start();
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
        int drift = Math.round(o.drift * DRIFT_STEPS_PER_SEC);
        int degrees = (int) Math.round(Math.toDegrees(o.max / o.focal));
        mDrift.setProgress(drift);
        mMax.setProgress(degrees);
        showDrift(drift);
        showMax(degrees);
    }

    private void showDrift(int steps) {
        mDriftLabel.setText(String.format(java.util.Locale.US, "Drift back to center: %.1f s (higher = slower)",
                steps / (float) DRIFT_STEPS_PER_SEC));
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
            mStatus.setText("XR Desk 2 was denied access to Shizuku. Allow it in the Shizuku app.");
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
        }
    }

    private void startStabilizer() {
        startForegroundService(new Intent(this, StabilizerService.class));
        mMain.postDelayed(this::refresh, 100);
    }

    private void send(String action) {
        if (StabilizerService.running()) {
            startService(new Intent(this, StabilizerService.class).setAction(action));
        }
        mMain.postDelayed(this::refresh, 100);
    }

    private void refresh() {
        boolean running = StabilizerService.running();
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
