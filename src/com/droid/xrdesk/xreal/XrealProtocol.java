package com.droid.xrdesk.xreal;

import java.util.zip.CRC32;

/**
 * XREAL Air-family USB HID protocol, as used by the xbx a01 / a01+ glasses. Plain Java (no
 * Android dependencies) so it can be unit tested on the JVM.
 *
 * There is no official documentation; every constant here comes from community drivers, cited
 * inline. "nrealAirLinuxDriver" means gitlab.com/wheaney/nrealAirLinuxDriver @61ecd4cc (the
 * xrealInterfaceLibrary submodule of github.com/wheaney/XRLinuxDriver @36e2139d). See
 * NOTES-xreal.md for the full source list.
 */
public final class XrealProtocol {
    private XrealProtocol() {
    }

    // nrealAirLinuxDriver interface_lib/src/hid_ids.c:38-73
    public static final int VENDOR_ID = 0x3318;
    public static final int PID_XBX_A01 = 0x0440;
    public static final int PID_XBX_A01_PLUS = 0x0442;
    public static final int A01_IMU_INTERFACE = 1;
    public static final int A01_MCU_INTERFACE = 0;
    public static final int MAX_PACKET = 64;

    public static boolean isSupported(int vendorId, int productId) {
        return vendorId == VENDOR_ID && (productId == PID_XBX_A01 || productId == PID_XBX_A01_PLUS);
    }

    // ---- IMU interface (nrealAirLinuxDriver interface_lib/src/imu_hid.c) ----

    /** imu_hid.c:18 DEVICE_IMU_MSG_START_IMU_DATA; data 0x01 starts, 0x00 stops (L173-181). */
    public static final int IMU_MSG_START_IMU_DATA = 0x19;
    private static final int IMU_HEAD = 0xAA;      // imu_hid.c:134
    private static final int IMU_DATA_OFFSET = 8;  // head(1) crc(4) len(2) msgid(1), imu_hid.c:118-124

    /**
     * Builds an IMU command frame into {@code out} and returns its length (8 + data length),
     * per imu_hid.c:128-147: [0]=0xAA, [1..4]=CRC32 LE, [5..6]=len LE where len = 3 + data
     * length, [7]=msgid, [8..]=data. The CRC covers {@code len} bytes starting at the len field.
     */
    public static int buildImuCommand(byte[] out, int msgId, byte[] data, int dataLen) {
        int len = 3 + dataLen;
        int total = IMU_DATA_OFFSET + dataLen;
        if (total > out.length || total > MAX_PACKET) {
            throw new IllegalArgumentException("IMU command too long: " + total);
        }
        out[0] = (byte) IMU_HEAD;
        putLe16(out, 5, len);
        out[7] = (byte) msgId;
        if (dataLen > 0) {
            System.arraycopy(data, 0, out, IMU_DATA_OFFSET, dataLen);
        }
        putLe32(out, 1, crc32(out, 5, len));
        return total;
    }

    /** Returns the msgid of an IMU command reply (head 0xAA), or -1 if {@code buf} isn't one. */
    public static int imuReplyMsgId(byte[] buf, int length) {
        if (length < IMU_DATA_OFFSET || (buf[0] & 0xFF) != IMU_HEAD) {
            return -1;
        }
        return buf[7] & 0xFF;
    }

    public static final int SAMPLE = 1;     // gyro values valid
    public static final int INIT = 2;       // stream-start marker (signature AA 53), no values
    public static final int SKIP = 0;       // some other report (e.g. a command reply)
    public static final int MALFORMED = -1; // wrong length

    /**
     * Parses one 64-byte IMU report (imu_hid.c:28-49 layout, 237-294 decoding) into {@code out}.
     * Gyro rates are in degrees per second, in the IMU's raw axes; the timestamp is in ns.
     */
    public static int parseImuReport(byte[] buf, int length, ImuSample out) {
        if (length != MAX_PACKET) {
            return MALFORMED;  // imu_hid.c:241 rejects anything but a full packet
        }
        int s0 = buf[0] & 0xFF;
        int s1 = buf[1] & 0xFF;
        if (s0 == 0xAA && s1 == 0x53) {  // imu_hid.c:244 init packet
            out.timestampNs = le64(buf, 4);
            return INIT;
        }
        if (s0 != 0x01 || s1 != 0x02) {  // imu_hid.c:253
            return SKIP;
        }
        out.timestampNs = le64(buf, 4);       // [4..11] u64 LE
        int mul = le16s(buf, 12);             // [12..13] angular_multiplier, pack16bit_signed
        int div = le32(buf, 14);              // [14..17] angular_divisor, pack32bit_signed
        if (div == 0) {
            return MALFORMED;
        }
        float scale = (float) mul / (float) div;  // imu_hid.c:278-280: raw * mul / div
        out.gx = le24s(buf, 18) * scale;
        out.gy = le24s(buf, 21) * scale;
        out.gz = le24s(buf, 24) * scale;
        return SAMPLE;
    }

    /** Mutable sample, reused to avoid per-packet allocation. */
    public static final class ImuSample {
        public float gx;  // deg/s, raw IMU axes
        public float gy;
        public float gz;
        public long timestampNs;
    }

