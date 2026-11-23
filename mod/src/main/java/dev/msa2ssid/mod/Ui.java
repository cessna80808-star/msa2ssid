package dev.msa2ssid.mod;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing primitives for the account manager's own look, so the screens do not
 * depend on vanilla widget art (which cannot be restyled): a dark panel with a
 * header strip, list cards and pill buttons.
 */
final class Ui {

    static final int PANEL = 0xF2141418;
    static final int PANEL_BORDER = 0xFF34343E;
    static final int HEADER = 0xFF1B1B22;
    static final int TEXT = 0xFFEDEDF2;
    static final int MUTED = 0xFF8B8B96;
    static final int ACCENT = 0xFF4F8CFF;
    static final int ACCENT_DIM = 0xFF2A4C8F;
    static final int DANGER = 0xFFFF6B6B;
    static final int WARN = 0xFFFFAA00;
    static final int OK = 0xFF57D68D;
    static final int ROW = 0xFF1C1C23;
    static final int ROW_HOVER = 0xFF26262F;
    static final int ROW_SELECTED = 0xFF22314F;
    static final int PLACEHOLDER = 0xFF2A2A32;
    static final int DISABLED = 0xFF55555F;

    private Ui() {
    }

    static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Opaque body with a one pixel border; drawn before widgets so they sit on top. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, PANEL_BORDER);
    }

    /** Top strip with a title on the left and an optional state word on the right. */
    static void header(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                       String title, String right, int rightColor) {
        g.fill(x + 1, y + 1, x + w - 1, y + h, HEADER);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, ACCENT_DIM);
        g.text(font, title, x + 12, y + (h - 8) / 2, TEXT);
        if (right != null) {
            g.text(font, right, x + w - 12 - font.width(right), y + (h - 8) / 2, rightColor);
        }
    }

    /** A flat button: accent filled for the primary action, subtle otherwise. */
    static void pill(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                     String label, boolean hovered, boolean primary) {
        pill(g, font, x, y, w, h, label, hovered, primary, true);
    }

    /** The same button, greyed out when its action has nothing to act on. */
    static void pill(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                     String label, boolean hovered, boolean primary, boolean enabled) {
        pill(g, font, x, y, w, h, label, hovered, primary, enabled, TEXT);
    }

    /** The same button with its own label colour (a destructive action, say). */
    static void pill(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                     String label, boolean hovered, boolean primary, boolean enabled,
                     int labelColor) {
        int bg;
        int color;
        if (!enabled) {
            bg = 0xFF1A1A20;
            color = DISABLED;
        } else if (primary) {
            bg = hovered ? 0xFF6BA1FF : ACCENT;
            color = 0xFF0B0B10;
        } else {
            bg = hovered ? 0xFF33333D : 0xFF23232B;
            color = labelColor;
        }
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, enabled && hovered ? 0xFF7C7C8C : 0xFF3A3A46);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, color);
    }

    /** One account row: tinted when selected, lighter on hover, with an accent spine. */
    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h,
                     boolean selected, boolean hovered) {
        g.fill(x, y, x + w, y + h, selected ? ROW_SELECTED : (hovered ? ROW_HOVER : ROW));
        if (selected) g.fill(x, y, x + 2, y + h, ACCENT);
    }

    /** A labelled input frame drawn behind a vanilla EditBox. */
    static void field(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF0F0F13);
        g.outline(x - 2, y - 2, w + 4, h + 4, 0xFF3A3A46);
    }
}
