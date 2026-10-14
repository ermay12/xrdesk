package com.droid.xrdesk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.droid.xrdesk.xreal.GyroOffset;
import com.droid.xrdesk.xreal.RateRing;
import com.droid.xrdesk.xreal.XrealProtocol;

import java.util.Locale;

/**
 * Head rotation from the IMU of XREAL xbx a01 / a01+ glasses over USB HID (see XrealProtocol and
 * NOTES-xreal.md). Samples are converted to the viewer's frame (rad/s about the display's right
 * and up axes) and queued in {@link #ring()}; {@link Listener#onSamples()} is called from the
 * reader thread whenever new samples are queued. All USB I/O happens on two private threads:
 * the reader (which also owns the connection) and the MCU heartbeat.
 *
 * Nothing here has been run against real glasses yet.
 */
final class XrealImuSource {
    private static final String TAG = ShellInstrumentation.TAG;
    private static final String ACTION_USB_PERMISSION = "com.droid.xrdesk.USB_PERMISSION";
    private static final int READ_TIMEOUT_MS = 200;
    private static final int WRITE_TIMEOUT_MS = 100;
    private static final long STALL_NS = 2_000_000_000L;      // no samples this long = failed
    private static final long FIRST_SAMPLE_NS = 3_000_000_000L;
    private static final long RETRY_DELAY_MS = 3000;
    private static final int MAX_RETRIES = 3;

    interface Listener {
        /** Reader thread: new samples are in the ring. */
        void onSamples();

        /** Any thread: the source started or stopped streaming. */
        void onStateChanged(boolean streaming, String detail);
    }

    private final Context mContext;
    private final UsbManager mUsbManager;
    private final Listener mListener;
    private final RateRing mRing = new RateRing(512);

    private volatile boolean mEnabled;
    private volatile Thread mReader;
    private volatile int mRetries;
    private boolean mReceiverRegistered;

    XrealImuSource(Context context, Listener listener) {
        mContext = context;
        mUsbManager = context.getSystemService(UsbManager.class);
        mListener = listener;
    }

    RateRing ring() {
        return mRing;
    }

    /** Starts watching for the glasses and connects if they're attached. Main thread. */
    void start() {
        if (mEnabled) {
            return;
        }
        mEnabled = true;
        mRetries = 0;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        // Attach/detach are system broadcasts and the permission result comes from our own
        // PendingIntent; both reach a non-exported receiver.
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
        tryConnect("start");
    }

