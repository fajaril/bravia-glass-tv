package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The frame that clips the grid under the top bar. While rows are scrolled up to it, its top edge feathers
 * them out to transparent (the wallpaper shows through) instead of cutting them off. The offscreen layer
 * this needs is only used while the fade is on, so the resting home screen costs nothing extra.
 * It clips at the top and bottom only: at the sides the dock's glass tray and an end tile grown by focus
 * reach a little past the grid, and a clip there cut the tray's rounded ends off.
 */
public class FadeTopFrame extends FrameLayout {

    private final Paint mask = new Paint();
    private final float length;
    /** How far past its sides the grid may draw. */
    private final float side;
    private float strength;   // 0 = off, 1 = full fade

    public FadeTopFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        length = 56 * getResources().getDisplayMetrics().density;
        side = 40 * getResources().getDisplayMetrics().density;
        setClipChildren(false);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setWillNotDraw(false);
    }

    /** How strongly the top edge fades, 0 to 1; animate it to fade the effect in and out. */
    public void setFadeStrength(float s) {
        if (s == strength) return;
        strength = s;
        mask.setShader(new LinearGradient(0, 0, 0, length, (int) (255 * s) << 24, 0, Shader.TileMode.CLAMP));
        invalidate();
    }

    public float getFadeStrength() {
        return strength;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (strength <= 0f) {
            int save = canvas.save();
            canvas.clipRect(-side, 0, getWidth() + side, getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
            return;
        }
        int save = canvas.saveLayer(-side, 0, getWidth() + side, length, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(-side, 0, getWidth() + side, length, mask);
        canvas.restoreToCount(save);
        // Below the fade band the children draw normally, outside the layer.
        save = canvas.save();
        canvas.clipRect(-side, length, getWidth() + side, getHeight());
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
