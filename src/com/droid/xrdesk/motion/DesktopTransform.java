package com.droid.xrdesk.motion;

/**
 * Where a desktop is drawn and how screen points map back into it (plain Java). The desktop
 * buffer (w x h) is drawn 1:1 with its unrotated top-left at (left, top), optionally rotated by
 * {@code angle} radians (positive = clockwise on screen, y down) about its own center, so the
 * center stays at (left + w/2, top + h/2).
 *
 * <p>Forward: screen = R(angle) * (desktop - c) + (left, top) + c, with
 * R = [cos -sin; sin cos] and c = (w/2, h/2) — the same matrix android.graphics.Matrix builds for
 * setRotate(degrees, w/2, h/2) followed by postTranslate(left, top).
 */
public final class DesktopTransform {
    private DesktopTransform() {
    }

    /** Screen point to desktop pixel. */
    public static void toDesktop(float sx, float sy, float left, float top, int w, int h, float angle,
            float[] out) {
        float cx = w / 2f;
        float cy = h / 2f;
        if (angle == 0f) {
            out[0] = sx - left;
            out[1] = sy - top;
            return;
        }
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float dx = sx - (left + cx);
        float dy = sy - (top + cy);
        out[0] = c * dx + s * dy + cx;   // R^T
        out[1] = -s * dx + c * dy + cy;
    }

    /** Desktop pixel to screen point. */
    public static void toScreen(float dx, float dy, float left, float top, int w, int h, float angle,
            float[] out) {
        float cx = w / 2f;
        float cy = h / 2f;
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float x = dx - cx;
        float y = dy - cy;
        out[0] = c * x - s * y + left + cx;
        out[1] = s * x + c * y + top + cy;
    }

    /**
     * The layer transform as {dsdx, dtdx, dtdy, dsdy, x, y}: the 2x2 part in the order of
     * SurfaceControl.Transaction.setMatrix(sc, dsdx, dtdx, dtdy, dsdy), which maps
     * x' = dsdx*x + dtdy*y and y' = dtdx*x + dsdy*y, then the layer position.
     */
    public static void layerTransform(float left, float top, int w, int h, float angle, float[] out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float cx = w / 2f;
        float cy = h / 2f;
        out[0] = c;
        out[1] = s;
        out[2] = -s;
        out[3] = c;
        out[4] = left + cx - (c * cx - s * cy);
        out[5] = top + cy - (s * cx + c * cy);
    }
}
