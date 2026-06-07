package com.example.loomoremotepro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Visual joystick widget used by the TcpPro phone app.
 *
 * This class only turns touch input into normalized coordinates. It does not know
 * anything about TCP, speed limits, or robot behavior; MainActivity handles that.
 */
public class JoystickView extends View {
    private static final int INVALID_POINTER_ID = -1;
    // Some devices occasionally emit ACTION_CANCEL during layout or gesture
    // transitions. A small delay avoids dropping control because of a brief glitch.
    private static final long CANCEL_RESET_GRACE_MS = 300L;

    public interface OnJoystickListener {
        void onJoystick(float normalizedX, float normalizedY, boolean active);
    }

    // ── Colour constants ─────────────────────────────────
    private static final int COL_BG         = Color.parseColor("#0D1117");
    private static final int COL_BASE_FILL  = Color.parseColor("#161B22");
    private static final int COL_BORDER     = Color.parseColor("#30363D");
    private static final int COL_GUIDE      = Color.parseColor("#21262D");
    private static final int COL_GUIDE_MID  = Color.parseColor("#272C35");
    private static final int COL_KNOB_IDLE  = Color.parseColor("#1F6FEB");
    private static final int COL_KNOB_MID   = Color.parseColor("#D29922");
    private static final int COL_KNOB_FULL  = Color.parseColor("#F85149");
    private static final int COL_GLOW       = Color.parseColor("#58A6FF");
    private static final int COL_TRAIL      = Color.parseColor("#1F6FEB");
    private static final int COL_TICK       = Color.parseColor("#30363D");
    private static final int COL_LABEL      = Color.parseColor("#484F58");

    // ── Paint objects ────────────────────────────────────
    private final Paint baseFillPaint    = mkPaint(Paint.Style.FILL, COL_BASE_FILL, 0);
    private final Paint baseBorderPaint  = mkPaint(Paint.Style.STROKE, COL_BORDER, 2f);
    private final Paint crosshairPaint   = mkPaint(Paint.Style.STROKE, COL_GUIDE, 1f);
    private final Paint midRingPaint     = mkPaint(Paint.Style.STROKE, COL_GUIDE_MID, 1f);
    private final Paint outerTickPaint   = mkPaint(Paint.Style.STROKE, COL_TICK, 1.5f);
    private final Paint trailPaint       = mkPaint(Paint.Style.STROKE, COL_TRAIL, 2f);
    private final Paint knobPaint        = mkPaint(Paint.Style.FILL, COL_KNOB_IDLE, 0);
    private final Paint knobGlowPaint    = mkPaint(Paint.Style.STROKE, COL_GLOW, 2.5f);
    private final Paint labelPaint       = mkPaint(Paint.Style.FILL, COL_LABEL, 0);
    private final Paint activeGlowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float centerX, centerY;
    private float baseRadius, knobRadius;
    private float knobX, knobY;
    private boolean isActive = false;
    private int activePointerId = INVALID_POINTER_ID;
    private OnJoystickListener listener;
    private final Runnable cancelResetRunnable = this::resetStick;

