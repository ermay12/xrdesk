package com.droid.xrdesk;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Started from adb with `am instrument`. Adopts the shell's permission identity so
 * this process can create a trusted virtual display and inject input into it, then
 * stays alive until DesktopActivity finishes.
 */
public class ShellInstrumentation extends Instrumentation {
    static final String TAG = "XRDesk";

    private static volatile ShellInstrumentation sInstance;

    private final Object mLock = new Object();
    private boolean mDone;
    private Bundle mArgs;
    private UiAutomation mUiAutomation;

    /** The running instrumentation, or null if the app was opened without adb. */
    static ShellInstrumentation get() {
        return sInstance;
    }

    Bundle args() {
        return mArgs;
    }

    UiAutomation uiAutomation() {
        return mUiAutomation;
    }

    void shutdown() {
        synchronized (mLock) {
            mDone = true;
            mLock.notifyAll();
        }
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mArgs = arguments != null ? arguments : new Bundle();
        start();
    }

    /** Starts DesktopActivity, on the display given by the "display" argument if any. */
    private void launch() {
        Intent intent = new Intent(getTargetContext(), DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String display = mArgs.getString("display");
        if (display != null) {
            try {
                int id = Integer.parseInt(display.trim());
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(id);
                // External displays default to freeform windows in desktop mode; ask for fullscreen.
                try {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                            .invoke(options, 1 /* WindowConfiguration.WINDOWING_MODE_FULLSCREEN */);
                } catch (ReflectiveOperationException e) {
                    Log.w(TAG, "setLaunchWindowingMode unavailable: " + e);
                }
                getTargetContext().startActivity(intent, options.toBundle());
                Log.i(TAG, "launched on display " + id);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "couldn't launch on display " + display + "; using the default display", e);
            }
        }
        getTargetContext().startActivity(intent);
    }

    @Override
    public void onStart() {
        try {
            mUiAutomation = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
            mUiAutomation.adoptShellPermissionIdentity();
            sInstance = this;
            Log.i(TAG, "adopted shell permission identity");
            launch();
        } catch (Throwable t) {
            Log.e(TAG, "instrumentation setup failed", t);
            Bundle result = new Bundle();
            result.putString(REPORT_KEY_STREAMRESULT, "XR Desk setup failed: " + t + "\n");
            finish(1, result);
            return;
        }

        synchronized (mLock) {
            while (!mDone) {
                try {
                    mLock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        try {
            mUiAutomation.dropShellPermissionIdentity();
        } catch (Throwable ignored) {
        }
        Bundle result = new Bundle();
        result.putString(REPORT_KEY_STREAMRESULT, "XR Desk stopped\n");
        finish(0, result);
    }
}
