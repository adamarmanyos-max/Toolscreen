package dev.toolscreen.mobile;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Toolscreen's EyeZoom, ported: a magnified clone of the pixels around the
 * crosshair, with a centre line and a ruler counting pixel offsets outward.
 *
 * <p>The point is measurement, not looking at things. One ruler mark is
 * <strong>one rendered game pixel</strong> — the quantity Ninjabrain Bot wants
 * for its correction. The magnification exists only so those pixels can be
 * counted by eye. Marking anything else (screen pixels, say) would feed the
 * calculator a wrong number, which is worse than showing nothing.
 *
 * <h2>How the clone is taken</h2>
 *
 * The obvious approach — sampling Minecraft's main render target as a texture —
 * is undefined behaviour here, because the HUD is being drawn into that very
 * target at the time. Doing it safely needs a scratch framebuffer, which is
 * exactly the kind of thing that cannot be verified from a build machine
 * against gl4es on iOS.
 *
 * <p>So instead the region is read back with {@code glReadPixels} and each
 * pixel is redrawn as a filled rectangle. That uses only core calls, and it
 * also happens to be what the original looks like: flat blocks of colour, one
 * per game pixel. The cost is a synchronous read stalling the pipeline each
 * frame, which is why only a small region is sampled and only while a mode that
 * asks for it is active.
 *
 * <h2>Why it is drawn inside the strip</h2>
 *
 * On Windows the panel sits beside the game window on the desktop. That is not
 * available here: the HUD is drawn inside Minecraft's framebuffer, which is the
 * strip itself, so nothing a mod draws can land in the black surround. The
 * panel therefore overlays the strip, and its defaults are sized to fit a
 * narrow one rather than copying the original's proportions.
 */
public final class EyeZoom {

    private static ByteBuffer pixelBuffer;

    private EyeZoom() {
    }

    /**
     * Draws the overlay, if the active mode asks for it.
     *
     * @param matrices HUD matrix stack, in scaled GUI units
     * @param font     the HUD's text renderer, for the ruler numbers
     */
    public static void render(MatrixStack matrices, MinecraftClient client, TextRenderer font) {
        ToolscreenMobile.noteOverlayHookFired();

        Window window = client.getWindow();
        if (window == null) return;

        // Drawn whenever a mode is active, not only for EyeZoom: hiding the HUD
        // takes the game's own crosshair with it, and a stretched screen is
        // useless for aiming without one.
        if (ToolscreenMobile.crosshairEnabled() && ToolscreenMobile.isOverrideActive()) {
            drawCrosshair(matrices, window);
        }

        if (!ToolscreenMobile.eyeZoomActive()) return;

        int fbWidth = window.getFramebufferWidth();
        int fbHeight = window.getFramebufferHeight();
        int regionW = Math.min(ToolscreenMobile.eyeZoomRegionWidth(), fbWidth);
        int regionH = Math.min(ToolscreenMobile.eyeZoomRegionHeight(), fbHeight);
        if (regionW < 2 || regionH < 2) return;

        int[] pixels = readRegion(fbWidth, fbHeight, regionW, regionH);
        if (pixels == null) return;
        ToolscreenMobile.noteEyeZoomActive();

        int zoom = ToolscreenMobile.eyeZoomFactor();
        int panelW = regionW * zoom;
        int panelH = regionH * zoom;
        int panelX = (window.getScaledWidth() - panelW) / 2;
        int panelY = (int) (window.getScaledHeight() * ToolscreenMobile.eyeZoomTop());

        drawPixels(matrices, pixels, regionW, regionH, panelX, panelY, zoom);
        drawRuler(matrices, font, regionW, panelX, panelY, panelH, zoom);
        drawCentreLine(matrices, regionW, panelX, panelY, panelH, zoom);
    }

