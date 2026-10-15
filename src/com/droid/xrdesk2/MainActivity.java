package com.droid.xrdesk2;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * The app's screen: shows the stabilizer's status and starts, pauses, recenters and stops it.
 * Starting asks for Shizuku permission the first time. Over adb:
 *   am start -n com.droid.xrdesk2/.MainActivity --ez start true [--es options "key=value ..."]
 */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private TextView mOptionsText;
    private Button mStartStop;
    private Button mPause;
    private Button mRecenter;
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
        String options = getIntent().getStringExtra("options");
        if (options != null) {
            Options.save(this, options);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
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
        mOptionsText = text("", 14);
        mOptionsText.setTextColor(Color.GRAY);
        mOptionsText.setPadding(0, 32, 0, 0);
        root.addView(title);
        root.addView(mStatus);
        root.addView(mStartStop);
        root.addView(mPause);
        root.addView(mRecenter);
        root.addView(mOptionsText);
        setContentView(root);

        Shizuku.addRequestPermissionResultListener(mPermissionListener, mMain);
        Shizuku.addBinderReceivedListenerSticky(mBinderListener, mMain);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
        if (getIntent().getBooleanExtra("start", false) && !StabilizerService.running()) {
            start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMain.post(mRefresh);
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

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        return b;
    }
}
