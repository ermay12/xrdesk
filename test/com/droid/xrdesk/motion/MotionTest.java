package com.droid.xrdesk.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain-JVM tests for the head-gesture, roll and coordinate-mapping logic, using synthetic gyro
 * traces (no device needed). Run with ./xrdesk.sh test.
 *
 * Rates follow DesktopActivity's convention: pitch = rad/s about the screen's right axis
 * (positive = tilting up), yaw = rad/s about its up axis (positive = turning left).
 */
public final class MotionTest {
    private static int sFailures;
    private static int sChecks;

    public static void main(String[] args) {
        for (int hz : new int[] {200, 1000}) {
            shakeOpensMenuInBothModes(hz);
            verticalFlicks(hz);
            horizontalFlickCommitsAfterDelay(hz);
            horizontalFlickWithQuickReturnIsCancelled(hz);
            slowTurnsAndWalkingDoNothing(hz);
            nods(hz);
            twoGlancesAreNotAShake(hz);
            slowShakeIsNotAShake(hz);
            singleMonitorDisablesFlicksNotShake(hz);
            cooldownSwallowsReturnMotion(hz);
        }
        monitorStepping();
        rollFilter();
        desktopTransform();
        System.out.println(sChecks + " checks, " + sFailures + " failures");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    // ---- synthetic traces ----

    /** A sampled trace of (pitch, yaw) rates. */
    private static final class Trace {
        final int hz;
        final List<float[]> samples = new ArrayList<>();

        Trace(int hz) {
            this.hz = hz;
        }

        Trace still(double seconds) {
            return add(seconds, t -> 0, t -> 0);
        }

        /** Half-sine pulse on one axis: peak rad/s over the given duration (covers peak*dur*2/pi rad). */
        Trace pulse(boolean pitchAxis, double peak, double seconds) {
            Fn f = t -> peak * Math.sin(Math.PI * t / seconds);
            return pitchAxis ? add(seconds, f, t -> 0) : add(seconds, t -> 0, f);
        }

        Trace add(double seconds, Fn pitch, Fn yaw) {
            int n = (int) Math.round(seconds * hz);
            for (int i = 0; i < n; i++) {
                double t = i / (double) hz;
                samples.add(new float[] {(float) pitch.at(t), (float) yaw.at(t)});
            }
            return this;
        }

        /** Feeds the trace; returns the events with their sample times in ms. */
        List<int[]> run(HeadGestures g) {
            List<int[]> events = new ArrayList<>();
            long dtNs = 1_000_000_000L / hz;
            float dt = 1f / hz;
            for (int i = 0; i < samples.size(); i++) {
                long t = 1_000_000_000L + i * dtNs;  // arbitrary non-zero start
                float[] s = samples.get(i);
                int e = g.update(t, s[0], s[1], dt);
                if (e != HeadGestures.NONE) {
                    events.add(new int[] {e, (int) ((t - 1_000_000_000L) / 1_000_000L)});
                }
            }
            return events;
        }
    }

    private interface Fn {
        double at(double t);
    }

    private static HeadGestures gestures(boolean horizontal) {
        HeadGestures g = new HeadGestures();  // defaults: flick 2.5 rad/s, 12 deg, shake 1.5 rad/s x3
        g.setHorizontal(horizontal);
        return g;
    }

    private static String describe(List<int[]> events) {
        StringBuilder sb = new StringBuilder("[");
        for (int[] e : events) {
            sb.append(new String[] {"NONE", "NEXT", "PREV", "SHAKE"}[e[0]]).append('@').append(e[1]).append("ms ");
        }
        return sb.append(']').toString();
    }

    private static void expect(List<int[]> events, int[] kinds, String what) {
        boolean ok = events.size() == kinds.length;
        for (int i = 0; ok && i < kinds.length; i++) {
            ok = events.get(i)[0] == kinds[i];
        }
        check(ok, what + " -> " + describe(events));
    }

    // ---- gestures ----

    /** A "no" shake: ~2.5 Hz yaw oscillation, peak 4 rad/s, for 1 s. */
    private static Trace shake(int hz, double peak, double freq, double seconds) {
        return new Trace(hz).still(0.2).add(seconds, t -> 0, t -> peak * Math.sin(2 * Math.PI * freq * t)).still(0.5);
    }

    static void shakeOpensMenuInBothModes(int hz) {
        expect(shake(hz, 4, 2.5, 1.0).run(gestures(false)), new int[] {HeadGestures.SHAKE},
                hz + "Hz up/down mode: shake = one SHAKE, no monitor switch");
        expect(shake(hz, 4, 2.5, 1.0).run(gestures(true)), new int[] {HeadGestures.SHAKE},
                hz + "Hz left/right mode: shake = one SHAKE, its first stroke isn't committed as a flick");
        expect(shake(hz, 2.5, 2.0, 1.2).run(gestures(true)), new int[] {HeadGestures.SHAKE},
                hz + "Hz gentler shake (2 Hz, peak 2.5 rad/s) still opens the menu");
        // Starting toward the other side makes no difference.
        expect(shake(hz, -4, 2.5, 1.0).run(gestures(true)), new int[] {HeadGestures.SHAKE},
                hz + "Hz shake starting to the right");
    }

    static void verticalFlicks(int hz) {
        Trace up = new Trace(hz).still(0.2).pulse(true, 5, 0.12).still(0.2).pulse(true, -1.5, 0.4).still(0.5);
        expect(up.run(gestures(false)), new int[] {HeadGestures.NEXT}, hz + "Hz up flick (+ slow return) = NEXT once");
        Trace down = new Trace(hz).still(0.2).pulse(true, -5, 0.12).still(0.2).pulse(true, 1.5, 0.4).still(0.5);
        expect(down.run(gestures(false)), new int[] {HeadGestures.PREVIOUS}, hz + "Hz down flick = PREVIOUS once");
        // The vertical flick fires during the stroke, without the left/right commit delay.
        List<int[]> e = up.run(gestures(false));
        check(!e.isEmpty() && e.get(0)[1] < 200 + 120, hz + "Hz vertical flick fires within its stroke (" + describe(e) + ")");
        expect(up.run(gestures(true)), new int[0], hz + "Hz pitch flick in left/right mode does nothing");
    }

    static void horizontalFlickCommitsAfterDelay(int hz) {
        // Turning right fast = negative yaw = NEXT; then holding still.
        Trace right = new Trace(hz).still(0.2).pulse(false, -5, 0.12).still(0.6);
        List<int[]> e = right.run(gestures(true));
        expect(e, new int[] {HeadGestures.NEXT}, hz + "Hz right flick = NEXT");
        // The stroke drops below 2.5 rad/s at ~200 + 120*(1 - asin(0.5)/pi*2)... ~280 ms; commit >= +150 ms.
        check(!e.isEmpty() && e.get(0)[1] >= 400 && e.get(0)[1] <= 470,
                hz + "Hz commit ~150 ms after the stroke ends (" + describe(e) + ")");
        Trace left = new Trace(hz).still(0.2).pulse(false, 5, 0.12).still(0.6);
        expect(left.run(gestures(true)), new int[] {HeadGestures.PREVIOUS}, hz + "Hz left flick = PREVIOUS");
        expect(right.run(gestures(false)), new int[0], hz + "Hz yaw flick in up/down mode does nothing");
        // A slow return after the commit window doesn't cancel or retrigger.
        Trace back = new Trace(hz).still(0.2).pulse(false, -5, 0.12).still(0.3).pulse(false, 1.2, 0.5).still(0.3);
        expect(back.run(gestures(true)), new int[] {HeadGestures.NEXT}, hz + "Hz right flick then slow return = NEXT once");
    }

    static void horizontalFlickWithQuickReturnIsCancelled(int hz) {
        // Documented trade-off: a fast reversal within the commit window looks like a shake starting.
        Trace t = new Trace(hz).still(0.2).pulse(false, -5, 0.12).still(0.05).pulse(false, 3, 0.15).still(0.6);
        expect(t.run(gestures(true)), new int[0], hz + "Hz right flick with an immediate fast return is cancelled");
    }

    static void slowTurnsAndWalkingDoNothing(int hz) {
        Trace slow = new Trace(hz).still(0.2).add(1.5, t -> 0, t -> 0.8).add(1.5, t -> 0.6, t -> -0.8).still(0.5);
        expect(slow.run(gestures(false)), new int[0], hz + "Hz slow turns, up/down mode");
        expect(slow.run(gestures(true)), new int[0], hz + "Hz slow turns, left/right mode");
        Random r = new Random(7);
        Trace walk = new Trace(hz).add(20, t -> 0.6 * Math.sin(2 * Math.PI * 2 * t) + (r.nextDouble() - 0.5) * 0.3,
                t -> 0.4 * Math.sin(2 * Math.PI * 1 * t + 1) + (r.nextDouble() - 0.5) * 0.3);
        expect(walk.run(gestures(false)), new int[0], hz + "Hz 20 s of walking-like bob + noise, up/down");
        expect(walk.run(gestures(true)), new int[0], hz + "Hz 20 s of walking-like bob + noise, left/right");
        // A brisk look around: one fast turn and back, a few seconds apart.
        Trace look = new Trace(hz).still(0.2).pulse(false, 2.0, 0.4).still(1).pulse(false, -2.0, 0.4).still(1);
        expect(look.run(gestures(true)), new int[0], hz + "Hz looking left and back at 2 rad/s");
    }

    static void nods(int hz) {
        Trace gentle = new Trace(hz).add(3, t -> 1.2 * Math.sin(2 * Math.PI * 1.5 * t), t -> 0);
        expect(gentle.run(gestures(false)), new int[0], hz + "Hz gentle nodding (peak 1.2 rad/s) does nothing");
        expect(gentle.run(gestures(true)), new int[0], hz + "Hz gentle nodding, left/right mode");
        // A fast "yes" (down first) reads as a down flick in up/down mode; the up return is in cooldown.
        Trace fast = new Trace(hz).still(0.2).add(0.5, t -> -4 * Math.sin(2 * Math.PI * 2 * t), t -> 0).still(0.5);
        expect(fast.run(gestures(false)), new int[] {HeadGestures.PREVIOUS},
                hz + "Hz one fast nod = PREVIOUS once (documented)");
        expect(fast.run(gestures(true)), new int[0], hz + "Hz fast nod in left/right mode does nothing");
    }

    static void twoGlancesAreNotAShake(int hz) {
        Trace two = new Trace(hz).still(0.2).pulse(false, 4, 0.2).pulse(false, -4, 0.2).still(0.6);
        List<int[]> e = two.run(gestures(false));
        check(e.isEmpty(), hz + "Hz left-right (2 strokes) is not a shake -> " + describe(e));
    }

    static void slowShakeIsNotAShake(int hz) {
        // Three strokes, but with long pauses between them.
        Trace t = new Trace(hz).still(0.2).pulse(false, 4, 0.2).still(0.5).pulse(false, -4, 0.2).still(0.5)
                .pulse(false, 4, 0.2).still(0.5);
        expect(t.run(gestures(false)), new int[0], hz + "Hz strokes with 0.5 s pauses are not a shake");
    }

    static void singleMonitorDisablesFlicksNotShake(int hz) {
        HeadGestures g = gestures(false);
        g.setFlicksEnabled(false);
        Trace up = new Trace(hz).still(0.2).pulse(true, 5, 0.12).still(0.5);
        expect(up.run(g), new int[0], hz + "Hz flicks disabled: no switch");
        HeadGestures g2 = gestures(true);
        g2.setFlicksEnabled(false);
        expect(shake(hz, 4, 2.5, 1.0).run(g2), new int[] {HeadGestures.SHAKE}, hz + "Hz flicks disabled: shake still works");
    }

    static void cooldownSwallowsReturnMotion(int hz) {
        // Up flick, then a fast down return 100 ms later: only one event.
        Trace t = new Trace(hz).still(0.2).pulse(true, 5, 0.12).still(0.1).pulse(true, -5, 0.12).still(0.5);
        expect(t.run(gestures(false)), new int[] {HeadGestures.NEXT}, hz + "Hz fast return inside the cooldown is ignored");
        // A second flick after the cooldown counts again.
        Trace two = new Trace(hz).still(0.2).pulse(true, 5, 0.12).still(1.0).pulse(true, 5, 0.12).still(0.3);
        expect(two.run(gestures(false)), new int[] {HeadGestures.NEXT, HeadGestures.NEXT}, hz + "Hz two flicks 1 s apart");
        // Shaking for 2.5 s: the menu toggles at most once per cooldown, not on every stroke.
        List<int[]> e = shake(hz, 4, 2.5, 2.5).run(gestures(false));
        check(e.size() >= 1 && e.size() <= 2, hz + "Hz a long shake toggles the menu once or twice -> " + describe(e));
    }

    static void monitorStepping() {
        check(HeadGestures.step(0, 2, 1) == 1 && HeadGestures.step(1, 2, 1) == 0, "2 monitors: next toggles");
        check(HeadGestures.step(0, 2, -1) == 1, "2 monitors: previous toggles");
        check(HeadGestures.step(2, 3, 1) == 0, "3 monitors: next wraps to the first");
        check(HeadGestures.step(0, 3, -1) == 2, "3 monitors: previous wraps to the last");
        check(HeadGestures.step(1, 4, 1) == 2 && HeadGestures.step(1, 4, -1) == 0, "4 monitors: steps");
        check(HeadGestures.step(0, 1, 1) == 0 && HeadGestures.step(0, 1, -1) == 0, "1 monitor: stays");
    }

    // ---- roll ----

    static void rollFilter() {
        RollFilter f = new RollFilter();
        float snap = RollFilter.snapFor(2182, 972);
        f.configure(0.004f, 0.5f, (float) Math.toRadians(15), snap);
        check(snap > 0.0003f && snap < 0.0006f, "snap for 2182x972 keeps corners within 0.5 px (" + snap + ")");
        f.update(0f, 0.005f);
        check(f.output(0.008f) == 0f, "still = exactly 0");
        // Noise below the deadband never rotates.
        Random r = new Random(3);
        for (int i = 0; i < 4000; i++) {
            f.update((r.nextFloat() - 0.5f) * 0.007f, 0.005f);
            if (f.output(0.008f) != 0f) {
                check(false, "noise rotated the desktop");
                break;
            }
        }
        check(true, "deadband noise leaves the angle at exactly 0");
        // A 10-degree head roll over 0.2 s: the desktop follows (minus some leak), clockwise for a
        // counter-clockwise head roll.
        double total = Math.toRadians(10);
        for (int i = 0; i < 40; i++) {
            f.update((float) (total / 0.2), 0.005f);
        }
        float a = f.angle();
        check(a > Math.toRadians(7) && a < Math.toRadians(10), "follows a quick 10-degree roll (" + Math.toDegrees(a) + ")");
        // Holding still: it levels out and snaps back to exactly 0 within a few seconds.
        int steps = 0;
        while (f.output(0.008f) != 0f && steps < 2000) {
            f.update(0f, 0.005f);
            steps++;
        }
        check(f.output(0.008f) == 0f && f.angle() == 0f, "levels out to exactly 0 (" + steps * 5 + " ms)");
        check(steps * 5 < 5000, "levels out within 5 s");
        // A long roll is clamped.
        for (int i = 0; i < 1000; i++) {
            f.update(2f, 0.005f);
        }
        check(Math.abs(f.angle() - Math.toRadians(15)) < 1e-4, "clamped to the max angle");
        // Output is quantized to the snap size.
        float out = f.output(0f);
        check(Math.abs(out / snap - Math.round(out / snap)) < 1e-3, "output quantized to the snap step");
        f.reset();
        check(f.angle() == 0f && f.output(0.008f) == 0f, "reset");
    }

    // ---- touch mapping ----

    static void desktopTransform() {
        float[] p = new float[2];
        float[] q = new float[2];
        int w = 2182;
        int h = 972;
        DesktopTransform.toDesktop(500, 300, 121, 54, w, h, 0f, p);
        check(p[0] == 379 && p[1] == 246, "no rotation: plain offset");
        // The center maps to the center at any angle.
        for (double deg : new double[] {-15, -3, 0.1, 7, 15}) {
            float ang = (float) Math.toRadians(deg);
            DesktopTransform.toDesktop(121 + w / 2f, 54 + h / 2f, 121, 54, w, h, ang, p);
            check(Math.abs(p[0] - w / 2f) < 1e-3 && Math.abs(p[1] - h / 2f) < 1e-3, "center fixed at " + deg + " deg");
        }
        // Round trip at several angles and points.
        Random r = new Random(11);
        double worst = 0;
        for (int i = 0; i < 5000; i++) {
            float ang = (float) Math.toRadians((r.nextDouble() - 0.5) * 30);
            float dx = r.nextFloat() * w;
            float dy = r.nextFloat() * h;
            float left = 100 + r.nextFloat() * 50;
            float top = 40 + r.nextFloat() * 30;
            DesktopTransform.toScreen(dx, dy, left, top, w, h, ang, p);
            DesktopTransform.toDesktop(p[0], p[1], left, top, w, h, ang, q);
            worst = Math.max(worst, Math.hypot(q[0] - dx, q[1] - dy));
        }
        check(worst < 0.01, "toScreen/toDesktop round trip within 0.01 px (worst " + worst + ")");
        // Positive angle = clockwise on screen (y down): the desktop's right-middle edge moves down.
        float ang = (float) Math.toRadians(10);
        DesktopTransform.toScreen(w, h / 2f, 0, 0, w, h, ang, p);
        check(p[1] > h / 2f + 100, "positive angle turns the desktop clockwise (right edge moves down)");
        // layerTransform agrees with toScreen for the buffer origin and axes.
        float[] m = new float[6];
        DesktopTransform.layerTransform(121, 54, w, h, ang, m);
        DesktopTransform.toScreen(0, 0, 121, 54, w, h, ang, p);
        check(Math.abs(m[4] - p[0]) < 1e-3 && Math.abs(m[5] - p[1]) < 1e-3, "layer position = screen position of pixel (0,0)");
        DesktopTransform.toScreen(100, 0, 121, 54, w, h, ang, q);
        check(Math.abs(m[4] + m[0] * 100 - q[0]) < 1e-2 && Math.abs(m[5] + m[1] * 100 - q[1]) < 1e-2,
                "x' = dsdx*x and y' = dtdx*x along the buffer x axis");
        DesktopTransform.toScreen(0, 100, 121, 54, w, h, ang, q);
        check(Math.abs(m[4] + m[2] * 100 - q[0]) < 1e-2 && Math.abs(m[5] + m[3] * 100 - q[1]) < 1e-2,
                "x' = dtdy*y and y' = dsdy*y along the buffer y axis");
        DesktopTransform.layerTransform(121, 54, w, h, 0f, m);
        check(m[0] == 1 && m[1] == 0 && m[2] == 0 && m[3] == 1 && m[4] == 121 && m[5] == 54, "angle 0 = identity at (left, top)");
    }

    // ---- helpers ----

    private static void check(boolean ok, String what) {
        sChecks++;
        if (!ok) {
            sFailures++;
            System.out.println("FAIL: " + what);
        }
    }
}
