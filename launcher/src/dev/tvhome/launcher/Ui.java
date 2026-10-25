package dev.tvhome.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Shared colours, sizes and small view helpers. */
final class Ui {
    private Ui() {}

    static final int BG_TOP = 0xFF101116;
    static final int BG_BOTTOM = 0xFF07070A;
    static final int PANEL = 0xE61B1C23;
    static final int TILE = 0xFF22232B;
    static final int TEXT = 0xFFF2F2F5;
    static final int TEXT_DIM = 0xFF9A9BA6;
    static final int ACCENT = 0xFF1ED760;
    static final int HAIRLINE = 0x33FFFFFF;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable ring(int color, int widthPx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(widthPx, color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Scale of a focused tile: it pops toward you. Containers leave room for this. */
    static final float POP = 1.12f;

    /**
     * Liquid-glass focus: a thin white-and-dark ring that shows on any content, and (with lift) the item
     * pops forward. No glow or shadow, so nothing outlines the edges of the panels around it.
     */
    static void focusable(View v, float radiusPx, boolean lift) {
        Context c = v.getContext();
        Glass.FocusRing ring = new Glass.FocusRing(radiusPx, dp(c, 1.5f), dp(c, 1.5f));
        v.setFocusable(true);
        v.setClickable(true);
        if (lift) {
            v.setOutlineSpotShadowColor(0);
            v.setOutlineAmbientShadowColor(0);
        }
        v.setOnFocusChangeListener((view, has) -> {
            view.setForeground(has ? ring : null);
            if (lift) {
                float s = has ? POP : 1f;
                // Draw the focused item above its neighbours (shadow colours are transparent: no glow).
                view.setZ(has ? 1 : 0);
                view.animate().scaleX(s).scaleY(s)
                        .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                        .setDuration(has ? 260 : 180).start();
            }
        });
    }

    static FrameLayout.LayoutParams at(int left, int top, int width, int height) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.START);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }
}
