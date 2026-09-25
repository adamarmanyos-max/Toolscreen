package dev.toolscreen.mobile;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

/**
 * What the driver will actually accept, queried once.
 *
 * <p>Eye Measure asks for a framebuffer 16384 rows tall and blits it through a
 * viewport of the same height. Both have ceilings, and on mobile they are
 * routinely lower than the desktop parts this was designed against. Neither
 * limit reports a failure worth noticing: an oversized texture fails the
 * allocation, and an oversized viewport is silently clamped.
 *
 * <p>The clamped size is what gets recorded and logged, so the number typed into
 * Ninjabrain Bot is the one the game really rendered at rather than the one the
 * config asked for.
 */
public final class GlLimits {

    private static volatile int maxTexture;
    private static volatile int maxViewport;

    private GlLimits() {
    }

    /**
     * {@code value}, reduced to whatever the driver will accept.
     *
     * <p>Two ceilings, not one. The framebuffer is a texture, so
     * {@code GL_MAX_TEXTURE_SIZE} bounds it - but it is also blitted through a
     * viewport as tall as itself, and {@code GL_MAX_VIEWPORT_DIMS} is often
     * smaller. That second limit is the dangerous one: a viewport past it is
     * clamped rather than refused, and a clamped viewport moves the crop. The
     * visible slice stops being the middle of the frame, which is precisely
     * what the crop has to get right, because the crosshair is at the
     * framebuffer's centre. A crop that lands off-centre looks like the view
     * has been cut at one edge only.
     *
     * <p>So the render is sized to the smaller of the two. A shorter render
     * measures more coarsely; a mispositioned crop measures the wrong thing.
     */
    public static int clampTexture(int value) {
        int max = Math.min(orUnlimited(maxTextureSize()), orUnlimited(maxViewportHeight()));
        if (max == Integer.MAX_VALUE || max < 2 || value <= max) return value;
        int clamped = max % 2 == 0 ? max : max - 1;
        ToolscreenMobile.noteSizeClamp(value, clamped);
        return clamped;
    }

    /**
     * {@code GL_MAX_TEXTURE_SIZE}, or 0 before a context exists.
     *
     * <p>Cached after the first successful query. It cannot change for the life
     * of the context, and this is called from inside the resize path.
     */
    public static int maxTextureSize() {
        int cached = maxTexture;
        if (cached > 0) return cached;
        try {
            int queried = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (queried > 0) {
                maxTexture = queried;
                ToolscreenMobile.noteMaxTexture(queried);
            }
            return queried;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** {@code GL_MAX_VIEWPORT_DIMS}, height component, or 0 before a context exists. */
    public static int maxViewportHeight() {
        int cached = maxViewport;
        if (cached > 0) return cached;
        try {
            IntBuffer dims = BufferUtils.createIntBuffer(2);
            GL11.glGetIntegerv(GL11.GL_MAX_VIEWPORT_DIMS, dims);
            int height = dims.get(1);
            if (height > 0) {
                maxViewport = height;
                ToolscreenMobile.noteMaxViewport(dims.get(0), height);
            }
            return height;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int orUnlimited(int value) {
        return value > 0 ? value : Integer.MAX_VALUE;
    }
}