    /** Stops streaming and releases the device. Main thread; doesn't block on USB I/O. */
    void stop() {
        mEnabled = false;
        if (mReceiverRegistered) {
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mReceiverRegistered = false;
        }
        Thread reader = mReader;
        if (reader != null) {
            reader.interrupt();  // the reader notices mEnabled == false within READ_TIMEOUT_MS
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (device == null || !XrealProtocol.isSupported(device.getVendorId(), device.getProductId())) {
                return;
            }
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                mRetries = 0;
                tryConnect("attached");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "xreal: glasses detached");
                Thread reader = mReader;
                if (reader != null) {
                    reader.interrupt();
                }
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "xreal: USB permission " + (granted ? "granted" : "denied") + " by user");
                if (granted) {
                    tryConnect("permission granted");
                } else {
                    mListener.onStateChanged(false, "USB permission denied");
                }
            }
        }
    };

    private UsbDevice findDevice() {
        for (UsbDevice d : mUsbManager.getDeviceList().values()) {
            if (XrealProtocol.isSupported(d.getVendorId(), d.getProductId())) {
                return d;
            }
        }
        return null;
    }

    /** Main thread. Starts a reader thread if the glasses are attached and none is running. */
    private void tryConnect(String why) {
        if (!mEnabled || mReader != null) {
            return;
        }
        UsbDevice device = findDevice();
        if (device == null) {
            mListener.onStateChanged(false, "no XREAL xbx a01/a01+ attached (" + why + ")");
            return;
        }
        if (!mUsbManager.hasPermission(device) && !grantViaShell(device)) {
            // Fall back to the normal permission dialog; the result arrives as ACTION_USB_PERMISSION.
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(mContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            mUsbManager.requestPermission(device, pi);
            mListener.onStateChanged(false, "waiting for USB permission");
            return;
        }
        Thread reader = new Thread(() -> run(device), "xrdesk-xreal-imu");
        mReader = reader;
        reader.start();
    }

    /** The app runs with the shell's identity, which holds MANAGE_USB, so it can grant itself access. */
    private boolean grantViaShell(UsbDevice device) {
        try {
            UsbManager.class.getMethod("grantPermission", UsbDevice.class).invoke(mUsbManager, device);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "xreal: grantPermission via shell identity failed: " + e);
            return false;
        }
        boolean ok = mUsbManager.hasPermission(device);
        Log.i(TAG, "xreal: USB permission granted via shell identity: " + ok);
        return ok;
    }

    // ---- Reader thread ----

    private static final class Endpoints {
        UsbInterface iface;
        UsbEndpoint in;
        UsbEndpoint out;
    }

    private void run(UsbDevice device) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException e) {
            Log.w(TAG, "xreal: couldn't raise reader priority: " + e);
        }
        String name = String.format(Locale.US, "%04x:%04x", device.getVendorId(), device.getProductId());
        UsbDeviceConnection conn = null;
        Endpoints imu = null;
        Endpoints mcu = null;
        Heartbeat heartbeat = null;
        String why = "stopped";
        boolean streamed = false;
        try {
            conn = mUsbManager.openDevice(device);
            if (conn == null) {
                why = "openDevice failed for " + name;
                return;
            }
            imu = endpoints(device, XrealProtocol.A01_IMU_INTERFACE);
            mcu = endpoints(device, XrealProtocol.A01_MCU_INTERFACE);
            if (imu == null || mcu == null || imu.in == null || mcu.in == null) {
                why = "unexpected USB interfaces on " + name + ": " + describe(device);
                return;
            }
            if (!conn.claimInterface(mcu.iface, true) || !conn.claimInterface(imu.iface, true)) {
                why = "claimInterface failed on " + name + " (Android or the kernel refused the HID interfaces)";
                return;
            }
            Log.i(TAG, "xreal: claimed " + name + " " + describe(device));

            byte[] buf = new byte[XrealProtocol.MAX_PACKET];
            String failed = bootstrapMcu(conn, mcu, buf);
            if (failed != null) {
                why = "MCU bootstrap failed: " + failed;
                return;
            }
            heartbeat = new Heartbeat(conn, mcu);
            heartbeat.start();

            // Stop any stream left running, then start it. The start reply is often the init
            // frame rather than an echo (XrealBeam XrealImu.java:268-270), so don't require one.
            writeImu(conn, imu, buf, (byte) 0x00);
            drain(conn, imu.in, buf, 4);
            if (writeImu(conn, imu, buf, (byte) 0x01) < 0) {
                why = "IMU start command failed";
                return;
            }
            streamed = readLoop(conn, imu, buf, name);
            why = streamed ? "IMU stream ended" : "no IMU samples within "
                    + FIRST_SAMPLE_NS / 1_000_000_000L + " s of starting the stream";
        } catch (RuntimeException e) {
            why = "error: " + e;
            Log.w(TAG, "xreal: reader failed", e);
        } finally {
            boolean heartbeatStopped = heartbeat == null || heartbeat.shutdown();
            if (conn != null && heartbeatStopped) {
                if (imu != null && imu.iface != null) {
                    writeImu(conn, imu, new byte[XrealProtocol.MAX_PACKET], (byte) 0x00);  // fails fast if detached
                    conn.releaseInterface(imu.iface);
                }
                if (mcu != null && mcu.iface != null) {
                    conn.releaseInterface(mcu.iface);
                }
                conn.close();
            } else if (conn != null) {
                // Never close a connection another thread may still be using (native use-after-free).
                Log.e(TAG, "xreal: heartbeat thread didn't stop; leaking the USB connection");
            }
            mReader = null;
            Log.i(TAG, "xreal: stopped: " + why);
            mListener.onStateChanged(false, why);
            maybeRetry(streamed);
        }
    }

    /** Retries a few times if the glasses are still attached (e.g. a transient USB error). */
    private void maybeRetry(boolean streamed) {
        if (!mEnabled) {
            return;
        }
        if (streamed) {
            mRetries = 0;
        }
        if (mRetries >= MAX_RETRIES) {
            return;
        }
        mRetries++;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(() -> tryConnect("retry " + mRetries), RETRY_DELAY_MS);
    }

    /** Returns true if any samples were streamed. Exits on stop, stall or detach. */
    private boolean readLoop(UsbDeviceConnection conn, Endpoints imu, byte[] buf, String name) {
        XrealProtocol.ImuSample sample = new XrealProtocol.ImuSample();
        GyroOffset offset = new GyroOffset();
        float[] g = new float[3];
        float[] view = new float[3];
        long started = SystemClock.elapsedRealtimeNanos();
        long lastSampleWall = 0;
        long lastTs = 0;
        long count = 0;
        long statsSince = started;
        long statsCount = 0;
        int malformed = 0;
        while (mEnabled && !Thread.currentThread().isInterrupted()) {
            int n = conn.bulkTransfer(imu.in, buf, buf.length, READ_TIMEOUT_MS);
            long now = SystemClock.elapsedRealtimeNanos();
            if (n > 0) {
                int kind = XrealProtocol.parseImuReport(buf, n, sample);
                if (kind == XrealProtocol.SAMPLE) {
                    float dt = lastTs == 0 ? 0f : (sample.timestampNs - lastTs) * 1e-9f;
                    lastTs = sample.timestampNs;
                    if (dt < 0f || dt > 0.05f) {
                        dt = 0f;  // first sample, clock wrap or a gap: don't integrate across it
                    }
                    g[0] = sample.gx;
                    g[1] = sample.gy;
                    g[2] = sample.gz;
                    offset.update(g, dt);
                    XrealProtocol.toViewerRates(g[0], g[1], g[2], view);
                    mRing.put(sample.timestampNs, view[0], view[1], view[2]);
                    mListener.onSamples();
                    if (count == 0) {
                        Log.i(TAG, "xreal: IMU streaming from " + name);
                        mListener.onStateChanged(true, "XREAL " + name);
                    }
                    count++;
                    statsCount++;
                    lastSampleWall = now;
                } else if (kind == XrealProtocol.MALFORMED && ++malformed <= 3) {
                    Log.w(TAG, "xreal: malformed IMU report, " + n + " bytes");
                }
            } else {
                // Timeouts and errors both return -1 (and a zero-length read 0); after a detach
                // errors return immediately, so back off instead of spinning.
                SystemClock.sleep(5);
            }
            long reference = count == 0 ? started : lastSampleWall;
            long limit = count == 0 ? FIRST_SAMPLE_NS : STALL_NS;
            if (now - reference > limit) {
                return count > 0;
            }
            if (now - statsSince >= 10_000_000_000L) {
                Log.i(TAG, String.format(Locale.US,
                        "xreal: %.0f Hz, gyro offset %.2f %.2f %.2f deg/s, dropped %d",
                        statsCount * 1e9 / (now - statsSince), offset.offsetX(), offset.offsetY(),
                        offset.offsetZ(), mRing.dropped()));
                statsSince = now;
                statsCount = 0;
            }
        }
        return count > 0;
    }

    /** nrealAirLinuxDriver mcu_hid.c:297-343: bootstrap commands, SDK version, two heartbeats. */
    private String bootstrapMcu(UsbDeviceConnection conn, Endpoints mcu, byte[] buf) {
        drain(conn, mcu.in, buf, 4);
        byte[] out = new byte[XrealProtocol.MAX_PACKET];
        for (int i = 0; i < XrealProtocol.MCU_BOOTSTRAP_IDS.length; i++) {
            if (!mEnabled) {
                return "stopped";
            }
            byte[] data = XrealProtocol.MCU_BOOTSTRAP_DATA[i];
            int len = XrealProtocol.buildMcuCommand(out, XrealProtocol.MCU_BOOTSTRAP_IDS[i], data,
                    data == null ? 0 : data.length);
            if (!mcuCommand(conn, mcu, out, len, buf)) {
                return String.format(Locale.US, "no ack for 0x%02x", XrealProtocol.MCU_BOOTSTRAP_IDS[i]);
            }
        }
        int len = XrealProtocol.buildMcuCommand(out, XrealProtocol.MCU_MSG_SDK_VERSION,
                XrealProtocol.MCU_SDK_VERSION, XrealProtocol.MCU_SDK_VERSION.length);
        if (!mcuCommand(conn, mcu, out, len, buf)) {
            return "no ack for the SDK version handshake (0x31)";
        }
        byte[] ts = new byte[8];
        for (int i = 0; i < 2; i++) {
            len = XrealProtocol.buildMcuHeartbeat(out, ts, SystemClock.elapsedRealtimeNanos());
            if (!mcuCommand(conn, mcu, out, len, buf)) {
                return "no ack for initial heartbeat " + (i + 1);
            }
        }
        Log.i(TAG, "xreal: MCU bootstrap done");
        return null;
    }

    /** Sends one MCU command and waits for any MCU-framed reply (mcu_hid.c:245-271). */
    private static boolean mcuCommand(UsbDeviceConnection conn, Endpoints mcu, byte[] out, int len, byte[] buf) {
        if (write(conn, mcu, out, len) < 0) {
            return false;
        }
        for (int i = 0; i < XrealProtocol.MCU_RESPONSE_ATTEMPTS; i++) {
            int n = conn.bulkTransfer(mcu.in, buf, buf.length, XrealProtocol.MCU_RESPONSE_TIMEOUT_MS);
            if (XrealProtocol.isMcuReply(buf, n)) {
                return true;
            }
        }
        return false;
    }

    private static int writeImu(UsbDeviceConnection conn, Endpoints imu, byte[] out, byte signal) {
        int len = XrealProtocol.buildImuCommand(out, XrealProtocol.IMU_MSG_START_IMU_DATA, new byte[]{signal}, 1);
        return write(conn, imu, out, len);
    }

    /** OUT endpoint if there is one, else HID SET_REPORT (as XrealBeam XrealImu.java:515-535). */
    private static int write(UsbDeviceConnection conn, Endpoints e, byte[] data, int len) {
        if (e.out != null) {
            int n = conn.bulkTransfer(e.out, data, len, WRITE_TIMEOUT_MS);
            if (n >= 0) {
                return n;
            }
        }
        return conn.controlTransfer(0x21, 0x09, 0x0200, e.iface.getId(), data, len, WRITE_TIMEOUT_MS);
    }

    private static void drain(UsbDeviceConnection conn, UsbEndpoint in, byte[] buf, int max) {
        for (int i = 0; i < max; i++) {
            if (conn.bulkTransfer(in, buf, buf.length, 10) <= 0) {
                return;
            }
        }
    }

    private static Endpoints endpoints(UsbDevice device, int interfaceNumber) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface f = device.getInterface(i);
            if (f.getId() != interfaceNumber || f.getAlternateSetting() != 0) {
                continue;
            }
            Endpoints e = new Endpoints();
            e.iface = f;
            for (int j = 0; j < f.getEndpointCount(); j++) {
                UsbEndpoint ep = f.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT && ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    e.in = ep;
                } else {
                    e.out = ep;
                }
            }
            return e;
        }
        return null;
    }

    private static String describe(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            sb.append(String.format(Locale.US, " if%d(class 0x%02x, alt %d, %d ep)", f.getId(),
                    f.getInterfaceClass(), f.getAlternateSetting(), f.getEndpointCount()));
        }
        return sb.toString();
    }

    /**
     * Sends a heartbeat every 100 ms (mcu_hid.c:277-292) and keeps the MCU's IN endpoint drained;
     * on Linux the kernel HID driver polls it continuously, and once we claim the interface
     * nothing else will.
     */
    private static final class Heartbeat extends Thread {
        private final UsbDeviceConnection mConn;
        private final Endpoints mMcu;
        private volatile boolean mRunning = true;

        Heartbeat(UsbDeviceConnection conn, Endpoints mcu) {
            super("xrdesk-xreal-heartbeat");
            mConn = conn;
            mMcu = mcu;
        }

        @Override
        public void run() {
            byte[] out = new byte[XrealProtocol.MAX_PACKET];
            byte[] in = new byte[XrealProtocol.MAX_PACKET];
            byte[] ts = new byte[8];
            while (mRunning) {
                int len = XrealProtocol.buildMcuHeartbeat(out, ts, SystemClock.elapsedRealtimeNanos());
                write(mConn, mMcu, out, len);
                long next = SystemClock.uptimeMillis() + XrealProtocol.MCU_HEARTBEAT_INTERVAL_MS;
                long left;
                while (mRunning && (left = next - SystemClock.uptimeMillis()) > 0) {
                    if (mConn.bulkTransfer(mMcu.in, in, in.length, (int) Math.min(left, 50)) < 0) {
                        // Timeouts have already waited; after a detach errors return at once,
                        // so don't spin until the reader notices.
                        SystemClock.sleep(Math.min(left, 10));
                    }
                }
            }
        }

        /** Stops the thread and waits for it; returns false if it's somehow still running. */
        boolean shutdown() {
            mRunning = false;
            // The caller may have been interrupted (that's how the reader is stopped), and an
            // interrupted join returns immediately; clear it so we really wait, then restore it.
            boolean interrupted = Thread.interrupted();
            long deadline = SystemClock.uptimeMillis() + 1000;
            while (isAlive() && SystemClock.uptimeMillis() < deadline) {
                try {
                    join(100);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return !isAlive();
        }
    }
}
