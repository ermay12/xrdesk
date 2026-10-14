package com.droid.xrdesk;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The XR Desk settings menu: a dialog centered over the desktops. It exists only while open, so
 * it costs nothing otherwise. Works with touch, mouse, keyboard (arrows/Tab, Enter) and game
 * controllers (D-pad, A to choose, B to close). Changes apply immediately.
 */
final class SettingsMenu {
    interface Host {
        int monitorCount();

        void setMonitorCount(int count);

        boolean swipeHorizontal();

        void setSwipeHorizontal(boolean horizontal);

        boolean rollEnabled();

        void setRollEnabled(boolean enabled);

        int megabytesPerMonitor();

        void onMenuClosed(String how);
    }

    static final int MAX_MONITORS = 4;
    private static final int PANEL = 0xF2202124;
    private static final int CHIP = 0xFF3C4043;
    private static final int CHIP_HOVER = 0xFF4A4E52;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int TEXT = 0xFFE8EAED;
    private static final int TEXT_DIM = 0xFFBDC1C6;

    private final Activity mActivity;
    private final Host mHost;
    private Dialog mDialog;
    private String mCloseReason;
    private final Button[] mMonitorButtons = new Button[MAX_MONITORS];
    private Button mUpDown;
    private Button mLeftRight;
    private Button mRollOff;
    private Button mRollOn;
    private TextView mMonitorNote;

    SettingsMenu(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
    }

    boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    void show() {
        if (isShowing()) {
            return;
        }
        Dialog dialog = new Dialog(mActivity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(buildContent());
        dialog.setOnKeyListener((d, keyCode, event) -> onKey(keyCode, event));
        dialog.setOnDismissListener(d -> {
            mDialog = null;
            String how = mCloseReason != null ? mCloseReason : "Back or outside tap";
            mCloseReason = null;
            mHost.onMenuClosed(how);
        });
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.35f);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        w.setAttributes(lp);
        // Show without focus first, hide the system bars, then take focus: keeps the immersive
        // look instead of flashing the status and navigation bars.
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mDialog = dialog;
        dialog.show();
        int screenWidth = mActivity.getWindow().getDecorView().getWidth();
        int width = Math.max(dp(420), Math.round(screenWidth * 0.6f));
        w.setLayout(Math.min(width, screenWidth - dp(16)), ViewGroup.LayoutParams.WRAP_CONTENT);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        refresh();
        mMonitorButtons[Math.max(0, Math.min(MAX_MONITORS, mHost.monitorCount()) - 1)].requestFocus();
    }

    /** Closes the menu; {@code how} is logged (e.g. "shake", "F12", "Done"). */
    void dismiss(String how) {
        if (mDialog != null) {
            mCloseReason = how;
            mDialog.dismiss();  // the dismiss listener tells the host
        }
    }

    /** Updates the selected choices, e.g. after a setting changed. */
    void refresh() {
        if (mDialog == null) {
            return;
        }
        int count = mHost.monitorCount();
        for (int i = 0; i < MAX_MONITORS; i++) {
            mMonitorButtons[i].setSelected(i + 1 == count);
        }
        boolean horizontal = mHost.swipeHorizontal();
        mUpDown.setSelected(!horizontal);
        mLeftRight.setSelected(horizontal);
        boolean roll = mHost.rollEnabled();
        mRollOff.setSelected(!roll);
        mRollOn.setSelected(roll);
        mMonitorNote.setText("Each monitor keeps about " + mHost.megabytesPerMonitor()
                + " MB of graphics memory. Removing a monitor moves its apps to the phone screen.");
    }

    private boolean onKey(int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && event.getRepeatCount() == 0) {
                    View focus = mDialog.getCurrentFocus();
                    if (focus != null) {
                        focus.performClick();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_F12:
            case KeyEvent.KEYCODE_BUTTON_MODE:
                if (down && event.getRepeatCount() == 0) {
                    dismiss(KeyEvent.keyCodeToString(keyCode));
                }
                return true;
            default:
                return false;  // D-pad/arrows/Tab move focus, Enter/Space click, Back closes
        }
    }

    private View buildContent() {
        LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(14), dp(24), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(20));
        panel.setBackground(bg);

        panel.addView(text("XR Desk", 20, TEXT, true));

        // One row per setting: label on the left, choices on the right; a short note below.
        Button[] monitors = new Button[MAX_MONITORS];
        for (int i = 0; i < MAX_MONITORS; i++) {
            final int count = i + 1;
            mMonitorButtons[i] = monitors[i] = chip(String.valueOf(count), v -> {
                mHost.setMonitorCount(count);
                refresh();
            });
        }
        panel.addView(setting("Monitors", monitors));
        mMonitorNote = note("");
        panel.addView(mMonitorNote);

        mUpDown = chip("Up / Down", v -> {
            mHost.setSwipeHorizontal(false);
            refresh();
        });
        mLeftRight = chip("Left / Right", v -> {
            mHost.setSwipeHorizontal(true);
            refresh();
        });
        panel.addView(setting("Flick to switch", mUpDown, mLeftRight));

        mRollOff = chip("Off", v -> {
            mHost.setRollEnabled(false);
            refresh();
        });
        mRollOn = chip("On", v -> {
            mHost.setRollEnabled(true);
            refresh();
        });
        panel.addView(setting("Roll smoothing", mRollOff, mRollOn));
        panel.addView(note("Keeps the desktop level when you tilt your head. Uses the GPU while tilted."));

        LinearLayout footer = row();
        TextView hint = note("Shake your head, press Back, F12 or the controller's Home button to close.");
        footer.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button done = chip("Done", v -> dismiss("Done"));
        footer.addView(done, new LinearLayout.LayoutParams(dp(120), dp(44)));
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(10);
        panel.addView(footer, footerLp);

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(panel);
        return scroll;
    }

    private LinearLayout setting(String label, Button... choices) {
        LinearLayout r = row();
        r.setPadding(0, dp(10), 0, 0);
        TextView t = text(label, 16, TEXT, true);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        LinearLayout chips = row();
        for (Button b : choices) {
            chips.addView(b, weighted());
        }
        r.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.1f));
        return r;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, TEXT_DIM, false);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(mActivity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMarginEnd(dp(8));
        return lp;
    }

    private Button chip(String label, View.OnClickListener onClick) {
        Button b = new Button(mActivity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setBackground(chipBackground());
        b.setDefaultFocusHighlightEnabled(false);
        b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_selected}, {}},
                new int[] {0xFF202124, TEXT}));
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    private Drawable chipBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused, android.R.attr.state_selected}, rounded(ACCENT, true));
        s.addState(new int[] {android.R.attr.state_selected}, rounded(ACCENT, false));
        s.addState(new int[] {android.R.attr.state_focused}, rounded(CHIP, true));
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(CHIP_HOVER, false));
        s.addState(new int[] {android.R.attr.state_hovered}, rounded(CHIP_HOVER, false));
        s.addState(new int[] {}, rounded(CHIP, false));
        return s;
    }

    private Drawable rounded(int color, boolean focusRing) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        if (focusRing) {
            g.setStroke(dp(3), Color.WHITE);
        }
        return g;
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
