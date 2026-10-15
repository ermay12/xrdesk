package com.droid.xrdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Entry point from the app icon. If this process already has shell access (started by
 * ShellInstrumentation), it just shows the desktop. Otherwise it binds ShellLaunchService
 * through Shizuku, which runs the same `am instrument` command xrdesk.sh runs over adb; that
 * restarts XR Desk with shell access.
 *
 * Start options for icon launches are saved from the "options" extra, e.g.
 *   am start -n com.droid.xrdesk/.LaunchActivity --es options "imu=xreal display=3"
 */
public class LaunchActivity extends Activity {
    private static final String TAG = ShellInstrumentation.TAG;
    private static final int PERMISSION_REQUEST = 1;
    private static final Pattern OPTION_KEY = Pattern.compile("[A-Za-z0-9_]+");
    private static final String NOT_RUNNING = "Shizuku isn't running.\n\n"
            + "Open the Shizuku app and start it, then open XR Desk again.\n"
            + "(Or start XR Desk over adb: ~/xrdesk/xrdesk.sh start)";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mMessage;
    private boolean mLaunching;
    private Shizuku.UserServiceArgs mServiceArgs;

    private final Shizuku.OnBinderReceivedListener mBinderListener = this::onShizukuReady;
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = (requestCode, result) -> {
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (result == PackageManager.PERMISSION_GRANTED) {
            launchWithShellAccess();
        } else {
            show("XR Desk wasn't allowed to use Shizuku.\n\nAllow it in the Shizuku app, then open XR Desk again.");
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            String[] options = withDisplay(parseOptions(prefs().getString("options", "")));
            new Thread(() -> startThrough(service, options), "xrdesk-shizuku").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String options = getIntent().getStringExtra("options");
        if (options != null) {
            prefs().edit().putString("options", options.trim()).apply();
            Log.i(TAG, "saved start options: " + options.trim());
        }
        if (ShellInstrumentation.get() != null) {
            startActivity(new Intent(this, DesktopActivity.class));
            finish();
            return;
        }

        mMessage = new TextView(this);
        mMessage.setBackgroundColor(Color.BLACK);
        mMessage.setTextColor(Color.WHITE);
        mMessage.setTextSize(18);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setPadding(48, 48, 48, 48);
        setContentView(mMessage);
        show("Starting XR Desk…");

        Shizuku.addRequestPermissionResultListener(mPermissionListener, mMain);
        // Shizuku hands its binder to this process shortly after it starts, if Shizuku is running.
        Shizuku.addBinderReceivedListenerSticky(mBinderListener, mMain);
        mMain.postDelayed(() -> {
            if (!mLaunching && !Shizuku.pingBinder()) {
                show(NOT_RUNNING);
            }
        }, 3000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMain.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(mBinderListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        if (mServiceArgs != null && Shizuku.pingBinder()) {
            Shizuku.unbindUserService(mServiceArgs, mConnection, true);
        }
    }

    private void onShizukuReady() {
        if (mLaunching || isFinishing()) {
            return;
        }
        if (Shizuku.isPreV11()) {
            show("This Shizuku version is too old. Update Shizuku, then open XR Desk again.");
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            launchWithShellAccess();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // The user chose "deny and don't ask again"; Shizuku won't show the prompt.
            show("XR Desk was denied access to Shizuku.\n\nAllow it in the Shizuku app, then open XR Desk again.");
        } else {
            Shizuku.requestPermission(PERMISSION_REQUEST);
        }
    }

    private void launchWithShellAccess() {
        if (mLaunching) {
            return;
        }
        mLaunching = true;
        mServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(this, ShellLaunchService.class))
                .daemon(false)  // ends with this process; the `am` it starts is detached
                .processNameSuffix("launch")
                .version(1);
        try {
            Shizuku.bindUserService(mServiceArgs, mConnection);
        } catch (RuntimeException e) {
            Log.e(TAG, "couldn't bind the Shizuku user service", e);
            fail(e.toString());
        }
    }

    /** Background thread. Asks the shell-side service to run `am instrument`. */
    private void startThrough(IBinder service, String[] options) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShellLaunchService.DESCRIPTOR);
            data.writeStringArray(options);
            service.transact(ShellLaunchService.TRANSACTION_START_XR_DESK, data, reply, 0);
            reply.readException();
            int exit = reply.readInt();
            Log.i(TAG, "started via Shizuku user service (sh exit " + exit + "), options " + Arrays.toString(options));
            if (exit != 0) {
                mMain.post(() -> fail("am instrument didn't start (exit " + exit + ")"));
            }
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Shizuku launch failed", e);
            mMain.post(() -> fail(e.toString()));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** "key=value key=value" to alternating keys and values; option names must be plain words. */
    private static String[] parseOptions(String options) {
        List<String> out = new ArrayList<>();
        for (String kv : options.trim().split("\\s+")) {
            int eq = kv.indexOf('=');
            if (eq <= 0 || eq == kv.length() - 1) {
                continue;
            }
            String key = kv.substring(0, eq);
            if (!OPTION_KEY.matcher(key).matches()) {
                Log.w(TAG, "ignoring start option with an invalid name: " + kv);
                continue;
            }
            out.add(key);
            out.add(kv.substring(eq + 1));
        }
        return out.toArray(new String[0]);
    }

    /**
     * Opens XR Desk fullscreen on the display the icon was tapped on (e.g. the glasses, where
     * desktop mode would otherwise give it a small floating window), unless a display is saved.
     */
    private String[] withDisplay(String[] options) {
        for (int i = 0; i < options.length; i += 2) {
            if (options[i].equals("display")) {
                return options;
            }
        }
        String[] out = Arrays.copyOf(options, options.length + 2);
        out[options.length] = "display";
        out[options.length + 1] = String.valueOf(getDisplay().getDisplayId());
        return out;
    }

    private void fail(String reason) {
        mLaunching = false;
        show("Couldn't start XR Desk through Shizuku:\n" + reason);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("xrdesk", MODE_PRIVATE);
    }

    private void show(String text) {
        mMessage.setText(text);
    }
}
