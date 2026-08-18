package com.firstham.aethergui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

public final class ConnectionOrbView extends View {
    private static final int DISCONNECTED = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;
    private static final int DISCONNECTING = 3;
    private static final int ERROR = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Path sphere = new Path();
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] particleAngles = new float[18];
    private final float[] particleRadii = new float[18];
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private Shader ringShader;
    private Shader bodyShader;
    private Shader highlightShader;
    private ValueAnimator motion;
    private int state = DISCONNECTED;
    private float phase;
    private String label = "";

    public ConnectionOrbView(Context context) { this(context, null); }
    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particleAngles.length; i++) {
            particleAngles[i] = (float) (i * Math.PI * 2 / particleAngles.length);
            particleRadii[i] = 1.05f + (i % 5) * .065f;
        }
    }

    public void setConnectionState(String value, String text) {
        int next;
        if ("connected".equals(value)) next = CONNECTED;
        else if ("disconnecting".equals(value)) next = DISCONNECTING;
        else if ("starting".equals(value) || "smart-testing".equals(value) || "scanning".equals(value) || "securing".equals(value) || "reconnecting".equals(value)) next = CONNECTING;
        else if ("error".equals(value) || "blocked".equals(value)) next = ERROR;
        else next = DISCONNECTED;
        boolean changed = next != state;
        state = next;
        label = text == null ? "" : text;
        if (changed) { updateShaders(); restartMotion(); }
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateShaders();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(width, height) * 0.39f;
        float breath = state == CONNECTED ? 1f + 0.018f * (float) Math.sin(phase * Math.PI * 2) : state == CONNECTING ? 1f + 0.028f * (float) Math.sin(phase * Math.PI * 2) : state == DISCONNECTED ? 1f + 0.012f * (float) Math.sin(phase * Math.PI * 2) : 1f;
        radius *= breath;

        int start = startColor();
        int end = endColor();

        paint.setShader(null);
        paint.setColor(withAlpha(end, state == DISCONNECTED ? 24 : 42));
        canvas.drawPath(spherePath(cx, cy, radius * 1.24f), paint);
        paint.setColor(withAlpha(start, state == DISCONNECTED ? 30 : 58));
        canvas.drawPath(spherePath(cx, cy, radius * 1.12f), paint);

        // A restrained particle field makes the core feel alive without becoming a dashboard effect.
        if (state != ERROR) {
            particlePaint.setColor(state == CONNECTED ? Color.rgb(37, 215, 242) : Color.rgb(115, 103, 236));
            for (int i = 0; i < particleAngles.length; i++) {
                float angle = particleAngles[i] + phase * (state == CONNECTING ? 1.8f : .45f);
                float orbit = radius * particleRadii[i];
                float px = cx + (float) Math.cos(angle) * orbit;
                float py = cy + (float) Math.sin(angle) * orbit;
                int alpha = (int) (35 + 45 * (0.5f + 0.5f * (float) Math.sin(angle * 2 + phase * 6)));
                particlePaint.setAlpha(alpha);
                canvas.drawCircle(px, py, Math.max(2f, radius * (i % 3 == 0 ? .016f : .009f)), particlePaint);
            }
        }

        paint.setShader(ringShader);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.075f);
        canvas.save();
        canvas.rotate((state == CONNECTING ? phase * 360f : state == CONNECTED ? phase * 35f : 0f) - 90f, cx, cy);
        canvas.drawPath(spherePath(cx, cy, radius), paint);
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bodyShader);
        canvas.drawPath(spherePath(cx, cy, radius * .91f), paint);

        paint.setShader(highlightShader);
        canvas.drawPath(spherePath(cx, cy, radius * .88f), paint);
        paint.setShader(null);

        float iconY = cy - radius * .23f;
        iconPaint.setStrokeWidth(Math.max(5f, radius * .035f));
        canvas.drawLine(cx, iconY - radius * .25f, cx, iconY - radius * .02f, iconPaint);
        arc.set(cx - radius * .22f, iconY - radius * .17f, cx + radius * .22f, iconY + radius * .27f);
        canvas.drawArc(arc, -43f, 266f, false, iconPaint);

        textPaint.setTextSize(Math.max(18f, radius * .145f));
        textPaint.getFontMetrics(fontMetrics);
        float baseline = cy + radius * .38f - (fontMetrics.ascent + fontMetrics.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
        textPaint.setTextSize(Math.max(10f, radius * .07f));
        textPaint.setColor(Color.rgb(100, 119, 148));
        canvas.drawText(getContext().getString(R.string.tap_to_secure), cx, baseline + radius * .18f, textPaint);
        textPaint.setColor(Color.WHITE);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().cancel();
            if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            else { setScaleX(1f); setScaleY(1f); }
        }
        return super.onTouchEvent(event);
    }

    private void restartMotion() {
        stopMotion();
        if (!isShown() || !ValueAnimator.areAnimatorsEnabled() || state == ERROR) { phase = 0f; invalidate(); return; }
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(state == CONNECTING ? 1450 : state == DISCONNECTING ? 900 : state == DISCONNECTED ? 4200 : 3200);
        motion.setRepeatCount(state == DISCONNECTING ? 0 : ValueAnimator.INFINITE);
        motion.setInterpolator(state == CONNECTING ? new LinearInterpolator() : new AccelerateDecelerateInterpolator());
        motion.addUpdateListener(animation -> { phase = (Float) animation.getAnimatedValue(); invalidate(); });
        motion.start();
    }

    private void stopMotion() { if (motion != null) { motion.cancel(); motion = null; } }
    private Path spherePath(float cx, float cy, float radius) {
        sphere.reset();
        final int points = 16;
        float[] xs = new float[points];
        float[] ys = new float[points];
        for (int i = 0; i < points; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / points;
            float wobble = 1f + 0.055f * (float) Math.sin(i * 2.37f + phase * 0.18f)
                    + 0.025f * (float) Math.cos(i * 4.11f - phase * 0.11f);
            xs[i] = cx + radius * wobble * (float) Math.cos(angle);
            ys[i] = cy + radius * wobble * (float) Math.sin(angle);
        }
        sphere.moveTo((xs[0] + xs[points - 1]) * .5f, (ys[0] + ys[points - 1]) * .5f);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            sphere.quadTo(xs[i], ys[i], (xs[i] + xs[next]) * .5f, (ys[i] + ys[next]) * .5f);
        }
        sphere.close();
        return sphere;
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); restartMotion(); }
    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) restartMotion(); else stopMotion(); }

    private static int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }
    private static int lighten(int color, float amount) { return Color.rgb((int) (Color.red(color) + (255 - Color.red(color)) * amount), (int) (Color.green(color) + (255 - Color.green(color)) * amount), (int) (Color.blue(color) + (255 - Color.blue(color)) * amount)); }
    private int startColor() { return state == CONNECTED ? Color.rgb(23, 221, 255) : state == CONNECTING ? Color.rgb(24, 215, 244) : state == DISCONNECTING ? Color.rgb(98, 120, 203) : state == ERROR ? Color.rgb(215, 65, 92) : Color.rgb(62, 156, 255); }
    private int endColor() { return state == CONNECTED ? Color.rgb(126, 73, 255) : state == CONNECTING ? Color.rgb(126, 73, 255) : state == DISCONNECTING ? Color.rgb(239, 83, 111) : state == ERROR ? Color.rgb(121, 36, 74) : Color.rgb(36, 68, 164); }
    private void updateShaders() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * .39f;
        int start = startColor();
        int end = endColor();
        int highlight = Color.argb(state == DISCONNECTED ? 70 : 155, 255, 255, 255);
        ringShader = new SweepGradient(cx, cy, new int[]{start, end, highlight, start}, new float[]{0f, .46f, .72f, 1f});
        bodyShader = new RadialGradient(cx - radius * .28f, cy - radius * .34f, radius * 1.45f, new int[]{lighten(start, .28f), start, end, Color.rgb(17, 20, 38)}, new float[]{0f, .32f, .74f, 1f}, Shader.TileMode.CLAMP);
        highlightShader = new RadialGradient(cx - radius * .34f, cy - radius * .42f, radius * .7f, new int[]{Color.argb(105, 255, 255, 255), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
    }
}
