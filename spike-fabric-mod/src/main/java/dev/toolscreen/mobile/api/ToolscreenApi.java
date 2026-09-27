package dev.toolscreen.mobile.api;

import dev.toolscreen.mobile.ToolscreenMobile;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Hooks for other client mods - written for Stronghold Finder, which is the
 * "reimplement the Ninjabrain maths natively" item in DESIGN.md.
 *
 * <p>Every method takes and returns only JDK and Minecraft types, so a mod can
 * call this by reflection with no compile-time dependency on Toolscreen
 * Mobile, and keeps working when Toolscreen Mobile is not installed at all.
 * Bump {@link #API_VERSION} on any change a caller could notice.
 */
public final class ToolscreenApi {
    public static final int API_VERSION = 1;

    private static final List<BiConsumer<MatrixStack, int[]>> SIDE_PANELS = new CopyOnWriteArrayList<>();

    private ToolscreenApi() {
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    /** True while any non-native mode (Eye Measure, Thin, Wide) resizes the render. */
    public static boolean isOverrideActive() {
        return ToolscreenMobile.isOverrideActive();
    }

    /** True while an EyeZoom mode - the tall render with the zoom panel - is active. */
    public static boolean isEyeMeasureActive() {
        return ToolscreenMobile.isOverrideActive() && ToolscreenMobile.eyeZoomActive();
    }

    /**
     * Switches to the first EyeZoom mode, or back to native, and resizes.
     * Returns false if the config has no such mode.
     */
    public static boolean setEyeMeasure(boolean on) {
        if (!ToolscreenMobile.setEyeMeasure(on)) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) client.onResolutionChanged();
        return true;
    }

    /** Factor applied to the game's FOV right now; 1.0 when nothing is narrowed. */
    public static double fovMultiplier() {
        return ToolscreenMobile.fovMultiplier();
    }

    /**
     * Registers something to draw in the letterbox beside the render strip.
     *
     * <p>Called once a frame while a non-native mode is active, with the free
     * area as {x, y, width, height} in real screen pixels, origin top left and
     * a projection already set up to match. In EyeZoom modes that is the side
     * the zoom panel is not using. This is how Stronghold Finder stays readable
     * in Eye Measure: its normal HUD panel would sit in the part of the tall
     * render that is cropped away.
     */
    public static void registerSidePanel(BiConsumer<MatrixStack, int[]> renderer) {
        if (renderer != null) SIDE_PANELS.add(renderer);
    }

    public static List<BiConsumer<MatrixStack, int[]>> sidePanels() {
        return SIDE_PANELS;
    }
}