    /**
     * Reads a block of pixels centred on the crosshair.
     *
     * <p>Coordinates are framebuffer space, whose origin is the bottom-left
     * corner — the crosshair sits at the centre of the framebuffer Minecraft
     * believes in, which is the overridden size, not the real surface.
     *
     * @return ARGB pixels in row-major order starting at the top row, or null
     *         if the read could not be made
     */
    private static int[] readRegion(int fbWidth, int fbHeight, int regionW, int regionH) {
        int x0 = (fbWidth - regionW) / 2;
        int y0 = (fbHeight - regionH) / 2;
        if (x0 < 0 || y0 < 0) return null;

        int needed = regionW * regionH * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < needed) {
            pixelBuffer = BufferUtils.createByteBuffer(needed);
        }
        pixelBuffer.clear();

        GL11.glReadPixels(x0, y0, regionW, regionH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);

        int[] out = new int[regionW * regionH];
        for (int row = 0; row < regionH; row++) {
            // glReadPixels returns rows bottom-up; flip so row 0 is the top.
            int src = (regionH - 1 - row) * regionW * 4;
            for (int col = 0; col < regionW; col++) {
                int i = src + col * 4;
                int r = pixelBuffer.get(i) & 0xFF;
                int g = pixelBuffer.get(i + 1) & 0xFF;
                int b = pixelBuffer.get(i + 2) & 0xFF;
                out[row * regionW + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    /** Each sampled pixel becomes one {@code zoom}-sized square. */
    private static void drawPixels(MatrixStack matrices, int[] pixels, int regionW, int regionH,
                                   int panelX, int panelY, int zoom) {
        for (int row = 0; row < regionH; row++) {
            for (int col = 0; col < regionW; col++) {
                int x = panelX + col * zoom;
                int y = panelY + row * zoom;
                DrawableHelper.fill(matrices, x, y, x + zoom, y + zoom, pixels[row * regionW + col]);
            }
        }
    }

    /**
     * The ruler: one cell per game pixel, numbered outward from the centre,
     * alternating colours so a long run stays countable.
     */
    private static void drawRuler(MatrixStack matrices, TextRenderer font, int regionW,
                                  int panelX, int panelY, int panelH, int zoom) {
        int centreCol = regionW / 2;
        int rulerY = panelY + panelH / 2 - zoom / 2;
        int max = ToolscreenMobile.eyeZoomRulerMax();

        for (int col = 0; col < regionW; col++) {
            int offset = col < centreCol ? centreCol - col : col - centreCol + 1;
            if (offset > max) continue;

            int x = panelX + col * zoom;
            int colour = (offset % 2 == 0) ? 0xFFADD8E6 : 0xFFFFC0CB;
            DrawableHelper.fill(matrices, x, rulerY, x + zoom, rulerY + zoom, colour);

            // Half scale: a two-digit number at full size does not fit a cell
            // only a few GUI units wide.
            String label = Integer.toString(offset);
            int labelWidth = font.getWidth(label);
            matrices.push();
            matrices.scale(0.5F, 0.5F, 1.0F);
            font.draw(matrices,
                    label,
                    (x + zoom / 2f) * 2f - labelWidth / 2f,
                    (rulerY + zoom / 2f) * 2f - 4f,
                    0xFF000000);
            matrices.pop();
        }
    }

    /**
     * A replacement for the vanilla crosshair, which is lost with the HUD.
     *
     * <p>Deliberately a thin plain cross rather than a copy of the vanilla
     * texture: this one marks the exact centre pixel, which is the reference
     * the ruler counts from.
     */
    private static void drawCrosshair(MatrixStack matrices, Window window) {
        int cx = window.getScaledWidth() / 2;
        int cy = window.getScaledHeight() / 2;
        int arm = ToolscreenMobile.crosshairSize();
        int colour = 0xFFFFFFFF;
        DrawableHelper.fill(matrices, cx - arm, cy, cx + arm + 1, cy + 1, colour);
        DrawableHelper.fill(matrices, cx, cy - arm, cx + 1, cy + arm + 1, colour);
    }

    /** The reference axis: the boundary between the two centre pixels. */
    private static void drawCentreLine(MatrixStack matrices, int regionW,
                                       int panelX, int panelY, int panelH, int zoom) {
        int x = panelX + (regionW / 2) * zoom;
        DrawableHelper.fill(matrices, x, panelY, x + 1, panelY + panelH, 0xFFFFFFFF);
    }
}
