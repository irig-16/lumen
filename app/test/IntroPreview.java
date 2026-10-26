package com.lumen.launcher;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Desktop renderer for the intro: draws IntroScene with Java2D into PNG frames, so the preview video
 * uses exactly the same animation code as the projector. Usage: IntroPreview <outDir> <fps> [homeImage]
 */
public class IntroPreview {

    static final class J2D implements IntroScene.Gfx {
        Graphics2D g;
        final Font light = new Font("DejaVu Sans", Font.PLAIN, 12), medium = new Font("DejaVu Sans", Font.BOLD, 12);

        private Color c(int argb) {
            return new Color(argb, true);
        }

        private final java.util.ArrayDeque<AffineTransform> saved = new java.util.ArrayDeque<AffineTransform>();

        public void flipY(float axisY) {
            saved.push(g.getTransform());
            g.translate(0, axisY);
            g.scale(1, -1);
            g.translate(0, -axisY);
        }

        public void restore() {
            if (!saved.isEmpty()) g.setTransform(saved.pop());
        }

        public void clear(int argb) {
            g.setComposite(AlphaComposite.Src);
            g.setColor(c(argb));
            g.fillRect(0, 0, 100000, 100000);
            g.setComposite(AlphaComposite.SrcOver);
        }

        public void glow(float cx, float cy, float r, int argb) {
            if (r <= 0) return;
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[]{0f, 1f},
                    new Color[]{c(argb), c(argb & 0x00FFFFFF)}, MultipleGradientPaint.CycleMethod.NO_CYCLE));
            g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        }

        public void circle(float cx, float cy, float r, int argb) {
            g.setColor(c(argb));
            g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        }

        public void ring(float cx, float cy, float r, float width, int argb) {
            g.setColor(c(argb));
            g.setStroke(new BasicStroke(width));
            g.draw(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        }

        public void arc(float cx, float cy, float r, float width, float start, float sweep, int argb) {
            g.setColor(c(argb));
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Java2D angles run counter-clockwise; Android's run clockwise
            g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -start, -sweep, Arc2D.OPEN));
        }

        public void line(float x0, float y0, float x1, float y1, float width, int argb) {
            g.setColor(c(argb));
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }

        public void text(String s, float x, float y, float size, int argb, int weight, int align, float scale) {
            Font f = (weight == 2 ? medium : light).deriveFont(size);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            float tw = fm.stringWidth(s);
            float dx = align == 0 ? -tw / 2f : align == 1 ? -tw : 0;
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.scale(scale, scale);
            g.setColor(c(argb));
            g.drawString(s, dx, 0);
            g.setTransform(old);
        }

        public void rect(float l, float t, float r, float b, int argb) {
            g.setColor(c(argb));
            g.fill(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t));
        }

        public void vgradient(float l, float t, float r, float b, int[] colors) {
            float[] f = new float[colors.length];
            Color[] cs = new Color[colors.length];
            for (int i = 0; i < colors.length; i++) {
                f[i] = colors.length == 1 ? 0 : i / (float) (colors.length - 1);
                cs[i] = c(colors[i]);
            }
            g.setPaint(new java.awt.LinearGradientPaint(0, t, 0, b, f, cs));
            g.fill(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t));
        }

        public void poly(float[] xy, int points, int argb) {
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            p.moveTo(xy[0], xy[1]);
            for (int i = 1; i < points; i++) p.lineTo(xy[2 * i], xy[2 * i + 1]);
            p.closePath();
            g.setColor(c(argb));
            g.fill(p);
        }

        public float textWidth(String s, float size, int weight) {
            g.setFont((weight == 2 ? medium : light).deriveFont(size));
            return g.getFontMetrics().stringWidth(s);
        }
    }

    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();
        int fps = Integer.parseInt(a[1]);
        BufferedImage home = a.length > 2 ? ImageIO.read(new File(a[2])) : null;
        int W = 960, H = 540;
        IntroScene scene = new IntroScene();
        J2D gfx = new J2D();
        int frames = (int) (IntroScene.DURATION * fps) + fps / 2;
        BufferedImage layer = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < frames; f++) {
            float t = f / (float) fps;
            scene.step(t, W, H);
            Graphics2D g = layer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gfx.g = g;
            scene.draw(gfx, t);
            g.dispose();

            BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D fg = frame.createGraphics();
            fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (home != null) fg.drawImage(home, 0, 0, W, H, null);
            fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, scene.alpha(t)));
            fg.drawImage(layer, 0, 0, null);
            fg.dispose();
            ImageIO.write(frame, "png", new File(out, String.format("f%04d.png", f)));
        }
        System.out.println("frames: " + frames);
    }
}
