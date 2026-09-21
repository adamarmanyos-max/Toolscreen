package dev.toolscreen.mobile;

/**
 * A screen shape, equivalent to one entry under Toolscreen's "Modes" tab.
 *
 * <p>Dimensions are fractions of the device's native surface rather than
 * absolute pixels. Toolscreen on Windows stores absolute {@code game_width} /
 * {@code game_height} because a desktop monitor is a fixed known size; on iOS
 * the same config has to survive an iPhone, an iPad and an external display,
 * so fractions travel better. {@link #resolve} turns them back into pixels.
 *
 * <p>A mode with both fractions at 1.0 is the pass-through / native mode.
 */
public record Mode(String name, double widthFraction, double heightFraction) {

    public Mode {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("mode name must not be blank");
        }
        widthFraction = clamp(widthFraction);
        heightFraction = clamp(heightFraction);
    }

    private static double clamp(double fraction) {
        if (Double.isNaN(fraction)) return 1.0;
        return Math.min(1.0, Math.max(0.01, fraction));
    }

    public boolean isNative() {
        return widthFraction >= 1.0 && heightFraction >= 1.0;
    }

    /**
     * Converts a fraction of the native surface into a concrete pixel count.
     *
     * <p>Forced even and to a floor of 2: Amethyst's own sizing path rounds
     * odd values down ({@code SurfaceViewController.updateSavedResolution}),
     * and a zero or odd framebuffer dimension makes Minecraft's render target
     * allocation misbehave.
     */
    public static int resolve(int nativePixels, double fraction) {
        int resolved = (int) Math.round(nativePixels * fraction);
        if (resolved % 2 != 0) resolved--;
        return Math.max(2, resolved);
    }

    public int resolveWidth(int nativeWidth) {
        return resolve(nativeWidth, widthFraction);
    }

    public int resolveHeight(int nativeHeight) {
        return resolve(nativeHeight, heightFraction);
    }
}
