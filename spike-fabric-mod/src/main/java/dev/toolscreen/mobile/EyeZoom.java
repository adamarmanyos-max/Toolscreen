package dev.toolscreen.mobile;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.BufferUtils;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
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
    /** {@code GL_FRAMEBUFFER}. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    private static final int RULER_BLUE = 0xFFADD8E6;
    private static final int RULER_PINK = 0xFFFFC0CB;

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

    /**
     * One panel per frame.
     *
     * <p>{@code drawInternal} is not guaranteed to run once a frame - shader
     * chains and resource-reload overlays go through it too - and a second
     * pass carries a different blit rectangle, so it places its panel
     * somewhere else entirely. {@code GameRenderer.render} does run once a
     * frame, so it arms the draw and the first blit afterwards consumes it.
     *
     * <p>Gated on {@code hookSeen} so that if the overlay injection ever
     * fails to attach, the panel still draws rather than waiting forever for
     * an arming call that never comes.
     */
    private static volatile boolean hookSeen;
    private static volatile boolean armed;

    private EyeZoom() {
    }

    /**
     * Draws the overlay, if the active mode asks for it.
     *
     * @param matrices HUD matrix stack, in scaled GUI units
     */
    public static void render(MatrixStack matrices, MinecraftClient client) {
        ToolscreenMobile.noteOverlayHookFired();
        hookSeen = true;
        armed = true;

        // Taken here, at the end of the frame's rendering: the framebuffer still
        // holds the frame and the crosshair has not been drawn over it yet.
        if (ToolscreenMobile.eyeZoomActive()) {
            sampleFramebuffer(client);
        }

    }

    /**
     * Reads the pixels around the crosshair out of Minecraft's own framebuffer.
     *
     * <p>Bound explicitly for the read rather than trusting whatever happens to
     * be bound - an earlier version read the default framebuffer by accident and
     * photographed the previous frame's screen, magnifying its own ruler.
     *
     * <p>It has to be the framebuffer rather than the screen because the two are
     * no longer the same picture: the framebuffer is 16384 rows tall and only
     * its middle slice is ever displayed. Sampling the display would measure a
     * crop, and would also catch our own crosshair, which is drawn over the
     * strip after this runs.
     */
    private static void sampleFramebuffer(MinecraftClient client) {
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer == null) return;

        int fbWidth = framebuffer.textureWidth;
        int fbHeight = framebuffer.textureHeight;
        // Exactly enough of the frame to fill the panel at the current zoom.
        //
        // Derived rather than configured, because the panel is a fixed size: the
        // zoom decides how much appears in it, so the sample has to follow the
        // zoom or the two disagree. They did - the panel was asking for as many
        // rows as it could draw while this returned a fixed sixty, so lowering
        // the vertical zoom showed the same sixty rows drawn smaller instead of
        // more of the eye. Sampling beyond the panel would only be cropped away.
        int panelW = ToolscreenMobile.panelPixelWidth();
        int panelH = ToolscreenMobile.panelPixelHeight();
        if (panelW < 32 || panelH < 32) return;

        int regionW = Math.min(columnsFor(panelW, ToolscreenMobile.effectiveZoomX()), fbWidth);
        int regionH = Math.min(rowsFor(panelH, ToolscreenMobile.effectiveZoomY()), fbHeight);
        if (regionW < 2 || regionH < 1) return;

        // Integer halves on both axes, so the boundary between the two middle
        // columns is exactly the framebuffer's centre line - the same line the
        // divider is drawn on, and the column the crosshair sits in.
        int x0 = fbWidth / 2 - regionW / 2;
        int y0 = fbHeight / 2 - regionH / 2;
        if (x0 < 0 || y0 < 0) return;

        int needed = regionW * regionH * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < needed) {
            pixelBuffer = BufferUtils.createByteBuffer(needed);
        }
        pixelBuffer.clear();

        int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        GlStateManager.bindFramebuffer(GL_FRAMEBUFFER, framebuffer.fbo);
        GlStateManager.readPixels(x0, y0, regionW, regionH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);
        GlStateManager.bindFramebuffer(GL_FRAMEBUFFER, previous);

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
                                   int panelX, int panelY, int panelW, int panelH,
                                   int zoomX, int zoomY) {
        // Centred in the fixed panel and clipped by it. Integer division leaves
        // up to one zoom step of slack; splitting it keeps the centre column on
        // the panel's centre, which is where the divider goes.
        int drawnW = Math.min(panelW, regionW * zoomX);
        int drawnH = Math.min(panelH, regionH * zoomY);
        int left = panelX + (panelW - drawnW) / 2;
        int top = panelY + (panelH - drawnH) / 2;

        int rows = Math.min(regionH, drawnH / zoomY);
        int columns = Math.min(regionW, drawnW / zoomX);

        for (int row = 0; row < rows; row++) {
            int rowStart = row * regionW;
            int y = top + row * zoomY;
            int col = 0;
            while (col < columns) {
                int colour = pixels[rowStart + col];
                int run = 1;
                while (col + run < columns && pixels[rowStart + col + run] == colour) {
                    run++;
                }
                int x = left + col * zoomX;
                DrawableHelper.fill(matrices, x, y, x + run * zoomX, y + zoomY, colour);
                col += run;
            }
        }
    }

    /**
     * The ruler: one cell per framebuffer pixel, numbered outward from the
     * centre, in a band of its own height across the middle of the panel.
     *
     * <p>Colours alternate strictly left to right across the whole run rather
     * than mirroring about the centre. Mirroring gave both cells marked 1 the
     * same colour, so they merged into one double-width block straddling the
     * divider - the one place on the ruler where the eye most needs an edge.
     *
     * <p>The band's height is its own rather than the vertical zoom. Measured
     * off four of the original's screenshots a cell is 0.96, 1.52, 2.35 and 2.49
     * times as tall as it is wide, never shorter; tying it to the zoom once made
     * it half as wide as it was tall, the one proportion the original never
     * shows.
     */
    private static void drawRuler(MatrixStack matrices, int panelX, int panelY,
                                  int panelW, int panelH, int zoomX, int rulerH) {
        int centreX = panelX + panelW / 2;
        int rulerY = panelY + panelH / 2 - rulerH / 2;
        int max = ToolscreenMobile.eyeZoomRulerMax();
        int right = panelX + panelW;

        // Laid out from the divider outward rather than across the sample, so a
        // cell stays one framebuffer pixel whatever the panel happens to fit and
        // cells falling outside the fixed panel are simply not drawn.
        for (int i = -max; i < max; i++) {
            int x = centreX + i * zoomX;
            if (x + zoomX <= panelX || x >= right) continue;

            int x0 = Math.max(x, panelX);
            int x1 = Math.min(x + zoomX, right);
            DrawableHelper.fill(matrices, x0, rulerY, x1, rulerY + rulerH,
                    (Math.floorMod(i, 2) == 0) ? RULER_BLUE : RULER_PINK);
            DrawableHelper.fill(matrices, x0, rulerY, Math.min(x0 + 1, x1), rulerY + rulerH, 0x40000000);

            if (x < panelX || x + zoomX > right) continue;
            String label = Integer.toString(i < 0 ? -i : i + 1);
            int scale = Digits.fitScale(label, zoomX - 2, rulerH - 2);
            int textX = x + (zoomX - Digits.width(label, scale)) / 2;
            int textY = rulerY + (rulerH - Digits.height(scale)) / 2;
            Digits.draw(matrices, label, textX, textY, scale, 0xFF000000);
        }
    }

    /**
     * The vanilla crosshair, drawn small over the game window.
     *
     * <p>Minecraft's own texture rather than a hand-drawn cross: it is the shape
     * the eye already knows where to look for, and the earlier plain cross read
     * as a different tool. It is drawn at a fraction of its normal size because
     * the window is a narrow strip and a full-size crosshair covers a large part
     * of it - including pixels being measured.
     *
     * <p>Drawn in the screen pass, after the sample. Drawing it into Minecraft's
     * framebuffer instead put it in the picture the magnifier photographs: its
     * arms came back as white slabs across the panel. The crosshair marks the
     * pixel being measured, so it must never be part of the measurement.
     *
     * <p>The blend function is vanilla's, which inverts what is behind it rather
     * than painting over it - that is what keeps it visible against both sky and
     * stone. If a translation layer mishandles it, {@code crosshairVanilla=false}
     * falls back to the plain cross.
     */
    private static void drawCrosshair(MatrixStack matrices, int blitX, int blitY, int blitW, int blitH) {
        if (!ToolscreenMobile.crosshairEnabled()) return;

        int cx = blitX + blitW / 2;
        int cy = blitY + blitH / 2;

        if (ToolscreenMobile.crosshairVanilla()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getTextureManager() != null) {
                drawVanillaCrosshair(matrices, client, cx, cy, vanillaScale(blitW));
                return;
            }
        }

        int scale = Math.max(1, blitW / 64);
        int arm = ToolscreenMobile.crosshairSize() * scale;
        int gap = ToolscreenMobile.crosshairGap() * scale;
        int thickness = Math.max(1, scale);
        int colour = 0xFFFFFFFF;

        // Four separate arms with a gap at the centre, rather than a solid plus.
        // The gap leaves the exact pixel being measured visible.
        DrawableHelper.fill(matrices, cx - gap - arm, cy, cx - gap, cy + thickness, colour);
        DrawableHelper.fill(matrices, cx + gap + thickness, cy, cx + gap + thickness + arm, cy + thickness, colour);
        DrawableHelper.fill(matrices, cx, cy - gap - arm, cx + thickness, cy - gap, colour);
        DrawableHelper.fill(matrices, cx, cy + gap + thickness, cx + thickness, cy + gap + thickness + arm, colour);
    }

    /**
     * How many screen pixels one crosshair texture pixel becomes.
     *
     * <p>Derived from the strip width so it stays the same size relative to the
     * window on any device, then scaled by the configured multiplier. At the
     * default the 15-pixel texture lands at about a tenth of the strip's width.
     */
    private static float vanillaScale(int blitW) {
        float derived = Math.max(1.0F, blitW / 150.0F);
        return Math.max(0.25F, derived * (float) ToolscreenMobile.crosshairScale());
    }

    private static void drawVanillaCrosshair(MatrixStack matrices, MinecraftClient client,
                                             int cx, int cy, float scale) {
        client.getTextureManager().bindTexture(DrawableHelper.GUI_ICONS_TEXTURE);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SrcFactor.ONE_MINUS_DST_COLOR, GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);

        matrices.push();
        matrices.translate(cx, cy, 0.0D);
        matrices.scale(scale, scale, 1.0F);
        // The texture is 15x15 at the top-left of icons.png; shifting by half of
        // that centres it on the crosshair rather than on its own corner.
        matrices.translate(-7.5D, -7.5D, 0.0D);
        DrawableHelper.drawTexture(matrices, 0, 0, 0.0F, 0.0F, 15, 15, 256, 256);
        matrices.pop();

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** The reference axis: the boundary between the two centre pixels. */
    private static void drawCentreLine(MatrixStack matrices, int panelX, int panelY,
                                       int panelW, int panelH) {
        int x = panelX + panelW / 2;
        // Two pixels wide: at these zooms a hairline all but vanished
        // against the terrain, and this is the axis everything is read from.
        DrawableHelper.fill(matrices, x - 1, panelY, x + 1, panelY + panelH, 0xFFFFFFFF);
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
    public static void renderSide(int blitX, int blitY, int blitW, int blitH) {
        if (!ToolscreenMobile.eyeZoomActive()) return;
        if (blitW < 2 || blitH < 2) return;
        if (!ToolscreenMobile.eyeZoomSide()) return;
        if (hookSeen) {
            if (!armed) return;
            armed = false;
        }

        final int[] pixels = lastPixels;
        if (pixels == null) return;

        int realW = ToolscreenMobile.nativeWidth();
        int realH = ToolscreenMobile.nativeHeight();
        if (realW < 2 || realH < 2) return;

        final int regionW = lastRegionW;
        final int regionH = lastRegionH;
        if (regionW < 2 || regionH < 1) return;

        // The bands either side of the game window, in real screen pixels.
        int leftBox = blitX;
        int rightBox = realW - (blitX + blitW);
        boolean useLeft = leftBox >= rightBox;
        int boxStart = useLeft ? 0 : blitX + blitW;
        int boxWidth = useLeft ? leftBox : rightBox;

        int margin = Math.max(8, realW / 120);
        int availW = boxWidth - 2 * margin;
        int availH = realH - 2 * margin;
        if (availW < 32 || availH < 32) return;

        // A fixed size, not one derived from the zoom.
        //
        // The panel used to be region times zoom, so turning the zoom down made
        // it shrink and turning it up made it grow until it no longer fitted.
        // Its size is now a property of the screen and the zoom only decides how
        // much of the frame appears inside it: less zoom shows more of the eye,
        // rather than a smaller picture of the same amount.
        final int panelW = Math.min(availW,
                (int) Math.round(realH * ToolscreenMobile.panelHeightFraction()
                        * ToolscreenMobile.panelAspect()));
        final int panelH = Math.min(availH,
                (int) Math.round(realH * ToolscreenMobile.panelHeightFraction()));
        if (panelW < 32 || panelH < 32) return;

        final int panelX = useLeft
                ? clamp(boxStart + margin + (availW - panelW) / 2, 0, blitX - panelW)
                : clamp(boxStart + margin + (availW - panelW) / 2, blitX + blitW, realW - panelW);
        final int panelY = clamp((int) (realH * ToolscreenMobile.eyeZoomTop()) - panelH / 2,
                margin, realH - margin - panelH);

        ToolscreenMobile.notePanelGeometry(realW, realH, blitX, blitW, panelX, panelY, panelW, panelH);

        // Last line of defence. The arithmetic above already said the panel fits
        // beside the game window, and once it did not - measured off a
        // screenshot it covered 219 of the strip's 220 pixels. Overlapping hides
        // the thing being measured, which is worse than showing no panel.
        if (panelX < blitX + blitW && panelX + panelW > blitX) {
            ToolscreenMobile.notePanelSuppressed(panelX, panelW, blitX, blitW);
            return;
        }

        final int zoomX = ToolscreenMobile.effectiveZoomX();
        final int zoomY = ToolscreenMobile.effectiveZoomY();
        final int rulerH = clamp(zoomX, 12, Math.max(12, panelH / 6));

        withFullSurface(blitX, blitY, blitW, blitH, () -> {
            MatrixStack matrices = new MatrixStack();
            // Painted first, at the fixed size, so the panel keeps its outline
            // even where the magnified image does not divide into it exactly.
            DrawableHelper.fill(matrices, panelX, panelY, panelX + panelW, panelY + panelH, 0xFF101014);
            drawPixels(matrices, pixels, regionW, regionH, panelX, panelY, panelW, panelH, zoomX, zoomY);
            drawRuler(matrices, panelX, panelY, panelW, panelH, zoomX, rulerH);
            drawCentreLine(matrices, panelX, panelY, panelW, panelH);
            drawCrosshair(matrices, blitX, blitY, blitW, blitH);
        });
    }

    /** How many framebuffer columns the panel can show at the current zoom. */
    public static int columnsFor(int panelWidth, int zoomX) {
        int columns = panelWidth / Math.max(1, zoomX);
        if (columns % 2 != 0) columns--;
        return Math.max(2, columns);
    }

    /** How many framebuffer rows the panel can show at the current zoom. */
    public static int rowsFor(int panelHeight, int zoomY) {
        return Math.max(1, panelHeight / Math.max(1, zoomY));
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
