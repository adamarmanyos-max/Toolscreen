package dev.toolscreen.mobile;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Toolscreen's EyeZoom, ported: a magnified clone of the framebuffer pixels
 * around the crosshair, with a centre divider and a ruler counting offsets.
 *
 * <h2>The one invariant</h2>
 *
 * <strong>One ruler cell is one framebuffer pixel.</strong> The number read off
 * this ruler goes into Ninjabrain Bot, which converts it to an angle using the
 * render height; a cell that stood for anything else - a screen pixel, a
 * fraction of one - would feed the calculator a wrong number while looking
 * perfectly reasonable. So the horizontal magnification is not a setting. It is
 * the ruler cell width, and {@link Layout} picks that as a whole number of
 * pixels so every magnified column is identical.
 *
 * <p>Vertically nothing is magnified: one framebuffer row per panel row. The
 * panel is the game's own view with the horizontal axis stretched and the
 * vertical axis left alone, which is why terrain in it reads at the same height
 * as terrain in the game window.
 *
 * <h2>Where the sample comes from</h2>
 *
 * Minecraft's own framebuffer, bound explicitly for the read. That framebuffer
 * is much taller than the screen - 16384 rows against 1940 - and only a crop of
 * it is ever displayed, so sampling what the monitor shows would sample a
 * different image from the one being measured. Binding it by hand also removes
 * the guesswork: an earlier version read whatever happened to be bound and
 * photographed the previous frame's screen, magnifying its own ruler.
 */
public final class EyeZoom {

    /** {@code GL_FRAMEBUFFER}; not exposed by the GL11 bindings. */
    private static final int GL_FRAMEBUFFER = 0x8D40;
    /** {@code GL_FRAMEBUFFER_BINDING}. */
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    private static final int RULER_BLUE = 0xFFADD8E6;
    private static final int RULER_PINK = 0xFFFFC0CB;
    private static final int DIVIDER = 0xFFFFFFFF;

    private static ByteBuffer pixelBuffer;

    /** The most recent sample: {@code columns} wide, {@code rows} tall, ARGB, top row first. */
    private static int[] lastPixels;
    private static int lastColumns;
    private static int lastRows;

    private EyeZoom() {
    }

    /**
     * Samples the framebuffer around the crosshair.
     *
     * <p>Called while Minecraft's framebuffer still holds the frame, before it
     * is blitted and before anything of ours is drawn over it. The crosshair is
     * drawn later, in the screen pass, precisely so that it cannot end up in
     * this picture: it marks the pixel being measured, so it must never be part
     * of the measurement.
     */
    public static void sample(MinecraftClient client, Layout layout) {
        if (layout == null) return;

        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer == null) return;

        int fbWidth = framebuffer.textureWidth;
        int fbHeight = framebuffer.textureHeight;
        int columns = Math.min(layout.columns, fbWidth);
        int stride = Math.max(1, ToolscreenMobile.verticalStride());
        int rows = Math.min(layout.panelHeight * stride, fbHeight);
        if (columns < 2 || rows < 2) return;

        // Centred on the crosshair, which is the centre of the framebuffer.
        // Integer halves on both axes so the boundary between the two middle
        // columns is exactly the framebuffer's centre line - the same line the
        // divider is drawn on.
        int x0 = fbWidth / 2 - columns / 2;
        int y0 = fbHeight / 2 - rows / 2;
        if (x0 < 0 || y0 < 0) return;

