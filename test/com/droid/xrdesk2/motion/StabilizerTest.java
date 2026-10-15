package com.droid.xrdesk2.motion;

/** Plain-JVM tests for Stabilizer (run by ./xrdesk2.sh test). */
public final class StabilizerTest {
    private static int sFailures;

    public static void main(String[] args) {
        smallTurnIsCancelled();
        driftsBackToCenter();
        deadbandIgnoresNoise();
        offsetIsClamped();
        tiltMovesVertically();
        if (sFailures > 0) {
            System.out.println("StabilizerTest: " + sFailures + " FAILED");
            System.exit(1);
        }
        System.out.println("StabilizerTest: all passed");
    }

    /** A quick 2 degree turn to the left slides the picture right by about focal * angle. */
    private static void smallTurnIsCancelled() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f);
        float rad = (float) Math.toRadians(2);
        int steps = 50;  // 50 ms at 1 kHz
        for (int i = 0; i < steps; i++) {
            s.update(0f, rad / 0.05f, 0.001f);
        }
        float expected = 2262f * rad;  // ~79 px, minus a little drift
        check("small turn x", s.x() > expected * 0.9f && s.x() <= Math.round(expected), s.x() + " vs " + expected);
        check("small turn y", s.y() == 0, String.valueOf(s.y()));
    }

    /** After the head stops, the offset decays to center within a few time constants. */
    private static void driftsBackToCenter() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f);
        for (int i = 0; i < 50; i++) {
            s.update(0f, 1f, 0.001f);
        }
        int start = s.x();
        for (int i = 0; i < 2500; i++) {  // 2.5 s = 5 time constants
            s.update(0f, 0f, 0.001f);
        }
        check("drift start", start > 50, String.valueOf(start));
        check("drift end", Math.abs(s.x()) <= 1, String.valueOf(s.x()));
    }

    private static void deadbandIgnoresNoise() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0.008f, 0.004f);
        for (int i = 0; i < 1000; i++) {
            s.update(0.003f, -0.003f, 0.001f);
        }
        check("deadband", s.x() == 0 && s.y() == 0, s.x() + "," + s.y());
    }

    private static void offsetIsClamped() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0.008f, 0.004f);
        for (int i = 0; i < 500; i++) {
            s.update(0f, -5f, 0.001f);  // fast turn right
        }
        check("clamp", s.x() == -600, String.valueOf(s.x()));
    }

    /** Tilting up (positive right-axis rate) slides the picture down. */
    private static void tiltMovesVertically() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f);
        for (int i = 0; i < 20; i++) {
            s.update(0.5f, 0f, 0.001f);
        }
        check("tilt", s.y() > 0 && s.x() == 0, s.x() + "," + s.y());
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            sFailures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }
}
