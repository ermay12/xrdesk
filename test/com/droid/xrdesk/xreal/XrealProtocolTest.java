package com.droid.xrdesk.xreal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Plain-JVM tests for the XREAL protocol code (no device needed). Run with ./xrdesk.sh test.
 *
 * Independent references used:
 *  - Command frames recorded from a real XREAL Air USB trace: BladeRunner28/XrealBeam @9daef62c
 *    README.md:283-288.
 *  - Command frames computed with Python's zlib.crc32 (independent CRC implementation).
 *  - A second packet parser written here in the style of ar-drivers-rs @56bfa489
 *    src/nreal_air.rs:469-485 (sequential little-endian reads), compared on random packets.
 */
public final class XrealProtocolTest {
    private static int sFailures;
    private static int sChecks;

    public static void main(String[] args) {
        commandFramesMatchRealUsbTrace();
        commandFramesMatchPythonZlib();
        crcKnownVector();
        parsesDocumentedLayout();
        parserMatchesIndependentReader();
        rejectsMalformedAndGarbage();
        viewerMapping();
        gyroOffsetConvergesOnlyWhenStill();
        rateRingOrderOverflowAndWrap();
        System.out.println(sChecks + " checks, " + sFailures + " failures");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    // ---- helpers ----

    private static void check(boolean ok, String what) {
        sChecks++;
        if (!ok) {
            sFailures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void near(double a, double b, double tol, String what) {
        check(Math.abs(a - b) <= tol, what + " expected " + b + " got " + a);
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    // ---- tests ----

    static void commandFramesMatchRealUsbTrace() {
        byte[] out = new byte[64];
        int n = XrealProtocol.buildImuCommand(out, 0x19, new byte[]{0x00}, 1);
        check(hex(out, n).equals("aa53e126350400" + "1900"), "STOP frame vs USB trace: " + hex(out, n));
        n = XrealProtocol.buildImuCommand(out, 0x19, new byte[]{0x01}, 1);
        check(hex(out, n).equals("aac5d121420400" + "1901"), "START frame vs USB trace: " + hex(out, n));
        n = XrealProtocol.buildImuCommand(out, 0x1A, new byte[0], 0);
        check(hex(out, n).equals("aa319e650003001a"), "GET_STATIC_ID vs USB trace: " + hex(out, n));
        n = XrealProtocol.buildImuCommand(out, 0x14, new byte[0], 0);
        check(hex(out, n).equals("aa36b3dde7030014"), "GET_CAL_LEN vs USB trace: " + hex(out, n));
        n = XrealProtocol.buildImuCommand(out, 0x15, new byte[0], 0);
        check(hex(out, n).equals("aaa083da90030015"), "CAL_NEXT_SEG vs USB trace: " + hex(out, n));
    }

    static void commandFramesMatchPythonZlib() {
        byte[] out = new byte[64];
        int n = XrealProtocol.buildMcuCommand(out, 0x26, null, 0);
        check(hex(out, n).equals("fd25af75f51100000000000000000026000000000000"), "MCU 0x26: " + hex(out, n));
        n = XrealProtocol.buildMcuCommand(out, 0x12, new byte[]{1, 0, 0, 0}, 4);
        check(hex(out, n).equals("fd2d4ae5c9150000000000000000001200000000000001000000"), "MCU 0x12: " + hex(out, n));
        n = XrealProtocol.buildMcuCommand(out, XrealProtocol.MCU_MSG_SDK_VERSION, XrealProtocol.MCU_SDK_VERSION,
                XrealProtocol.MCU_SDK_VERSION.length);
        check(hex(out, n).equals("fda047bf251600000000000000000031000000000000332e312e31"), "MCU SDK: " + hex(out, n));
        n = XrealProtocol.buildMcuHeartbeat(out, new byte[8], 0x0102030405060708L);
        check(hex(out, n).equals("fd4dfadbdb190000000000000000001a0000000000000807060504030201"), "MCU HB: " + hex(out, n));
        // Bootstrap order and payloads are the reference's (mcu_hid.c:306-318).
        check(java.util.Arrays.equals(XrealProtocol.MCU_BOOTSTRAP_IDS, new int[]{0x26, 0x57, 0x12, 0x02, 0x34, 0x35}),
                "bootstrap ids");
        // A frame the builder must refuse rather than overflow 64 bytes.
        boolean threw = false;
        try {
            XrealProtocol.buildMcuCommand(out, 0x31, new byte[43], 43);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "oversized MCU command rejected");
        check(XrealProtocol.isMcuReply(new byte[]{(byte) 0xFD}, 1), "MCU reply head");
        check(!XrealProtocol.isMcuReply(new byte[]{(byte) 0xAA}, 1), "not an MCU reply");
        check(!XrealProtocol.isMcuReply(new byte[]{(byte) 0xFD}, -1), "failed read isn't a reply");
    }

    static void crcKnownVector() {
        byte[] s = "123456789".getBytes();
        check(XrealProtocol.crc32(s, 0, s.length) == 0xCBF43926L, "CRC-32 check value");
    }

    /** Builds a sensor packet per imu_hid.c:28-49. */
    static byte[] packet(long ts, int mul, int div, int gx, int gy, int gz) {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x01).put((byte) 0x02);
        b.putShort((short) 0x1234);  // temperature
        b.putLong(ts);
        b.putShort((short) mul);
        b.putInt(div);
        put24(b, gx);
        put24(b, gy);
        put24(b, gz);
        // accel/mag/checksum/padding left as zero; the gyro parser must not depend on them
        return b.array();
    }

    static void put24(ByteBuffer b, int v) {
        b.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
    }

    static void parsesDocumentedLayout() {
        XrealProtocol.ImuSample s = new XrealProtocol.ImuSample();
        byte[] p = packet(123456789012345L, 1, 16384, 16384 * 90, -16384 * 45, 0x7FFFFF);
        check(XrealProtocol.parseImuReport(p, 64, s) == XrealProtocol.SAMPLE, "sample recognized");
        near(s.gx, 90.0, 1e-3, "gx deg/s");
        near(s.gy, -45.0, 1e-3, "gy deg/s");
        near(s.gz, 8388607.0 / 16384.0, 1e-3, "gz max positive 24-bit");
        check(s.timestampNs == 123456789012345L, "timestamp ns");
        p = packet(1, -2, 1000, -0x800000, 1, -1);
        XrealProtocol.parseImuReport(p, 64, s);
        near(s.gx, -8388608.0 * -2 / 1000, 1e-1, "most negative 24-bit, negative multiplier");
        near(s.gy, -0.002, 1e-6, "small value");
        near(s.gz, 0.002, 1e-6, "minus one");
        byte[] init = new byte[64];
        init[0] = (byte) 0xAA;
        init[1] = 0x53;
        check(XrealProtocol.parseImuReport(init, 64, s) == XrealProtocol.INIT, "init frame");
    }

    /** Independent sequential reader, as ar-drivers-rs does it. */
    static double[] referenceParse(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        b.position(4);  // signature + temperature
        b.getLong();  // timestamp, compared separately as a long
        double mul = b.getShort();
        double div = b.getInt();
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            int v = (b.get() & 0xFF) | ((b.get() & 0xFF) << 8) | (b.get() << 16);  // signed top byte
            r[i] = v * mul / div;
        }
        return r;
    }

    static long referenceTimestamp(byte[] p) {
        return ByteBuffer.wrap(p, 4, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    static void parserMatchesIndependentReader() {
        Random rnd = new Random(42);
        XrealProtocol.ImuSample s = new XrealProtocol.ImuSample();
        int compared = 0;
        for (int i = 0; i < 20000; i++) {
            byte[] p = new byte[64];
            rnd.nextBytes(p);
            p[0] = 0x01;
            p[1] = 0x02;
            int kind = XrealProtocol.parseImuReport(p, 64, s);
            int div = ByteBuffer.wrap(p, 14, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (div == 0) {
                check(kind == XrealProtocol.MALFORMED, "zero divisor rejected");
                continue;
            }
            double[] r = referenceParse(p);
            double tol = 1e-5 * Math.max(1.0, Math.abs(r[0]) + Math.abs(r[1]) + Math.abs(r[2]));
            if (Math.abs(s.gx - r[0]) > tol || Math.abs(s.gy - r[1]) > tol || Math.abs(s.gz - r[2]) > tol
                    || s.timestampNs != referenceTimestamp(p)) {
                check(false, "random packet " + i + " differs from reference");
                return;
            }
            compared++;
        }
        check(compared > 19000, "compared random packets: " + compared);
    }

    static void rejectsMalformedAndGarbage() {
        XrealProtocol.ImuSample s = new XrealProtocol.ImuSample();
        byte[] p = packet(1, 1, 1, 1, 1, 1);
        check(XrealProtocol.parseImuReport(p, 63, s) == XrealProtocol.MALFORMED, "short packet");
        check(XrealProtocol.parseImuReport(p, 0, s) == XrealProtocol.MALFORMED, "empty read");
        check(XrealProtocol.parseImuReport(p, -1, s) == XrealProtocol.MALFORMED, "failed read");
        byte[] reply = new byte[64];
        XrealProtocol.buildImuCommand(reply, 0x19, new byte[]{1}, 1);
        check(XrealProtocol.parseImuReport(reply, 64, s) == XrealProtocol.SKIP, "command reply skipped");
        check(XrealProtocol.imuReplyMsgId(reply, 64) == 0x19, "reply msgid");
        check(XrealProtocol.imuReplyMsgId(p, 64) == -1, "sensor packet isn't a reply");
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            byte[] g = new byte[64];
            rnd.nextBytes(g);
            int len = rnd.nextInt(70) - 3;
            try {
                XrealProtocol.parseImuReport(g, Math.min(len, 64), s);
                XrealProtocol.imuReplyMsgId(g, Math.max(0, Math.min(len, 64)));
            } catch (RuntimeException e) {
                check(false, "garbage threw " + e);
                return;
            }
        }
        check(true, "20000 garbage packets without exceptions");
    }

    static void viewerMapping() {
        float[] v = new float[3];
        XrealProtocol.toViewerRates(10f, 0f, 0f, v);
        near(v[0], -Math.toRadians(10), 1e-6, "right = -raw x");
        near(v[1], 0, 1e-9, "x doesn't leak into up");
        near(v[2], 0, 1e-9, "x doesn't leak into roll");
        XrealProtocol.toViewerRates(0f, 0f, 10f, v);
        near(v[1], Math.toRadians(10), 1e-6, "up = +raw z");
        near(v[0], 0, 1e-9, "z doesn't leak into right");
        near(v[2], 0, 1e-9, "z doesn't leak into roll");
        XrealProtocol.toViewerRates(0f, 25f, 0f, v);
        near(v[2], Math.toRadians(25), 1e-6, "roll (back axis) = +raw y");
        near(v[0], 0, 1e-9, "raw y doesn't leak into right");
        near(v[1], 0, 1e-9, "raw y doesn't leak into up");
    }

    static void gyroOffsetConvergesOnlyWhenStill() {
        GyroOffset o = new GyroOffset();
        float[] g = new float[3];
        float dt = 0.001f;
        // Still with a 1 deg/s bias on x for 60 s: within the first 5 s nothing changes.
        for (int i = 0; i < 4000; i++) {
            g[0] = 1f; g[1] = -0.5f; g[2] = 0.2f;
            o.update(g, dt);
        }
        near(o.offsetX(), 0, 1e-9, "no correction before the 5 s timeout");
        for (int i = 0; i < 56000; i++) {
            g[0] = 1f; g[1] = -0.5f; g[2] = 0.2f;
            o.update(g, dt);
        }
        near(o.offsetX(), 1.0, 0.01, "bias x learned");
        near(o.offsetY(), -0.5, 0.01, "bias y learned");
        near(g[0], 0, 0.01, "corrected output ~0");
        // Motion above 3 deg/s must neither change the offset nor be filtered away.
        float before = o.offsetX();
        for (int i = 0; i < 10000; i++) {
            g[0] = 50f; g[1] = 0f; g[2] = 0f;
            o.update(g, dt);
        }
        near(o.offsetX(), before, 1e-9, "offset frozen while moving");
        near(g[0], 50f - before, 1e-4, "motion passes through");
    }

    static void rateRingOrderOverflowAndWrap() {
        RateRing r = new RateRing(8);
        for (int i = 0; i < 8; i++) {
            check(r.put(i, i, -i, 2 * i), "put " + i);
        }
        check(!r.put(99, 0, 0, 0), "full ring drops");
        check(r.dropped() == 1, "drop counted");
        final long[] expect = {0};
        int n = r.drain((t, right, up, roll) -> {
            check(t == expect[0] && right == t && up == -t && roll == 2 * t, "order " + t);
            expect[0]++;
        });
        check(n == 8, "drained 8");
        // Many wraps past int-sized indices aren't practical; exercise a few thousand wraps.
        long next = 0;
        final long[] seen = {0};
        for (int round = 0; round < 5000; round++) {
            for (int i = 0; i < 5; i++) {
                r.put(next++, 0, 0, 0);
            }
            r.drain((t, right, up, roll) -> {
                if (t != seen[0]) {
                    check(false, "wrap order");
                }
                seen[0]++;
            });
        }
        check(seen[0] == next, "all wrapped samples delivered in order");
        r.put(1, 1, 1, 1);
        r.clear();
        check(r.drain((t, a, b, c) -> { }) == 0, "clear empties");
        boolean threw = false;
        try {
            new RateRing(6);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "non power of two rejected");
    }
}
