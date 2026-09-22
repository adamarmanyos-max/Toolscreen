package dev.toolscreen.mobile;

import org.lwjgl.opengl.GL11;

/**
 * The driver's maximum texture size, queried once.
 *
 * <p>Eye Measure asks for a framebuffer 16384 rows tall, which is exactly the
 * limit on the hardware it was designed for and above it on plenty of others.
 * Minecraft's render target is a texture, so exceeding the limit does not
 * degrade - the allocation fails and the frame is blank or the game crashes.
 * Asking for the largest size the driver will actually give is the difference
 * between a coarser measurement and none at all.
 *
 * <p>The clamped height is what gets recorded and logged, so the number typed
 * into Ninjabrain Bot is the one the game really rendered at rather than the one
 * the config asked for.
 */
public final class GlLimits {

    private static volatile int maxTexture;

    private GlLimits() {
    }

    /** {@code value}, reduced to the driver's limit and kept even. */
    public static int clampTexture(int value) {
        int max = maxTextureSize();
        if (max < 2 || value <= max) return value;
        int clamped = max;
        if (clamped % 2 != 0) clamped--;
        ToolscreenMobile.noteTextureClamp(value, clamped);
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
}
