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
        smoothStartsSlowerThanExponential();
        curvesMatchHalfwayTime();
        curvesDoNotOvershoot();
        curvesFollowHeadDuringTurn();
        linearIsConstantSpeed();
        accelerateSpeedsUpThenStops();
        curvesHeadStraightForCenter();
        if (sFailures > 0) {
            System.out.println("StabilizerTest: " + sFailures + " FAILED");
            System.exit(1);
        }
        System.out.println("StabilizerTest: all passed");
    }

    /** A quick 2 degree turn to the left slides the picture right by about focal * angle. */
    private static void smallTurnIsCancelled() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f, Stabilizer.EXPONENTIAL);
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
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f, Stabilizer.EXPONENTIAL);
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
        s.configure(2262f, 0.5f, 600f, 0.008f, 0.004f, Stabilizer.EXPONENTIAL);
        for (int i = 0; i < 1000; i++) {
            s.update(0.003f, -0.003f, 0.001f);
        }
        check("deadband", s.x() == 0 && s.y() == 0, s.x() + "," + s.y());
    }

    private static void offsetIsClamped() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0.008f, 0.004f, Stabilizer.EXPONENTIAL);
        for (int i = 0; i < 500; i++) {
            s.update(0f, -5f, 0.001f);  // fast turn right
        }
        check("clamp", s.x() == -600, String.valueOf(s.x()));
    }

    /** Tilting up (positive right-axis rate) slides the picture down. */
    private static void tiltMovesVertically() {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 0.5f, 600f, 0f, 0.004f, Stabilizer.EXPONENTIAL);
        for (int i = 0; i < 20; i++) {
            s.update(0.5f, 0f, 0.001f);
        }
        check("tilt", s.y() > 0 && s.x() == 0, s.x() + "," + s.y());
    }

    /**
     * After a quick turn, the smooth curve moves back much less than the exponential one at first
     * (theory for drift 1 s: 2.5% vs 9.5% of the way after 0.1 s, 16% vs 26% after 0.3 s).
     */
    private static void smoothStartsSlowerThanExponential() {
        Stabilizer smooth = turned(Stabilizer.SMOOTH, 10);
        Stabilizer exp = turned(Stabilizer.EXPONENTIAL, 10);
        int start = exp.x();
        still(smooth, 100);
        still(exp, 100);
        int smooth100 = start - smooth.x();
        int exp100 = start - exp.x();
        still(smooth, 200);
        still(exp, 200);
        int smooth300 = start - smooth.x();
        int exp300 = start - exp.x();
        check("smooth slow start 0.1 s", smooth100 * 2 < exp100, smooth100 + " vs " + exp100 + " px");
        check("smooth slow start 0.3 s", smooth300 * 4 < exp300 * 3, smooth300 + " vs " + exp300 + " px");
    }

    /** From the largest offset, every curve gets halfway back in about drift * ln 2. */
    private static void curvesMatchHalfwayTime() {
        String[] names = {"smooth", "exp", "linear", "accel"};
        for (int curve = 0; curve < names.length; curve++) {
            Stabilizer s = turned(curve, MAX_DEGREES);
            int start = s.x();
            int ms = 0;
            while (s.x() > start / 2 && ms < 10000) {
                still(s, 1);
                ms++;
            }
            float expected = 1000f * (float) Math.log(2);  // drift = 1 s
            check("halfway " + names[curve], Math.abs(ms - expected) < 0.1f * expected, ms + " ms vs " + expected);
        }
    }

    /** From rest, each curve settles exactly at center without crossing it. */
    private static void curvesDoNotOvershoot() {
        for (int curve = 0; curve < 4; curve++) {
            Stabilizer s = turned(curve, 2);
            int min = Integer.MAX_VALUE;
            for (int i = 0; i < 8000; i++) {
                still(s, 1);
                min = Math.min(min, s.x());
            }
            check("settles " + curve, Math.abs(s.x()) <= 1, String.valueOf(s.x()));
            check("no overshoot " + curve, min >= -1, String.valueOf(min));
        }
    }

    /**
     * A quick turn is still cancelled almost fully by every curve except linear, whose constant
     * drift speed (433 px/s at drift 1 s, max 600 px) also takes back 22 px during the 50 ms turn.
     */
    private static void curvesFollowHeadDuringTurn() {
        float expected = 2262f * (float) Math.toRadians(2);
        for (int curve = 0; curve < 4; curve++) {
            Stabilizer s = turned(curve, 2);
            float want = curve == Stabilizer.LINEAR ? expected - 600f / (2f * 0.693147f) * 0.05f : expected;
            check("turn " + curve, Math.abs(s.x() - want) <= Math.max(2f, 0.05f * want), s.x() + " vs " + want);
        }
    }

    /** Linear moves the same distance in every 100 ms until it reaches center, then stops. */
    private static void linearIsConstantSpeed() {
        Stabilizer s = turned(Stabilizer.LINEAR, MAX_DEGREES);
        int a = s.x();
        still(s, 100);
        int b = s.x();
        still(s, 100);
        int c = s.x();
        check("linear constant", Math.abs((a - b) - (b - c)) <= 1, (a - b) + " then " + (b - c) + " px");
        still(s, 2000);
        check("linear stops", s.x() == 0, String.valueOf(s.x()));
    }

    /** Accelerate moves farther in each successive 100 ms, then stops dead at center. */
    private static void accelerateSpeedsUpThenStops() {
        Stabilizer s = turned(Stabilizer.ACCELERATE, MAX_DEGREES);
        int a = s.x();
        still(s, 100);
        int b = s.x();
        still(s, 100);
        int c = s.x();
        check("accel speeds up", (b - c) > 2 * (a - b), (a - b) + " then " + (b - c) + " px");
        int ms = 0;
        while (s.x() != 0 && ms < 5000) {
            int before = s.x();
            still(s, 10);
            ms += 10;
            if (s.x() == 0) {
                check("accel sudden stop", before > 5, "last step from " + before + " px");
            }
        }
        still(s, 500);
        check("accel stays", s.x() == 0, String.valueOf(s.x()));
    }

    /** After a diagonal turn, every curve comes back along the line to center, not at 45 degrees. */
    private static void curvesHeadStraightForCenter() {
        String[] names = {"smooth", "exp", "linear", "accel"};
        for (int curve = 0; curve < names.length; curve++) {
            Stabilizer s = new Stabilizer();
            s.configure(2262f, 1f, 600f, 0f, 0.004f, curve);
            for (int i = 0; i < 50; i++) {
                s.update(0.8f, 3.2f, 0.001f);  // mostly left, a little up: about 4:1
            }
            int x0 = s.x();
            int y0 = s.y();
            float r0 = (float) Math.hypot(x0, y0);
            float worst = 0f;
            for (int i = 0; i < 3000 && s.x() > 10; i++) {
                s.update(0f, 0f, 0.001f);
                // Distance from the line through center and the starting point.
                worst = Math.max(worst, Math.abs(s.x() * y0 - s.y() * x0) / r0);
            }
            check("straight " + names[curve], worst <= 1.5f, "strays " + worst + " px from (" + x0 + "," + y0 + ")");
        }
    }

    private static final float MAX_DEGREES = (float) Math.toDegrees(600 / 2262.0);

    /** A Stabilizer (drift 1 s, max 600 px, no look-ahead) right after a quick 50 ms turn to the left. */
    private static Stabilizer turned(int curve, float degrees) {
        Stabilizer s = new Stabilizer();
        s.configure(2262f, 1f, 600f, 0f, 0.004f, curve);
        float rad = (float) Math.toRadians(degrees);
        for (int i = 0; i < 50; i++) {
            s.update(0f, rad / 0.05f, 0.001f);
        }
        return s;
    }

    private static void still(Stabilizer s, int ms) {
        for (int i = 0; i < ms; i++) {
            s.update(0f, 0f, 0.001f);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            sFailures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }
}
