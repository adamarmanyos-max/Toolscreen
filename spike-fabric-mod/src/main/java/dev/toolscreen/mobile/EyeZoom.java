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
 * <h2>Where the sample comes from</h2>
 *
 * The read happens after Minecraft has blitted its render target to the screen,
 * from inside the blit rectangle. Three things make that the right moment:
 * the default framebuffer is definitely bound, so there is no guessing about
 * what {@code glReadPixels} will read; the blit is 1:1, so one screen pixel
 * there is one game pixel, which is what the ruler has to count; and those
 * pixels were overwritten by the blit an instant earlier, so the panel cannot
 * photograph itself.
 *
 * <p>That last point is not hypothetical. Sampling at the end of
 * {@code GameRenderer.render} read the previous frame's screen rather than the
 * render target, so the panel magnified its own ruler - pink cells and numbers
 * blown up fifty times, with no terrain in sight.
 *
 * <h2>Where it is drawn</h2>
 *
 * In the letterbox beside the strip, like the original sits beside the game
 * window on the desktop. The panel is fitted to the letterbox rather than
 * placed at a configured fraction: a size that does not fit would otherwise
 * cover the very thing being measured.
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

        // The in-strip variant, for when there is no letterbox to put the panel
        // in. It draws the sample taken during the previous frame's blit, which
        // is a frame behind; the alternative is reading Minecraft's render
        // target while the HUD is being drawn into it, which is undefined.
        if (ToolscreenMobile.eyeZoomActive() && !ToolscreenMobile.eyeZoomSide()) {
            int[] pixels = lastPixels;
            if (pixels != null) {
                int zoomX = ToolscreenMobile.eyeZoomFactorX();
                int zoomY = ToolscreenMobile.eyeZoomFactorY();
                int panelW = lastRegionW * zoomX;
                int panelH = lastRegionH * zoomY;
                int panelX = (int) (window.getScaledWidth() * ToolscreenMobile.eyeZoomLeft()) - panelW / 2;
                int panelY = (int) (window.getScaledHeight() * ToolscreenMobile.eyeZoomTop()) - panelH / 2;

                drawPixels(matrices, pixels, lastRegionW, lastRegionH, panelX, panelY, zoomX, zoomY);
                drawRuler(matrices, font, lastRegionW, panelX, panelY, panelH, zoomX, zoomY);
                drawCentreLine(matrices, lastRegionW, panelX, panelY, panelH, zoomX);
            }
        }

        // Hiding the HUD takes the game's own crosshair with it, and a stretched
        // screen is useless for aiming without one.
        if (ToolscreenMobile.crosshairEnabled() && ToolscreenMobile.isOverrideActive()) {
            drawCrosshair(matrices, window);
        }
    }

    /**
     * Reads the pixels around the crosshair out of the just-blitted strip.
     *
     * <p>Coordinates are screen pixels with a bottom-left origin, which is the
     * same convention {@code blitX}/{@code blitY} already use, so the centre of
     * the strip is simply the centre of the blit rectangle.
     */
    private static void sampleFromScreen(int blitX, int blitY, int blitW, int blitH) {
        int regionW = Math.min(ToolscreenMobile.eyeZoomRegionWidth(), blitW);
        int regionH = Math.min(ToolscreenMobile.eyeZoomRegionHeight(), blitH);
        if (regionW < 2 || regionH < 2) return;

        int x0 = blitX + (blitW - regionW) / 2;
        int y0 = blitY + (blitH - regionH) / 2;
        if (x0 < 0 || y0 < 0) return;

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

        ToolscreenMobile.noteEyeZoomActive();
        lastPixels = out;
        lastRegionW = regionW;
        lastRegionH = regionH;
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

    /**
     * Samples the strip and draws the magnifier into the letterbox beside it.
     *
     * <p>Called once Minecraft has blitted its framebuffer to the screen, the
     * one moment in the frame when the full surface is addressable: everything
     * drawn earlier goes into Minecraft's own framebuffer, which <em>is</em>
     * the strip, so it can never reach the black.
     *
     * <h2>Fitted, not positioned</h2>
     *
     * The panel used to be placed at a configured fraction of the screen at
     * whatever size the zoom factors implied. Nothing checked that the result
     * fit, and at a strong zoom it did not: a 30-pixel region at 56x is 1680
     * pixels wide, which on a 2360-wide screen ran clear across the strip and
     * off the far edge, hiding the thing being measured.
     *
     * <p>So the letterbox decides the size now. The zoom factors are treated as
     * an upper bound and reduced until the panel fits beside the strip, which
     * means no combination of settings can put the panel over the game. The
     * wider of the two letterboxes is used, so this follows the strip when it
     * is aligned left or right rather than centred.
     */
    public static void renderSide(TextRenderer font, int blitX, int blitY, int blitW, int blitH) {
        if (!ToolscreenMobile.eyeZoomActive()) return;
        if (blitW < 2 || blitH < 2) return;

        // Sampled here rather than during the HUD pass: see the class comment.
        sampleFromScreen(blitX, blitY, blitW, blitH);

        if (!ToolscreenMobile.eyeZoomSide()) return;

        final int[] pixels = lastPixels;
        if (pixels == null) return;

        int realW = ToolscreenMobile.nativeWidth();
        int realH = ToolscreenMobile.nativeHeight();
        if (realW < 2 || realH < 2) return;

        final int regionW = lastRegionW;
        final int regionH = lastRegionH;
        if (regionW < 2 || regionH < 2) return;

        // The bands either side of the strip, in real screen pixels.
        int leftBox = blitX;
        int rightBox = realW - (blitX + blitW);
        boolean useLeft = leftBox >= rightBox;
        int boxStart = useLeft ? 0 : blitX + blitW;
        int boxWidth = useLeft ? leftBox : rightBox;

        int margin = Math.max(8, realW / 120);
        int availW = boxWidth - 2 * margin;
        int availH = realH - 2 * margin;
        // Nothing worth drawing into - Native mode, or a strip so wide there is
        // no surround. Skipping beats overlapping the game.
        if (availW < regionW || availH < regionH) return;

        final int zoomX = Math.min(ToolscreenMobile.eyeZoomFactorX(), availW / regionW);
        final int zoomY = Math.min(ToolscreenMobile.eyeZoomFactorY(), availH / regionH);
        if (zoomX < 1 || zoomY < 1) return;

        final int panelW = regionW * zoomX;
        final int panelH = regionH * zoomY;
        final int panelX = boxStart + margin + (availW - panelW) / 2;
        final int panelY = clamp((int) (realH * ToolscreenMobile.eyeZoomTop()) - panelH / 2,
                margin, realH - margin - panelH);

        withFullSurface(blitX, blitY, blitW, blitH, () -> {
            MatrixStack matrices = new MatrixStack();
            drawPixels(matrices, pixels, regionW, regionH, panelX, panelY, zoomX, zoomY);
            drawRuler(matrices, font, regionW, panelX, panelY, panelH, zoomX, zoomY);
            drawCentreLine(matrices, regionW, panelX, panelY, panelH, zoomX);
        });
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