        int needed = columns * rows * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < needed) {
            pixelBuffer = BufferUtils.createByteBuffer(needed);
        }
        pixelBuffer.clear();

        int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        GlStateManager.bindFramebuffer(GL_FRAMEBUFFER, framebuffer.fbo);
        GlStateManager.readPixels(x0, y0, columns, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);
        GlStateManager.bindFramebuffer(GL_FRAMEBUFFER, previous);

        int outRows = rows / stride;
        int[] out = new int[columns * outRows];
        for (int row = 0; row < outRows; row++) {
            // glReadPixels returns rows bottom-up; flip so row 0 is the top.
            int source = (rows - 1 - row * stride) * columns * 4;
            for (int col = 0; col < columns; col++) {
                int i = source + col * 4;
                int r = pixelBuffer.get(i) & 0xFF;
                int g = pixelBuffer.get(i + 1) & 0xFF;
                int b = pixelBuffer.get(i + 2) & 0xFF;
                out[row * columns + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        ToolscreenMobile.noteEyeZoomActive();
        lastPixels = out;
        lastColumns = columns;
        lastRows = outRows;
    }

    /**
     * Draws the panel, the ruler, the divider and the crosshair.
     *
     * <p>Called after the blit, the one moment in the frame when the whole
     * surface can be drawn to: everything before it goes into Minecraft's
     * framebuffer, which is the game window, so it could never reach the
     * letterbox beside it.
     */
    public static void render(MatrixStack matrices, Layout layout) {
        if (layout == null) return;

        int[] pixels = lastPixels;
        if (pixels != null && lastColumns >= 2 && lastRows >= 1 && !layout.overlapsGame()) {
            drawPixels(matrices, layout, pixels);
            drawRuler(matrices, layout);
            drawDivider(matrices, layout);
        }

        drawCrosshair(matrices, layout);
    }

    /**
     * One rectangle per framebuffer pixel, horizontal runs merged.
     *
     * <p>Every column is {@code cellWidth} wide and every row one pixel tall, by
     * construction rather than by rounding: a column that came out a pixel wider
     * than its neighbour would put the ruler out of step with the image it is
     * measuring.
     */
    private static void drawPixels(MatrixStack matrices, Layout layout, int[] pixels) {
        int cell = layout.cellWidth;
        int left = layout.pixelsX();
        int top = layout.panelY;
        int rows = Math.min(lastRows, layout.panelHeight);

        for (int row = 0; row < rows; row++) {
            int rowStart = row * lastColumns;
            int y = top + row;
            int col = 0;
            while (col < lastColumns) {
                int colour = pixels[rowStart + col];
                int run = 1;
                while (col + run < lastColumns && pixels[rowStart + col + run] == colour) {
                    run++;
                }
                int x = left + col * cell;
                DrawableHelper.fill(matrices, x, y, x + run * cell, y + 1, colour);
                col += run;
            }
        }
    }

    /**
     * The ruler: one cell per framebuffer pixel, numbered outward from the
     * divider, in a band of its own height across the middle of the panel.
     *
     * <p>Colours alternate strictly left to right across the whole run, not
     * mirrored about the centre. Mirroring put the same colour on both cells
     * marked 1, which merged them into one double-width block straddling the
     * divider - the one place on the ruler where the eye most needs an edge.
     */
    private static void drawRuler(MatrixStack matrices, Layout layout) {
        int cells = layout.rulerCells;
        int half = cells / 2;
        int cell = layout.cellWidth;
        int y = layout.rulerY;
        int height = layout.rulerHeight;

        for (int i = 0; i < cells; i++) {
            int x = layout.centreX + (i - half) * cell;
            DrawableHelper.fill(matrices, x, y, x + cell, y + height,
                    (i % 2 == 0) ? RULER_BLUE : RULER_PINK);
            DrawableHelper.fill(matrices, x, y, x + 1, y + height, 0x40000000);

            String label = Integer.toString(i < half ? half - i : i - half + 1);
            int scale = Digits.fitScale(label, cell - 2, height - 2);
            int textX = x + (cell - Digits.width(label, scale)) / 2;
            int textY = y + (height - Digits.height(scale)) / 2;
            Digits.draw(matrices, label, textX, textY, scale, 0xFF000000);
        }
    }

    /** The reference axis: the boundary between the two middle framebuffer pixels. */
    private static void drawDivider(MatrixStack matrices, Layout layout) {
        DrawableHelper.fill(matrices, layout.centreX - 1, layout.panelY,
                layout.centreX + 1, layout.panelY + layout.panelHeight, DIVIDER);
    }

    /**
     * A replacement for the vanilla crosshair, which is lost with the HUD.
     *
     * <p>Drawn over the game window in screen pixels, after the sample. A thin
     * plain cross with a gap at the centre rather than a copy of the vanilla
     * texture: the gap leaves the pixel being measured visible, and that pixel
     * is the whole point.
     */
    private static void drawCrosshair(MatrixStack matrices, Layout layout) {
        if (!ToolscreenMobile.crosshairEnabled()) return;

        int cx = layout.gameX + layout.gameWidth / 2;
        int cy = layout.gameY + layout.gameHeight / 2;
        int scale = Math.max(1, layout.gameWidth / 64);
        int arm = ToolscreenMobile.crosshairSize() * scale;
        int gap = ToolscreenMobile.crosshairGap() * scale;
        int t = Math.max(1, scale);

        DrawableHelper.fill(matrices, cx - gap - arm, cy, cx - gap, cy + t, DIVIDER);
        DrawableHelper.fill(matrices, cx + gap + t, cy, cx + gap + t + arm, cy + t, DIVIDER);
        DrawableHelper.fill(matrices, cx, cy - gap - arm, cx + t, cy - gap, DIVIDER);
        DrawableHelper.fill(matrices, cx, cy + gap + t, cx + t, cy + gap + t + arm, DIVIDER);
    }

    /**
     * Fills the area around the game window, replacing the bare black.
     *
     * <p>Painted before the blit, so the game is drawn over the top of it: a
     * fill placed afterwards has to agree exactly with where the game landed,
     * and when it did not it covered the game instead.
     */
    public static void renderBackground(MatrixStack matrices, Layout layout) {
        if (!ToolscreenMobile.backgroundEnabled()) return;

        int from = ToolscreenMobile.backgroundTop();
        int to = ToolscreenMobile.backgroundBottom();
        int bands = 48;
        for (int i = 0; i < bands; i++) {
            int y0 = layout.screenHeight * i / bands;
            int y1 = layout.screenHeight * (i + 1) / bands;
            DrawableHelper.fill(matrices, 0, y0, layout.screenWidth, y1,
                    lerpColour(from, to, i / (float) (bands - 1)));
        }
    }

    private static int lerpColour(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