    public JoystickView(Context context) { super(context); init(); }
    public JoystickView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        midRingPaint.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));
        trailPaint.setAlpha(100);
        labelPaint.setTextSize(24f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        activeGlowPaint.setStyle(Paint.Style.STROKE);
        activeGlowPaint.setStrokeWidth(6f);
    }

    public void setOnJoystickListener(OnJoystickListener listener) {
        this.listener = listener;
    }

    public void resetStick() {
        removeCallbacks(cancelResetRunnable);
        knobX = centerX;
        knobY = centerY;
        isActive = false;
        activePointerId = INVALID_POINTER_ID;
        invalidate();
        // Reset always reports a centered, inactive stick so the Activity can send STOP.
        if (listener != null) listener.onJoystick(0f, 0f, false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX    = w / 2f;
        centerY    = h / 2f;
        baseRadius = Math.min(w, h) * 0.38f;
        knobRadius = baseRadius * 0.30f;
        knobX      = centerX;
        knobY      = centerY;
        labelPaint.setTextSize(baseRadius * 0.08f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(COL_BG);

        float displacement = displacement();

        // ── Base disc ──
        canvas.drawCircle(centerX, centerY, baseRadius, baseFillPaint);

        // ── Tick marks at 25%, 50%, 75% ──
        drawTickRing(canvas, 0.25f, 16);
        drawTickRing(canvas, 0.50f, 24);
        drawTickRing(canvas, 0.75f, 16);

        // ── Crosshair axis lines ──
        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, crosshairPaint);
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, crosshairPaint);

        // ── 50% dashed reference ring ──
        canvas.drawCircle(centerX, centerY, baseRadius * 0.50f, midRingPaint);

        // ── Direction labels ──
        float labelOffset = baseRadius + baseRadius * 0.10f;
        canvas.drawText("FWD", centerX, centerY - labelOffset, labelPaint);
        canvas.drawText("REV", centerX, centerY + labelOffset + labelPaint.getTextSize(), labelPaint);

        // ── Border ring ──
        canvas.drawCircle(centerX, centerY, baseRadius, baseBorderPaint);

        // ── Trail line from center to knob ──
        if (displacement > 0.02f) {
            trailPaint.setAlpha((int) (displacement * 160));
            canvas.drawLine(centerX, centerY, knobX, knobY, trailPaint);
        }

        // ── Active outer glow ring ──
        if (isActive && displacement > 0.05f) {
            int glowAlpha = (int) (displacement * 80);
            activeGlowPaint.setColor(blendKnobColor(displacement));
            activeGlowPaint.setAlpha(glowAlpha);
            canvas.drawCircle(centerX, centerY, baseRadius + 4f, activeGlowPaint);
        }

        // ── Knob (colour varies with displacement) ──
        int knobColor = blendKnobColor(displacement);
        knobPaint.setColor(knobColor);
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);

        // ── Knob inner radial highlight ──
        if (isActive) {
            RadialGradient knobHighlight = new RadialGradient(
                    knobX - knobRadius * 0.25f, knobY - knobRadius * 0.25f,
                    knobRadius,
                    Color.argb(60, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP);
            knobPaint.setShader(knobHighlight);
            canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
            knobPaint.setShader(null);
        }

        // ── Knob highlight ring ──
        knobGlowPaint.setColor(knobColor);
        knobGlowPaint.setAlpha(isActive ? 200 : 120);
        canvas.drawCircle(knobX, knobY, knobRadius, knobGlowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(cancelResetRunnable);
                activePointerId = event.getPointerId(0);
                // Prevent parent scrolling containers from stealing this drag stream.
                requestParentDisallowIntercept(true);
                isActive = true;
                updateKnob(event.getX(0), event.getY(0), true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(cancelResetRunnable);
                if (activePointerId == INVALID_POINTER_ID) {
                    int pointerIndex = event.getActionIndex();
                    activePointerId = event.getPointerId(pointerIndex);
                    requestParentDisallowIntercept(true);
                    isActive = true;
                    updateKnob(event.getX(pointerIndex), event.getY(pointerIndex), true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                removeCallbacks(cancelResetRunnable);
                int pointerIndex = activePointerId == INVALID_POINTER_ID
                        ? 0
                        : event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) {
                    pointerIndex = 0;
                    activePointerId = event.getPointerId(pointerIndex);
                }
                requestParentDisallowIntercept(true);
                isActive = true;
                updateKnob(event.getX(pointerIndex), event.getY(pointerIndex), true);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                removeCallbacks(cancelResetRunnable);
                int releasedIndex = event.getActionIndex();
                int releasedPointerId = event.getPointerId(releasedIndex);
                if (releasedPointerId == activePointerId) {
                    if (event.getPointerCount() > 1) {
                        int nextIndex = releasedIndex == 0 ? 1 : 0;
                        activePointerId = event.getPointerId(nextIndex);
                        updateKnob(event.getX(nextIndex), event.getY(nextIndex), true);
                    } else {
                        requestParentDisallowIntercept(false);
                        resetStick();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(cancelResetRunnable);
                requestParentDisallowIntercept(false);
                resetStick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                requestParentDisallowIntercept(false);
                removeCallbacks(cancelResetRunnable);
                postDelayed(cancelResetRunnable, CANCEL_RESET_GRACE_MS);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateKnob(float x, float y, boolean active) {
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > baseRadius) {
            // Keep the knob inside a circular boundary so diagonal drag never
            // exceeds full throw.
            float scale = baseRadius / distance;
            dx *= scale;
            dy *= scale;
        }
        knobX = centerX + dx;
        knobY = centerY + dy;
        invalidate();
        if (listener != null) {
            // Return normalized coordinates in [-1, 1]. The caller decides how to
            // map these into forward/turn command values.
            listener.onJoystick(dx / baseRadius, dy / baseRadius, active);
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private float displacement() {
        float dx = knobX - centerX;
        float dy = knobY - centerY;
        if (baseRadius < 1f) return 0f;
        return Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / baseRadius);
    }

    /** Blue → Amber → Red based on displacement (0..1) */
    private int blendKnobColor(float t) {
        if (t < 0.55f) {
            float f = t / 0.55f;
            return blendColor(COL_KNOB_IDLE, COL_KNOB_MID, f);
        } else {
            float f = (t - 0.55f) / 0.45f;
            return blendColor(COL_KNOB_MID, COL_KNOB_FULL, f);
        }
    }

    private int blendColor(int from, int to, float ratio) {
        float r = ratio;
        int ra = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * r);
        int rr = (int) (Color.red(from)   + (Color.red(to)   - Color.red(from))   * r);
        int rg = (int) (Color.green(from) + (Color.green(to)  - Color.green(from)) * r);
        int rb = (int) (Color.blue(from)  + (Color.blue(to)   - Color.blue(from))  * r);
        return Color.argb(ra, rr, rg, rb);
    }

    private void drawTickRing(Canvas canvas, float fraction, int count) {
        float tickRadius = baseRadius * fraction;
        float tickLen = baseRadius * 0.02f;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float x1 = centerX + cos * (tickRadius - tickLen);
            float y1 = centerY + sin * (tickRadius - tickLen);
            float x2 = centerX + cos * (tickRadius + tickLen);
            float y2 = centerY + sin * (tickRadius + tickLen);
            canvas.drawLine(x1, y1, x2, y2, outerTickPaint);
        }
    }

    private static Paint mkPaint(Paint.Style style, int color, float strokeWidth) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(style);
        p.setColor(color);
        if (strokeWidth > 0) p.setStrokeWidth(strokeWidth);
        return p;
    }
}