    // ---- MCU interface (nrealAirLinuxDriver interface_lib/src/mcu_hid.c) ----

    private static final int MCU_HEAD = 0xFD;       // mcu_hid.c:52
    private static final int MCU_DATA_OFFSET = 22;  // head crc len(2) ts(8) msgid(2) reserved(5), mcu_hid.c:64-75
    /** include/mcu_hid.h:47 DEVICE_MCU_MSG_P_HEARTBEAT. */
    public static final int MCU_MSG_HEARTBEAT = 0x1A;
    /** mcu_hid.c:58-59: SDK-version handshake that "enables the IMU stream". */
    public static final int MCU_MSG_SDK_VERSION = 0x31;
    public static final byte[] MCU_SDK_VERSION = {'3', '.', '1', '.', '1'};
    /** mcu_hid.c:60-62. */
    public static final int MCU_HEARTBEAT_INTERVAL_MS = 100;
    public static final int MCU_RESPONSE_TIMEOUT_MS = 250;
    public static final int MCU_RESPONSE_ATTEMPTS = 8;

    private static final byte[] ONE = {0x01, 0x00, 0x00, 0x00};
    /** mcu_hid.c:306-318: bootstrap commands sent before the SDK-version handshake, in order. */
    public static final int[] MCU_BOOTSTRAP_IDS = {0x26, 0x57, 0x12, 0x02, 0x34, 0x35};
    public static final byte[][] MCU_BOOTSTRAP_DATA = {null, null, ONE, ONE, null, null};

    /**
     * Builds an MCU command frame into {@code out} and returns its length (22 + data length),
     * per mcu_hid.c:119-146: [0]=0xFD, [1..4]=CRC32 LE, [5..6]=len LE where len = 17 + data
     * length, [7..14]=timestamp 0, [15..16]=msgid LE, [17..21]=0, [22..]=data.
     */
    public static int buildMcuCommand(byte[] out, int msgId, byte[] data, int dataLen) {
        int len = 17 + dataLen;
        int total = MCU_DATA_OFFSET + dataLen;
        if (total > out.length || total > MAX_PACKET) {
            throw new IllegalArgumentException("MCU command too long: " + total);
        }
        java.util.Arrays.fill(out, 0, MCU_DATA_OFFSET, (byte) 0);
        out[0] = (byte) MCU_HEAD;
        putLe16(out, 5, len);
        putLe16(out, 15, msgId);
        if (dataLen > 0) {
            System.arraycopy(data, 0, out, MCU_DATA_OFFSET, dataLen);
        }
        putLe32(out, 1, crc32(out, 5, len));
        return total;
    }

    /** Heartbeat payload: a little-endian u64 monotonic timestamp in ns (mcu_hid.c:234-239). */
    public static int buildMcuHeartbeat(byte[] out, byte[] scratch8, long monotonicNs) {
        for (int i = 0; i < 8; i++) {
            scratch8[i] = (byte) (monotonicNs >>> (8 * i));
        }
        return buildMcuCommand(out, MCU_MSG_HEARTBEAT, scratch8, 8);
    }

    /**
     * Bootstrap acks don't follow the status-byte convention; any packet with the MCU head
     * counts as an acknowledgement (mcu_hid.c:241-271).
     */
    public static boolean isMcuReply(byte[] buf, int length) {
        return length > 0 && (buf[0] & 0xFF) == MCU_HEAD;
    }

    // ---- Axes ----

    /**
     * Maps raw IMU rates (deg/s) to the viewer's frame, writing {right, up, back} in rad/s into
     * {@code out}: the rotation rate about the display's right axis, its up axis, and its back
     * axis (roll; positive = counter-clockwise as seen by the viewer, the Android/RUB convention).
     * Viewer right = -raw x, up = +raw z, back = +raw y for the XREAL Air, per
     * ar-drivers-rs @56bfa489 src/nreal_air.rs:479-485 (RUB convention, src/lib.rs:115-116; the
     * third component, line 483, is +gyro_y) and measured on a wearer by BladeRunner28/XrealBeam
     * @9daef62c (README, "Pose path and the mount": viewer back (+z) -> body +y). NOT verified for
     * the a01/a01+, whose IMU mounting could differ.
     */
    public static void toViewerRates(float gx, float gy, float gz, float[] out) {
        final float degToRad = (float) (Math.PI / 180.0);
        out[0] = -gx * degToRad;
        out[1] = gz * degToRad;
        out[2] = gy * degToRad;
    }

    // ---- Bytes ----

    static long crc32(byte[] b, int off, int len) {
        CRC32 crc = new CRC32();  // same algorithm as nrealAirLinuxDriver crc32.c:27-102
        crc.update(b, off, len);
        return crc.getValue();
    }

    static void putLe16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    static void putLe32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    static int le16s(byte[] b, int o) {
        return (short) ((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8));
    }

    static int le24s(byte[] b, int o) {
        int v = (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
        return (v << 8) >> 8;  // sign-extend bit 23, as pack24bit_signed (imu_hid.c:211-215)
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[o + i] & 0xFF);
        }
        return v;
    }
}
