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
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A connection to XREAL xbx a01 / a01+ glasses over USB HID (see XrealProtocol and NOTES-xreal.md):
 * commands to them (e.g. their display mode, see {@link #command}), their buttons
 * ({@link Listener#onButton}) and, while {@link #setStreaming} asks for it, head rotation from
 * their IMU. Samples are converted to the viewer's frame (rad/s about the display's right and up
 * axes) and queued in {@link #ring()}; {@link Listener#onSamples()} is called from the reader
 * thread whenever new samples are queued. All USB I/O happens on private threads: the reader
 * (which owns the connection and reads the IMU) and the MCU thread (heartbeat, commands and what
 * the glasses send).
 *
 * <p>Connecting includes the SDK-version handshake, which the IMU needs; it also makes the glasses
 * send their button presses here instead of acting on them, until they're unplugged.
 *
 * Tested with xbx a01+ glasses on a Pixel 9a (Android 17): about 860 samples/s.
 */
final class XrealImuSource {
    private static final String TAG = "XRDesk";
    private static final String ACTION_USB_PERMISSION = "com.droid.xrdesk.USB_PERMISSION";
    private static final int READ_TIMEOUT_MS = 200;
    private static final int WRITE_TIMEOUT_MS = 100;
    private static final long STALL_NS = 2_000_000_000L;      // no samples this long = failed
    private static final long FIRST_SAMPLE_NS = 3_000_000_000L;
    private static final long RETRY_DELAY_MS = 3000;
    private static final int MAX_RETRIES = 3;
    private static final long MCU_REPLY_TIMEOUT_MS = 1000;
    // The glasses keep a press made while nobody was reading and send it once someone does.
    private static final long STALE_BUTTON_MS = 500;
    /** onConnected's detail when the glasses' USB side went away (unplugged, or it reconnects by itself). */
    static final String DETACHED = "the glasses' USB connection went away";

    interface Listener {
        /** Reader thread: new samples are in the ring. */
        void onSamples();

        /** Any thread: connected to the glasses (commands go through, buttons come in), or not, and why. */
        void onConnected(boolean connected, String detail);

        /** Reader thread: IMU samples arrive, after setStreaming(true). */
        void onStreaming();

        /** MCU thread: a button on the glasses, XrealProtocol BUTTON_MENU etc., and what it did, BUTTON_DOWN etc. */
        void onButton(int button, int event);
    }

    /** USB thread: a command's reply data (status byte first, 0 = ok), or null if none came. */
    interface McuReply {
        void onReply(byte[] data);
    }

    private static final class Command {
        final int msgId;
        final byte[] data;
        final McuReply reply;

        Command(int msgId, byte[] data, McuReply reply) {
            this.msgId = msgId;
            this.data = data;
            this.reply = reply;
        }
    }

    private final Context mContext;
    private final UsbManager mUsbManager;
    private final Listener mListener;
    private final Runnable mAfterAsking;
    private final RateRing mRing = new RateRing(512);

    private volatile boolean mEnabled;
    private volatile boolean mStreamWanted;
    private final Object mWake = new Object();  // the idle reader waits on it for mStreamWanted
    private volatile Thread mReader;
    private volatile int mRetries;
    private volatile boolean mDetached;   // the glasses' USB side went away: wait for it to come back, don't retry
    private volatile Mcu mMcuThread;      // while connected
    private boolean mReceiverRegistered;

    /** afterAsking: runs right after Android is asked to show its USB permission dialog. */
    XrealImuSource(Context context, Listener listener, Runnable afterAsking) {
        mContext = context;
        mUsbManager = context.getSystemService(UsbManager.class);
        mListener = listener;
        mAfterAsking = afterAsking;
    }

    RateRing ring() {
        return mRing;
    }

    /**
     * Sends a command to the glasses' MCU (as mcu_hid.c send_payload_action); the reply comes on
     * the MCU thread, or null if none came or not connected (then at once, on this thread). Any thread.
     */
    void command(int msgId, byte[] data, McuReply reply) {
        Command c = new Command(msgId, data, reply);
        Mcu mcu = mMcuThread;
        if (mcu == null || !mcu.enqueue(c)) {
            reply.onReply(null);
        }
    }

    /** Connected to the glasses. Any thread. */
    boolean connected() {
        return mMcuThread != null;
    }

    /** The glasses are plugged in (their USB side). Any thread. */
    boolean attached() {
        return findDevice() != null;
    }

    /** Whether to stream IMU samples while connected. Any thread. */
    void setStreaming(boolean on) {
        mStreamWanted = on;
        synchronized (mWake) {
            mWake.notifyAll();
        }
    }

    /** Starts watching for the glasses and connects if they're attached. Main thread. */
    void start() {
        if (mEnabled) {
            return;
        }
        mEnabled = true;
        mRetries = 0;
        mDetached = false;
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

    /** Disconnects and releases the device. Main thread; doesn't block on USB I/O. */
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
                mDetached = false;
                tryConnect("attached");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "xreal: glasses detached");
                mDetached = true;
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
                    mListener.onConnected(false, "USB permission denied");
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
            mListener.onConnected(false, mDetached ? DETACHED : "no XREAL xbx a01/a01+ attached (" + why + ")");
            return;
        }
        if (!mUsbManager.hasPermission(device)) {
            // The normal permission dialog; "Always allow" there makes Android remember it for
            // these glasses. The result arrives as ACTION_USB_PERMISSION.
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(mContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            mUsbManager.requestPermission(device, pi);
            mAfterAsking.run();
            mListener.onConnected(false, "waiting for USB permission");
            return;
        }
        Thread reader = new Thread(() -> run(device), "xrdesk-xreal-imu");
        mReader = reader;
        reader.start();
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
        Mcu mcuThread = null;
        String why = "stopped";
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
            mcuThread = new Mcu(conn, mcu, mListener);
            mcuThread.start();
            mMcuThread = mcuThread;
            mListener.onConnected(true, "XREAL " + name);

            writeImu(conn, imu, buf, (byte) 0x00);  // stop any stream left running
            while (mEnabled && !Thread.currentThread().isInterrupted()) {
                if (!mStreamWanted) {
                    synchronized (mWake) {
                        if (!mStreamWanted && mEnabled) {
                            mWake.wait(READ_TIMEOUT_MS);
                        }
                    }
                    continue;
                }
                // The start reply is often the init frame rather than an echo (XrealBeam
                // XrealImu.java:268-270), so don't require one.
                drain(conn, imu.in, buf, 4);
                if (writeImu(conn, imu, buf, (byte) 0x01) < 0) {
                    why = "IMU start command failed";
                    return;
                }
                failed = readLoop(conn, imu, buf, name);
                writeImu(conn, imu, buf, (byte) 0x00);
                if (failed != null) {
                    why = failed;
                    return;  // reconnect (maybeRetry)
                }
            }
        } catch (InterruptedException e) {
            // stopped, or the glasses were detached
        } catch (RuntimeException e) {
            why = "error: " + e;
            Log.w(TAG, "xreal: reader failed", e);
        } finally {
            mMcuThread = null;
            boolean mcuStopped = mcuThread == null || mcuThread.shutdown();
            if (conn != null && mcuStopped) {
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
                Log.e(TAG, "xreal: MCU thread didn't stop; leaking the USB connection");
            }
            mReader = null;
            if (mDetached) {
                why = DETACHED;
            }
            Log.i(TAG, "xreal: stopped: " + why);
            mListener.onConnected(false, why);
            maybeRetry();
        }
    }

    /** Retries a few times if the glasses are still attached (e.g. a transient USB error); streaming resets the count. */
    private void maybeRetry() {
        if (!mEnabled || mDetached) {
            return;
        }
        if (mRetries >= MAX_RETRIES) {
            return;
        }
        mRetries++;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(() -> tryConnect("retry " + mRetries), RETRY_DELAY_MS);
    }

    /** Reads samples while they're wanted. Returns null once they aren't (or on detach), else why the stream failed. */
    private String readLoop(UsbDeviceConnection conn, Endpoints imu, byte[] buf, String name) {
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
        while (mEnabled && mStreamWanted && !Thread.currentThread().isInterrupted()) {
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
                        mRetries = 0;
                        mListener.onStreaming();
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
                return count > 0 ? "the IMU stream stopped"
                        : "no IMU samples within " + FIRST_SAMPLE_NS / 1_000_000_000L + " s of starting the stream";
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
        return null;
    }

    /**
     * nrealAirLinuxDriver mcu_hid.c:297-343: bootstrap commands, SDK version, two heartbeats. The
     * SDK-version handshake enables the IMU stream, and makes the glasses send their button
     * presses here instead of acting on them until they're unplugged (both measured on the a01+).
     */
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
     * Owns the MCU interface once bootstrapped: sends a heartbeat every 100 ms (mcu_hid.c:277-292)
     * and the queued commands, one at a time, and reads everything the MCU sends: replies,
     * button events and heartbeat acks. On Linux the kernel HID driver polls the IN endpoint
     * continuously; once we claim the interface nothing else will.
     */
    private static final class Mcu extends Thread {
        private final UsbDeviceConnection mConn;
        private final Endpoints mMcu;
        private final Listener mListener;
        private final ConcurrentLinkedQueue<Command> mQueue = new ConcurrentLinkedQueue<>();
        private volatile boolean mRunning = true;
        private Command mPending;  // MCU thread: sent, waiting for its reply
        private long mPendingUntilMs;
        private int mLogged;
        private final long mButtonsFromMs = SystemClock.uptimeMillis() + STALE_BUTTON_MS;

        Mcu(UsbDeviceConnection conn, Endpoints mcu, Listener listener) {
            super("xrdesk-xreal-mcu");
            mConn = conn;
            mMcu = mcu;
            mListener = listener;
        }

        /** Any thread. False once stopping (the command isn't sent). */
        boolean enqueue(Command c) {
            if (!mRunning) {
                return false;
            }
            mQueue.add(c);
            return true;
        }

        @Override
        public void run() {
            byte[] out = new byte[XrealProtocol.MAX_PACKET];
            byte[] in = new byte[XrealProtocol.MAX_PACKET];
            byte[] ts = new byte[8];
            try {
                while (mRunning) {
                    int len = XrealProtocol.buildMcuHeartbeat(out, ts, SystemClock.elapsedRealtimeNanos());
                    write(mConn, mMcu, out, len);
                    long next = SystemClock.uptimeMillis() + XrealProtocol.MCU_HEARTBEAT_INTERVAL_MS;
                    long left;
                    while (mRunning && (left = next - SystemClock.uptimeMillis()) > 0) {
                        if (mPending == null) {
                            send(mQueue.poll(), out);
                        }
                        int n = mConn.bulkTransfer(mMcu.in, in, in.length, (int) Math.min(left, 50));
                        if (n > 0) {
                            received(in, n);
                        } else if (n < 0) {
                            // Timeouts have already waited; after a detach errors return at once,
                            // so don't spin until the reader notices.
                            SystemClock.sleep(Math.min(left, 10));
                        }
                        if (mPending != null && SystemClock.uptimeMillis() > mPendingUntilMs) {
                            Log.w(TAG, String.format(Locale.US, "xreal: no reply to MCU 0x%02x", mPending.msgId));
                            reply(null);
                        }
                    }
                }
            } finally {
                reply(null);
                Command c;
                while ((c = mQueue.poll()) != null) {
                    c.reply.onReply(null);
                }
            }
        }

        private void send(Command c, byte[] out) {
            if (c == null) {
                return;
            }
            int len = XrealProtocol.buildMcuCommand(out, c.msgId, c.data, c.data.length);
            if (write(mConn, mMcu, out, len) < 0) {
                c.reply.onReply(null);
                return;
            }
            mPending = c;
            mPendingUntilMs = SystemClock.uptimeMillis() + MCU_REPLY_TIMEOUT_MS;
        }

        private void reply(byte[] data) {
            Command c = mPending;
            mPending = null;
            if (c != null) {
                c.reply.onReply(data);
            }
        }

        private void received(byte[] in, int n) {
            int id = XrealProtocol.mcuMsgId(in, n);
            if (id < 0 || id == XrealProtocol.MCU_MSG_HEARTBEAT) {
                return;
            }
            if (mPending != null && id == mPending.msgId) {
                reply(XrealProtocol.mcuData(in, n));
                return;
            }
            int[] button = XrealProtocol.mcuButton(in, n);
            if (button != null) {
                Log.i(TAG, String.format(Locale.US, "xreal: button %d event %d value %d", button[0], button[1], button[2]));
                if (SystemClock.uptimeMillis() >= mButtonsFromMs) {
                    mListener.onButton(button[0], button[1]);
                } else {
                    Log.i(TAG, "xreal: ignored a button press from before connecting");
                }
            } else if (id != XrealProtocol.MCU_MSG_STATUS_A && id != XrealProtocol.MCU_MSG_STATUS_B && mLogged++ < 20) {
                byte[] data = XrealProtocol.mcuData(in, n);
                Log.i(TAG, String.format(Locale.US, "xreal: MCU message 0x%04x: %s", id,
                        XrealProtocol.hex(data, 0, data.length)));
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
