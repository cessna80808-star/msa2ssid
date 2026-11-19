package dev.msa2ssid.mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The only two places where 26.1.2/26.2 and 26.3 differ for this mod:
 * the keyboard InputConstants.Type constant is KEYSYM before 26.3 and KEYBOARD
 * from 26.3, and RenderPipeline moved package in 26.3 (which never has to be
 * named here because the blit call is type-checked per build).
 */
final class Compat {

    private Compat() {
    }

    static InputConstants.Type keyboard() {
        for (String candidate : new String[]{"KEYBOARD", "KEYSYM"}) {
            try {
                return InputConstants.Type.valueOf(candidate);
            } catch (IllegalArgumentException ignored) {
                // not present on this version
            }
        }
        return InputConstants.Type.values()[0];
    }

    /** Draws an 8x8 head icon scaled to {@code size}. */
    static void blitHead(GuiGraphicsExtractor g, Identifier texture, int x, int y, int size) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, 8, 8);
    }
}
