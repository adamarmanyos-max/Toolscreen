package dev.toolscreen.mobile;

/**
 * A screen shape, equivalent to one entry under Toolscreen's "Modes" tab.
 *
 * <p>Each dimension is either a <strong>fraction</strong> of the device's native
 * surface or an <strong>absolute pixel count</strong>, decided by magnitude: a
 * value of {@code 1.0} or less is a fraction, anything larger is pixels. So
 * {@code 0.2} is a fifth of the screen, while {@code 280} is 280 pixels.
 *
 * <p>Both exist because they answer different needs. Fractions travel across an
 * iPhone, an iPad and an external display, which absolute pixels cannot.
 * Absolute pixels let a Toolscreen preset be copied over exactly — that tool
 * stores {@code game_width}/{@code game_height} in pixels, and a desktop monitor
 * is a fixed known size, so its published mode dimensions are only meaningful
 * as pixels.
 *
 * <p>A mode whose dimensions are both the fraction 1.0 is the pass-through /
 * native mode.
 */
public record Mode(String name, double width, double height) {

    public Mode {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mode name must not be blank");
        }
        width = validate(width);
        height = validate(height);
    }

    private static double validate(double value) {
        if (Double.isNaN(value) || value <= 0.0) return 1.0;
        return value;
    }

    /** True when this dimension is a fraction of the screen rather than a pixel count. */
    private static boolean isFraction(double value) {
        return value <= 1.0;
    }

    public boolean isNative() {
        return isFraction(width) && width >= 1.0 && isFraction(height) && height >= 1.0;
    }

    /**
     * Converts one configured dimension into a concrete pixel count.
     *
     * <p>Forced even and to a floor of 2: Amethyst's own sizing path rounds odd
     * values down ({@code SurfaceViewController.updateSavedResolution}), and a
     * zero or odd framebuffer dimension makes Minecraft's render target
     * allocation misbehave.
     *
     * <p>Clamped to the native size, since reporting a framebuffer larger than
     * the real surface would place the blit partly off-screen.
     */
    public static int resolve(int nativePixels, double value) {
        double raw = isFraction(value) ? nativePixels * value : value;
        int resolved = (int) Math.round(raw);
        if (resolved > nativePixels) resolved = nativePixels;
        if (resolved % 2 != 0) resolved--;
        return Math.max(2, resolved);
    }

    public int resolveWidth(int nativeWidth) {
        return resolve(nativeWidth, width);
    }

    public int resolveHeight(int nativeHeight) {
        return resolve(nativeHeight, height);
    }
}
