package dev.tvhome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Liquid-glass look on Android 10, which has no live background blur: a soft aurora background
 * (rendered small and scaled up, so it's naturally blurry) and translucent panels with a light-catching
 * edge and a sheen on top.
 */
final class Glass {
    private Glass() {}

    static final int BASE = 0xFF040506;

    /** Near-black with a faint cool-grey glow for depth. Rendered once at low resolution. */
    static final class Aurora extends Drawable {
        private final int scrim;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private Bitmap cache;

        /** @param scrim colour laid over the aurora (e.g. darker for busy screens), 0 for none */
        Aurora(int scrim) {
            this.scrim = scrim;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (cache == null) cache = render(Math.max(1, b.width() / 6), Math.max(1, b.height() / 6));
            canvas.drawBitmap(cache, null, b, bitmapPaint);
            if (scrim != 0) canvas.drawColor(scrim);
        }

        private static Bitmap render(int w, int h) {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(BASE);
            blob(c, w * 0.18f, h * 0.10f, w * 0.60f, 0x4A3A4558); // cool grey light, top left
            blob(c, w * 0.85f, h * 0.20f, w * 0.45f, 0x2E3C4150); // fainter, top right
            blob(c, w * 0.55f, h * 1.05f, w * 0.65f, 0x26304048); // slate, low centre
            // Vignette so the edges fall off to black.
            Paint v = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            v.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    new int[]{0x00000000, 0x00000000, 0xB3000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, v);
            return bmp;
        }

        private static void blob(Canvas c, float x, float y, float r, int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG); // dither: no visible rings in the fade
            p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * Rounds the corners of a live video tile. Android 10 can't clip a video surface to a rounded shape,
     * so this view sits over the tile and repaints the screen background everywhere outside a rounded
     * rectangle: the four corners. {@code offsetX/Y} is the tile's position on screen, so the background
     * lines up exactly with the real one behind it.
     */
    static final class CornerMask extends android.view.View {
        private final Aurora background = new Aurora(0);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();
        private final float radius;
        private final int offsetX, offsetY, screenW, screenH;

        CornerMask(android.content.Context c, float radius, int offsetX, int offsetY, int screenW, int screenH) {
            super(c);
            this.radius = radius;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.screenW = screenW;
            this.screenH = screenH;
            clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            canvas.save();
            canvas.translate(-offsetX, -offsetY);
            background.setBounds(0, 0, screenW, screenH);
            background.draw(canvas);
            canvas.restore();
            hole.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(hole, radius, radius, clear); // anti-aliased, so the curve stays smooth
            canvas.restoreToCount(layer);
        }
    }

    /** Frosted panel: translucent fill, top sheen, and a bright-to-faint edge. */
    static final class Panel extends Drawable {
        private final float radius;
        private final int fillTop, fillBottom;
        private final float stroke;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Panel(float radius, int fillTop, int fillBottom, float stroke) {
            this.radius = radius;
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.stroke = stroke;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            // Kept faint on purpose: a bright edge or sheen makes every panel's outline stand out.
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, fillTop, fillBottom, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{0x33FFFFFF, 0x0AFFFFFF, 0x1AFFFFFF}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f, 0x0FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            fill.setDither(true);
            sheen.setDither(true);
        }

        @Override
        public void draw(Canvas c) {
            r.set(getBounds());
            c.drawRoundRect(r, radius, radius, fill);
            c.drawRoundRect(r, radius, radius, sheen);
            r.inset(stroke / 2, stroke / 2);
            c.drawRoundRect(r, radius, radius, edge);
        }

        @Override
        public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), radius);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Edges are hairlines: whatever width callers ask for, at most this fraction of it. */
    private static final float EDGE_SCALE = 0.75f;

    static Panel panel(float radius, float stroke) {
        return new Panel(radius, 0x24FFFFFF, 0x14FFFFFF, stroke * EDGE_SCALE);
    }

    static Panel tinted(float radius, float stroke, int rgb) {
        return new Panel(radius, 0x66000000 | (rgb & 0xFFFFFF), 0x4D000000 | (rgb & 0xFFFFFF), stroke * EDGE_SCALE);
    }

    /**
     * Focus ring that shows on any content: a bright white edge with a dark band inside it, so it reads on
     * white banners (YouTube, Netflix) as well as dark ones.
     */
    static final class FocusRing extends Drawable {
        private final float radius, white, dark;
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        FocusRing(float radius, float white, float dark) {
            this.radius = radius;
            this.white = white;
            this.dark = dark;
            whitePaint.setStyle(Paint.Style.STROKE);
            whitePaint.setStrokeWidth(white);
            whitePaint.setColor(0xFFFFFFFF);
            darkPaint.setStyle(Paint.Style.STROKE);
            darkPaint.setStrokeWidth(dark);
            darkPaint.setColor(0x8C05070F);
        }

        @Override
        public void draw(Canvas c) {
            r.set(getBounds());
            r.inset(white + dark / 2, white + dark / 2);
            c.drawRoundRect(r, Math.max(0, radius - white), Math.max(0, radius - white), darkPaint);
            r.set(getBounds());
            r.inset(white / 2, white / 2);
            c.drawRoundRect(r, radius, radius, whitePaint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
