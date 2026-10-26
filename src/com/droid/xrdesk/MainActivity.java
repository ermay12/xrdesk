package com.droid.xrdesk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * The app's screen: the stabilizer's status, then two rows that open the settings changed now and
 * then, each summing them up: the picture's size and position (PictureActivity) and
 * stabilization (SettingsActivity). The frequent actions are at the bottom, within a thumb's reach
 * while wearing the glasses and always in the same place: Switch to 3D/2D, Start/Stop
 * Stabilization, Recenter and the large Mouse button. Opening the app starts XR Desk (the
 * service, which then runs until the glasses are unplugged) without stabilizing; starting asks
 * for Shizuku permission the first time. Over adb, starting stabilizing too:
 *   am start -n com.droid.xrdesk/.MainActivity --ez start true [--es options "key=value ..."]
 */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
    private static final float BUTTON_HEIGHT_IN = 0.5f;  // Start/Stop and Recenter
    private static final float MOUSE_BUTTON_HEIGHT_IN = 1f;
    private static final float ROW_HEIGHT_IN = 0.55f;    // the settings rows, at least
    private static final long SWITCH_ASKED_MS = 1000;    // Switch to 3D/2D shows "Switching…" from the tap until the service says so

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private TextView mPictureSummary;
    private TextView mStabilizationSummary;
    private Button m3d;
    private Button mStartStop;
    private Button mRecenter;
    private boolean mStartWhenReady;
    private String mAction = StabilizerService.ACTION_RUN;  // what start() asks of the service once Shizuku is ready
    private boolean mStartOnOpen;       // start XR Desk once the notification permission is settled
    private boolean mStabilizeOnOpen;   // and stabilize (adb's "start")
    private long mSwitchAskedMs;

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = (requestCode, result) -> {
        if (requestCode != SHIZUKU_REQUEST) {
            return;
        }
        if (result == PackageManager.PERMISSION_GRANTED) {
            startWith(mAction);
        } else {
            mStatus.setText("XR Desk wasn't allowed to use Shizuku. Allow it in the Shizuku app.");
        }
    };

    private final Shizuku.OnBinderReceivedListener mBinderListener = () -> {
        if (mStartWhenReady) {
            mStartWhenReady = false;
            start(mAction);
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
        // The status and settings rows scroll if they must; the actions stay at the bottom.
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
        root.addView(Ui.text(this, "XR Desk", 28));
        mStatus = Ui.text(this, "", 18);
        mStatus.setPadding(0, 24, 0, 48);
        root.addView(mStatus);
        mPictureSummary = settingsRow(root, "Picture size and position", PictureActivity.class, 0);
        mStabilizationSummary = settingsRow(root, "Stabilization settings", SettingsActivity.class, 24);
        ScrollView scroll = new ScrollView(this);  // fits short screens, e.g. the glasses
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        m3d = button("Switch to 3D", 18, v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            boolean on = !StabilizerService.in3d();
            mSwitchAskedMs = SystemClock.uptimeMillis();
            show3d();
            start(on ? StabilizerService.ACTION_3D_ON : StabilizerService.ACTION_3D_OFF);
        });
        mStartStop = button("Start Stabilization", 18, v -> {
            if (StabilizerService.stabilizing()) {
                send(StabilizerService.ACTION_STOP);
            } else {
                start(StabilizerService.ACTION_START);
            }
        });
        mRecenter = button("Recenter", 18, v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            send(StabilizerService.ACTION_RECENTER);
        });
        Button mouse = button("Mouse", 24, v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            startActivity(new Intent(this, MouseActivity.class));
        });
        // Recenter is next to Mouse, as hitting it by mistake does no harm; Stop is one further, and
        // Switch to 3D, which blanks the glasses for a few seconds, furthest.
        screen.addView(m3d, actionParams(BUTTON_HEIGHT_IN, 24));
        screen.addView(mStartStop, actionParams(BUTTON_HEIGHT_IN, 24));
        screen.addView(mRecenter, actionParams(BUTTON_HEIGHT_IN, 24));
        screen.addView(mouse, actionParams(MOUSE_BUTTON_HEIGHT_IN, 48));
        setContentView(screen);

        Shizuku.addRequestPermissionResultListener(mPermissionListener, mMain);
        Shizuku.addBinderReceivedListenerSticky(mBinderListener, mMain);
        mStartOnOpen = savedInstanceState == null;  // not when recreated, e.g. after a rotation
        handleIntent(getIntent());
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Starting waits for the answer, so Shizuku's permission prompt doesn't cover this one.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else {
            startOnOpen();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == NOTIFICATION_REQUEST) {
            startOnOpen();  // XR Desk works without notifications; they just don't show
        }
    }

    /** Starts XR Desk if the app just opened and it isn't running, and stabilizing if adb asked. */
    private void startOnOpen() {
        if (!mStartOnOpen) {
            return;
        }
        mStartOnOpen = false;
        if (mStabilizeOnOpen) {
            mStabilizeOnOpen = false;
            if (!StabilizerService.stabilizing()) {
                start(StabilizerService.ACTION_START);
            }
        } else if (!StabilizerService.running()) {
            start(StabilizerService.ACTION_RUN);
        }
    }

    /** singleTask: opening the app again, or a later `am start`, arrives here instead of onCreate. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mStartOnOpen = true;
        handleIntent(intent);
        startOnOpen();
        showSummaries();
    }

    /**
     * Extras from adb: "options" saves settings (applied right away), "start" starts, "mouse" opens
     * the mouse screen, "3d" switches the glasses to 3D (true) or 2D (false), "mcu" sends a command
     * to the glasses (development).
     */
    private void handleIntent(Intent intent) {
        String options = intent.getStringExtra("options");
        if (options != null) {
            Options.save(this, options);
            StabilizerService.reloadPicture(this);
            mMain.postDelayed(this::refresh, 100);
        }
        if (intent.getBooleanExtra("start", false)) {
            mStabilizeOnOpen = true;
        }
        if (intent.getBooleanExtra("mouse", false)) {
            startActivity(new Intent(this, MouseActivity.class));
        }
        if (intent.hasExtra("3d")) {
            startForegroundService(new Intent(this, StabilizerService.class).setAction(intent.getBooleanExtra("3d", false)
                    ? StabilizerService.ACTION_3D_ON : StabilizerService.ACTION_3D_OFF));
        }
        String mcu = intent.getStringExtra("mcu");
        if (mcu != null && StabilizerService.running()) {
            startService(new Intent(this, StabilizerService.class).setAction(StabilizerService.ACTION_MCU)
                    .putExtra("mcu", mcu));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSummaries();
        mMain.post(mRefresh);
    }

    /** The settings rows' summaries (changed on their screens, or over adb; the picture's is paused in 3D). */
    private void showSummaries() {
        Options o = Options.load(this);
        mPictureSummary.setText(StabilizerService.in3d() ? "Paused in 3D: full size, centered" : PictureActivity.describe(o));
        mStabilizationSummary.setText(SettingsActivity.describe(o));
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

    /** Checks Shizuku and its permission, then has the service (started if need be) do action. */
    private void start(String action) {
        mAction = action;
        if (!Shizuku.pingBinder()) {
            // Shizuku hands its binder to this process shortly after start, if it's running.
            mStartWhenReady = true;
            mStatus.setText("Waiting for Shizuku… (is it running?)");
            mMain.postDelayed(() -> {
                if (mStartWhenReady && !Shizuku.pingBinder()) {
                    mStartWhenReady = false;
                    mStatus.setText("Shizuku isn't running. Start it in the Shizuku app, then try again.");
                }
            }, 3000);
            return;
        }
        if (Shizuku.isPreV11()) {
            mStatus.setText("This Shizuku version is too old. Update Shizuku.");
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startWith(action);
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            mStatus.setText("XR Desk was denied access to Shizuku. Allow it in the Shizuku app.");
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
        }
    }

    private void startWith(String action) {
        startForegroundService(new Intent(this, StabilizerService.class).setAction(action));
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
            String status = StabilizerService.status();
            mStatus.setText(StabilizerService.switching() ? status + " " + StabilizerService.SWITCH_NOTE : status);
        }
        mStartStop.setText(running ? "Stop Stabilization" : "Start Stabilization");
        mRecenter.setEnabled(running);
        show3d();
        showSummaries();
    }

    /** Switch to 3D / Switch to 2D, or "Switching…" (disabled) from the tap until the glasses are back. */
    private void show3d() {
        boolean switching = StabilizerService.switching()
                || SystemClock.uptimeMillis() - mSwitchAskedMs < SWITCH_ASKED_MS;
        m3d.setEnabled(!switching);
        m3d.setText(switching ? "Switching…" : StabilizerService.in3d() ? "Switch to 2D" : "Switch to 3D");
    }

    /**
     * A row that opens a settings screen: its title, a summary of its settings below (returned,
     * to fill in) and a › at the end.
     */
    private TextView settingsRow(LinearLayout parent, String title, Class<? extends Activity> screen, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(Ui.inches(this, ROW_HEIGHT_IN)));
        row.setPadding(40, 28, 32, 28);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, Ui.rounded(Ui.PRESSED_COLOR));
        bg.addState(new int[0], Ui.rounded(Ui.BUTTON_COLOR));
        row.setBackground(bg);
        row.setOnClickListener(v -> startActivity(new Intent(this, screen)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 18));
        TextView summary = Ui.text(this, "", 14);
        summary.setTextColor(Ui.SECONDARY_TEXT);
        summary.setPadding(0, 4, 0, 0);
        texts.addView(summary);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = Ui.text(this, "›", 28);
        arrow.setTextColor(Ui.SECONDARY_TEXT);
        arrow.setPadding(24, 0, 0, 0);
        row.addView(arrow);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        parent.addView(row, p);
        return summary;
    }

    /** A bottom action's size: full width, a fixed height, and its gap below. */
    private LinearLayout.LayoutParams actionParams(float heightIn, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(Ui.inches(this, heightIn)));
        p.setMargins(48, 0, 48, bottomMargin);
        return p;
    }

    private Button button(String label, int sp, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(sp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        return b;
    }
}
