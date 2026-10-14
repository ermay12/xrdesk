package com.droid.xrdesk;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Shizuku user service: Shizuku runs this class in its own process as the shell user. Its only
 * job is to run `am instrument` for XR Desk, which restarts XR Desk with shell access. It is
 * reached through raw Binder transactions (this build has no AIDL compiler).
 */
public class ShellLaunchService extends Binder {
    static final String DESCRIPTOR = "com.droid.xrdesk.ShellLaunchService";
    /** Request: String[] of alternating option keys and values. Reply: int exit code of sh. */
    static final int TRANSACTION_START_XR_DESK = FIRST_CALL_TRANSACTION;
    /** Reserved by Shizuku: sent when the service should exit. */
    private static final int TRANSACTION_DESTROY = 16777114;

    public ShellLaunchService() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case TRANSACTION_DESTROY:
                System.exit(0);
                return true;
            case TRANSACTION_START_XR_DESK:
                data.enforceInterface(DESCRIPTOR);
                int exit = startXrDesk(data.createStringArray());
                reply.writeNoException();
                reply.writeInt(exit);
                exitSoon();
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /**
     * This service does one job per launch. Shizuku doesn't reliably stop it when XR Desk
     * restarts, so exit once the reply has had time to reach the caller.
     */
    private static void exitSoon() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "xrdesk-launch-exit");
        t.setDaemon(true);
        t.start();
    }

    private static int startXrDesk(String[] options) {
        List<String> cmd = new ArrayList<>();
        // nohup and & so `am` outlives this process. The arguments reach it as "$@", so the
        // shell never parses option values.
        cmd.add("/system/bin/sh");
        cmd.add("-c");
        cmd.add("nohup \"$@\" >/dev/null 2>&1 &");
        cmd.add("sh");
        cmd.add("/system/bin/am");
        cmd.add("instrument");
        cmd.add("-w");
        cmd.add("--no-hidden-api-checks");
        for (int i = 0; options != null && i + 1 < options.length; i += 2) {
            cmd.add("-e");
            cmd.add(options[i]);
            cmd.add(options[i + 1]);
        }
        cmd.add("com.droid.xrdesk/.ShellInstrumentation");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return p.waitFor();
        } catch (Exception e) {
            Log.e(ShellInstrumentation.TAG, "couldn't run am instrument", e);
            return -1;
        }
    }
}
