package dev.toolscreen.mobile;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.BufferUtils;
import com.mojang.blaze3d.platform.GlStateManager;
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

    /** {@code GL_FRAMEBUFFER_BINDING}; not exposed by the GL11 bindings. */
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    private static ByteBuffer pixelBuffer;

    /**
     * The most recent sample, kept so the side panel can be drawn later in the
     * frame than it was taken.
     *
     * <p>The sample has to happen while Minecraft's own framebuffer is bound,
     * before our crosshair goes down. The side panel is drawn after that
     * framebuffer has been blitted to the screen, which is the only moment the
     * letterboxed area is addressable. Those are different points in the frame,
     * so the pixels are carried between them.
     */
    private static int[] lastPixels;
    private static int lastRegionW;
    private static int lastRegionH;

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

        boolean overrideActive = ToolscreenMobile.isOverrideActive();

        // Order matters. The pixels are read before anything of ours is drawn,
        // because the sample is taken at the centre of the screen and our own
        // crosshair sits exactly there: draw first and the magnifier shows a
        // giant crosshair rather than the target.
        if (ToolscreenMobile.eyeZoomActive()) {
            int fbWidth = window.getFramebufferWidth();
            int fbHeight = window.getFramebufferHeight();
            int regionW = Math.min(ToolscreenMobile.eyeZoomRegionWidth(), fbWidth);
            int regionH = Math.min(ToolscreenMobile.eyeZoomRegionHeight(), fbHeight);

            if (regionW >= 2 && regionH >= 2) {
                int[] pixels = readRegion(fbWidth, fbHeight, regionW, regionH);
                if (pixels != null) {
                    ToolscreenMobile.noteEyeZoomActive();
                    lastPixels = pixels;
                    lastRegionW = regionW;
                    lastRegionH = regionH;

                    if (!ToolscreenMobile.eyeZoomSide()) {
                        int zoomX = ToolscreenMobile.eyeZoomFactorX();
                        int zoomY = ToolscreenMobile.eyeZoomFactorY();
                        int panelW = regionW * zoomX;
                        int panelH = regionH * zoomY;
                        int panelX = (int) (window.getScaledWidth() * ToolscreenMobile.eyeZoomLeft()) - panelW / 2;
                        int panelY = (int) (window.getScaledHeight() * ToolscreenMobile.eyeZoomTop()) - panelH / 2;

                        drawPixels(matrices, pixels, regionW, regionH, panelX, panelY, zoomX, zoomY);
                        drawRuler(matrices, font, regionW, panelX, panelY, panelH, zoomX, zoomY);
                        drawCentreLine(matrices, regionW, panelX, panelY, panelH, zoomX);
                    }
                }
            }
        }

        // Drawn last, and only after sampling. Hiding the HUD takes the game's
        // own crosshair with it, and a stretched screen is useless for aiming
        // without one.
        if (ToolscreenMobile.crosshairEnabled() && overrideActive) {
            drawCrosshair(matrices, window);
        }
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

    /**
     * Each sampled pixel becomes one {@code zoom}-sized square.
     *
     * <p>Horizontally adjacent pixels of the same colour are merged into a
     * single rectangle first. At the sizes the original uses — around 30x36 —
     * drawing one quad per pixel would be over a thousand draw calls every
     * frame, which is a lot to ask of a translation layer on a tablet. Terrain
     * is full of runs of identical colour, so this usually collapses to a small
     * fraction of that, and it is exact rather than approximate: the output is
     * identical, just fewer calls.
     */
    private static void drawPixels(MatrixStack matrices, int[] pixels, int regionW, int regionH,
                                   int panelX, int panelY, int zoomX, int zoomY) {
        for (int row = 0; row < regionH; row++) {
            int rowStart = row * regionW;
            int y = panelY + row * zoomY;
            int col = 0;
            while (col < regionW) {
                int colour = pixels[rowStart + col];
                int run = 1;
                while (col + run < regionW && pixels[rowStart + col + run] == colour) {
                    run++;
                }
                int x = panelX + col * zoomX;
                DrawableHelper.fill(matrices, x, y, x + run * zoomX, y + zoomY, colour);
                col += run;
            }
        }
    }

    /**
     * The ruler: one cell per game pixel, numbered outward from the centre,
     * alternating colours so a long run stays countable.
     */
    private static void drawRuler(MatrixStack matrices, TextRenderer font, int regionW,
                                  int panelX, int panelY, int panelH, int zoomX, int zoomY) {
        int centreCol = regionW / 2;
        int rulerY = panelY + panelH / 2 - zoomY / 2;
        int max = ToolscreenMobile.eyeZoomRulerMax();

        for (int col = 0; col < regionW; col++) {
            int offset = col < centreCol ? centreCol - col : col - centreCol + 1;
            if (offset > max) continue;

            int x = panelX + col * zoomX;
            int colour = (offset % 2 == 0) ? 0xFFADD8E6 : 0xFFFFC0CB;
            DrawableHelper.fill(matrices, x, rulerY, x + zoomX, rulerY + zoomY, colour);
            // Hairline between cells, so a long run stays countable by eye.
            DrawableHelper.fill(matrices, x, rulerY, x + 1, rulerY + zoomY, 0x40000000);

            String label = Integer.toString(offset);
            int labelWidth = font.getWidth(label);
            if (labelWidth <= 0) continue;

            // Scaled to the cell rather than fixed. A cell is `zoom` wide, and
            // zoom varies hugely: single figures inside the strip, tens out in
            // the letterbox. A fixed size is either unreadable at one end or
            // overflows at the other.
            float scale = Math.min(3.0F, Math.max(0.5F, (zoomX * 0.8F) / labelWidth));

            matrices.push();
            matrices.scale(scale, scale, 1.0F);
            font.draw(matrices,
                    label,
                    (x + zoomX / 2f) / scale - labelWidth / 2f,
                    (rulerY + zoomY / 2f) / scale - font.fontHeight / 2f,
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
        int gap = ToolscreenMobile.crosshairGap();
        int colour = 0xFFFFFFFF;

        // Four separate arms with a gap at the centre, rather than a solid
        // plus. The gap leaves the exact pixel being measured visible, which a
        // filled centre would cover - and that pixel is the whole point.
        DrawableHelper.fill(matrices, cx - gap - arm, cy, cx - gap, cy + 1, colour);
        DrawableHelper.fill(matrices, cx + gap + 1, cy, cx + gap + 1 + arm, cy + 1, colour);
        DrawableHelper.fill(matrices, cx, cy - gap - arm, cx + 1, cy - gap, colour);
        DrawableHelper.fill(matrices, cx, cy + gap + 1, cx + 1, cy + gap + 1 + arm, colour);
    }

    /** The reference axis: the boundary between the two centre pixels. */
    private static void drawCentreLine(MatrixStack matrices, int regionW,
                                       int panelX, int panelY, int panelH, int zoomX) {
        int x = panelX + (regionW / 2) * zoomX;
        DrawableHelper.fill(matrices, x, panelY, x + 1, panelY + panelH, 0xFFFFFFFF);
    }

    /**
     * Draws the panel into the letterboxed area beside the strip.
     *
     * <p>Called after Minecraft has blitted its framebuffer to the screen, the
     * one moment in the frame when the full surface is addressable: everything
     * drawn earlier goes into Minecraft's own framebuffer, which <em>is</em>
     * the strip, so it can never reach the black.
     *
     * <p>Sets up a viewport and orthographic projection covering the whole
     * surface, mirroring the sequence Minecraft itself uses a few lines
     * earlier, then restores both. Coordinates here are real screen pixels,
     * not GUI units, because outside the strip there is no GUI scale.
     */
    /**
     * Fills the letterboxed area around the strip, replacing the bare black.
     *
     * <p>The original does this too — its localization table carries
     * {@code modes.background}, {@code modes.bg_image_path} and
     * {@code modes.color_stops} — so the colour behind the game is part of the
     * tool rather than the desktop showing through.
     *
     * <p>Only the four bands around the blit are painted. Filling the whole
     * surface would paint over the strip that was just drawn into it.
     */
    public static void renderBackground(int blitX, int blitY, int blitW, int blitH) {
        if (!ToolscreenMobile.isOverrideActive()) return;

        int realW = ToolscreenMobile.nativeWidth();
        int realH = ToolscreenMobile.nativeHeight();
        ToolscreenMobile.noteSurfaceGeometry(realW, realH, blitX, blitY, blitW, blitH);

        if (!ToolscreenMobile.backgroundEnabled()) return;
        if (realW < 2 || realH < 2) return;

        // Only paint when the default framebuffer is bound.
        //
        // Twice now this fill has covered the game rather than the letterbox,
        // and the likeliest reason is that at this point Minecraft's own
        // framebuffer is still bound - so the gradient lands on top of the
        // world, which is then blitted to the screen. Rather than assume either
        // way, ask. If anything other than the screen is bound, paint nothing:
        // a missing background is a cosmetic loss, painting over the world is
        // not.
        int boundFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        ToolscreenMobile.noteFramebufferBinding(boundFramebuffer);
        if (boundFramebuffer != 0) return;

        final int from = ToolscreenMobile.backgroundTop();
        final int to = ToolscreenMobile.backgroundBottom();
        final int w = realW;
        final int h = realH;

        // Painted across the whole surface, before Minecraft blits the strip
        // over the top of it. The first attempt filled only the four bands
        // around the strip, which meant the fill had to agree exactly with the
        // blit's position and the surface size - get either wrong and it covers
        // the game, which is what happened. Painting underneath cannot: the
        // strip is drawn afterwards, so the worst a wrong number can do here is
        // leave a band unpainted.
        withFullSurface(blitX, blitY, blitW, blitH, () -> {
            MatrixStack matrices = new MatrixStack();
            int bands = 48;
            for (int i = 0; i < bands; i++) {
                int y0 = h * i / bands;
                int y1 = h * (i + 1) / bands;
                DrawableHelper.fill(matrices, 0, y0, w, y1, lerpColour(from, to, i / (float) (bands - 1)));
            }
        });
    }

    private static int lerpColour(int from, int to, float t) {
        int a = 0xFF;
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Runs {@code body} with a viewport and projection covering the whole
     * surface, restoring both afterwards.
     */
    private static void withFullSurface(int blitX, int blitY, int blitW, int blitH, Runnable body) {
        int realW = ToolscreenMobile.nativeWidth();
        int realH = ToolscreenMobile.nativeHeight();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, realW, realH, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0.0F, 0.0F, -2000.0F);
        GlStateManager.viewport(0, 0, realW, realH);

        try {
            body.run();
        } finally {
            GlStateManager.viewport(blitX, blitY, blitW, blitH);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        }
    }

    public static void renderSide(TextRenderer font, int blitX, int blitY, int blitW, int blitH) {
        if (!ToolscreenMobile.eyeZoomSide() || !ToolscreenMobile.eyeZoomActive()) return;

        final int[] pixels = lastPixels;
        if (pixels == null) return;

        int realW = ToolscreenMobile.nativeWidth();
        int realH = ToolscreenMobile.nativeHeight();
        if (realW < 2 || realH < 2) return;

        final int regionW = lastRegionW;
        final int regionH = lastRegionH;
        final int zoomX = ToolscreenMobile.eyeZoomFactorX();
        final int zoomY = ToolscreenMobile.eyeZoomFactorY();
        final int panelW = regionW * zoomX;
        final int panelH = regionH * zoomY;
        final int panelX = (int) (realW * ToolscreenMobile.eyeZoomLeft()) - panelW / 2;
        final int panelY = (int) (realH * ToolscreenMobile.eyeZoomTop()) - panelH / 2;

        withFullSurface(blitX, blitY, blitW, blitH, () -> {
            MatrixStack matrices = new MatrixStack();
            drawPixels(matrices, pixels, regionW, regionH, panelX, panelY, zoomX, zoomY);
            drawRuler(matrices, font, regionW, panelX, panelY, panelH, zoomX, zoomY);
            drawCentreLine(matrices, regionW, panelX, panelY, panelH, zoomX);
        });
    }
}
