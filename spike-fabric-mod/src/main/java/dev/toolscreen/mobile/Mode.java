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
     * <p>A dimension larger than the screen is legitimate and expected: see
     * {@link #resolve(int, double)}.
     */
    /**
     * Smallest render this will ask for, unless the surface itself is smaller.
     *
     * <p>A strip narrower than this is not a shape, it is a fault: the tool
     * measures offsets in rendered pixels, and there have to be enough of them
     * to count. It exists because a feedback loop between the override and the
     * launcher's window stubs once collapsed the render to a handful of pixels,
     * which the launcher then stretched over the strip - and nothing in this
     * arithmetic objected, because each step on its own was reasonable.
     */
    private static final int MIN_USEFUL_PIXELS = 64;

    /**
     * Largest framebuffer dimension that will be asked for.
     *
     * <p>Eye Measure renders far taller than the screen on purpose, so there is
     * no clamp to the display any more - but there is still a hard ceiling,
     * because the framebuffer is a texture and asking for more than the driver
     * allows fails the allocation rather than degrading. The real limit is
     * queried at runtime; this is only a sanity bound on what a config file may
     * ask for.
     */
    public static final int MAX_PIXELS = 32768;

    /**
     * Converts one configured dimension into a concrete framebuffer size.
     *
     * <p>Deliberately <em>not</em> clamped to the screen. Measuring depends on
     * the render being much taller than the display: with the field of view
     * fixed, angle per pixel is the vertical field divided by the render
     * height, so a 16384-pixel render resolves an offset roughly eight times
     * more finely than a 1940-pixel one. The framebuffer is an off-screen
     * texture and has no reason to fit the monitor; only the visible crop of it
     * does.
     */
    public static int resolve(int nativePixels, double value) {
        double raw = isFraction(value) ? nativePixels * value : value;
        int resolved = (int) Math.round(raw);

        if (resolved > MAX_PIXELS) resolved = MAX_PIXELS;
        int floor = Math.min(MIN_USEFUL_PIXELS, Math.max(2, nativePixels));
        if (resolved < floor) resolved = floor;

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
