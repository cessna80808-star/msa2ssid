package dev.msa2ssid.core;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Turns a 64x64 (or legacy 64x32) skin PNG into a small head icon, so the UI can
 * draw a face with a plain 8x8 texture instead of doing UV maths per version.
 */
public final class Skins {

    private Skins() {
    }

    /**
     * Checks a candidate skin before uploading it. Mojang accepts 64x64 and 64x32
     * PNGs only; HD skins are refused server-side, so catch them here.
     */
    public static void validateSkin(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("that file is not a readable PNG image");
        int w = img.getWidth();
        int h = img.getHeight();
        if (w != 64 || (h != 64 && h != 32)) {
            throw new IOException("skins must be 64x64 or 64x32 (this is " + w + "x" + h + ")");
        }
    }

    /** Returns an 8x8 PNG of the face with the hat layer composited on top. */
    public static byte[] headIcon(byte[] skinPng) throws IOException {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        if (skin == null) throw new IOException("skin was not a readable image");
        if (skin.getWidth() < 64 || skin.getHeight() < 32) {
            throw new IOException("unexpected skin size " + skin.getWidth() + "x" + skin.getHeight());
        }

        BufferedImage out = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int face = skin.getRGB(8 + x, 8 + y);
                if (skin.getHeight() >= 64) {
                    int hat = skin.getRGB(40 + x, 8 + y);
                    face = over(hat, face);
                }
                out.setRGB(x, y, face);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    /** Source-over compositing of {@code top} onto {@code bottom} (both ARGB). */
    private static int over(int top, int bottom) {
        int ta = (top >>> 24) & 0xFF;
        if (ta == 0) return bottom;
        if (ta == 255) return top;
        float a = ta / 255f;
        int br = (bottom >> 16) & 0xFF, bg = (bottom >> 8) & 0xFF, bb = bottom & 0xFF;
        int tr = (top >> 16) & 0xFF, tg = (top >> 8) & 0xFF, tb = top & 0xFF;
        int r = Math.round(tr * a + br * (1 - a));
        int g = Math.round(tg * a + bg * (1 - a));
        int bl = Math.round(tb * a + bb * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Scales an icon up by an integer factor with nearest-neighbour sampling. */
    public static byte[] scale(byte[] png, int factor) throws IOException {
        if (factor <= 1) return png;
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) return png;
        BufferedImage out = new BufferedImage(src.getWidth() * factor, src.getHeight() * factor,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }
}
