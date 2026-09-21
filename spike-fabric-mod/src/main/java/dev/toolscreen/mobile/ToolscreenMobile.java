package dev.toolscreen.mobile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Entry point and global state for the Toolscreen mobile spike.
 *
 * <p>The mod holds one active {@link Mode}; {@code WindowMixin} reports its
 * dimensions to Minecraft in place of the real surface size, which is what
 * produces the stretched / thin screen shape.
 *
 * <p>Everything is static because the mixins that read this state have no
 * sensible way to reach an instance.
 */
public final class ToolscreenMobile implements ClientModInitializer {

    public static final String MOD_ID = "toolscreen-mobile";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /**
     * GLFW_KEY_GRAVE_ACCENT (the {@code `} key).
     *
     * <p>Deliberately not a function key: plenty of tablet and compact keyboards
     * have no F-row at all. Grave accent is present on essentially every layout
     * and is unbound in vanilla Minecraft.
     *
     * <p>Amethyst's on-screen buttons emit GLFW key codes too, so whatever is
     * set here works as a touch binding as well as a physical key.
     */
    private static final int DEFAULT_TOGGLE_KEY = 96;

    /**
     * Ratios estimated from screenshots of the Windows tool: its thin mode runs
     * about 1:5 and its wide mode about 5.8:1, both far more extreme than the
     * first guesses here (1:3.5 and 3.2:1). Expressed as fractions so they hold
     * on any device; to copy a Toolscreen preset exactly, use absolute pixels
     * instead, e.g. {@code Thin:280x1024}.
     */
    private static final List<Mode> DEFAULT_MODES = List.of(
            new Mode("Native", 1.00, 1.00),
            new Mode("Thin", 0.14, 1.00),
            new Mode("Eye Measure", 0.08, 1.00),
            new Mode("Wide Short", 1.00, 0.25)
    );

    private static volatile List<Mode> modes = DEFAULT_MODES;
    private static volatile int activeIndex = 0;
    private static volatile int toggleKey = DEFAULT_TOGGLE_KEY;
    private static volatile Align align = Align.CENTER;

    /**
     * The real surface size, recorded by {@code WindowMixin} as it reads the
     * shadowed fields. Needed to work out how far to offset the rendered strip:
     * the overridden getters report the fake size, so the true size is not
     * otherwise reachable from here.
     */
    private static volatile int nativeWidth;
    private static volatile int nativeHeight;

    // ---- EyeZoom ----------------------------------------------------------
    // Per-mode, matching the Windows tool, where the EyeZoom settings live
    // under Modes rather than as a global toggle.
    private static volatile List<String> eyeZoomModes = List.of("Eye Measure");
    // Sized for a narrow strip. In Eye Measure the strip is only ~190 real
    // pixels wide, and Minecraft picks GUI scale 1 at that width, so one GUI
    // unit is one real pixel: a zoom of 6 meant six real pixels per game pixel,
    // far too fine to count. 10 with a smaller region keeps the panel inside
    // the strip while making each pixel legible.
    private static volatile int eyeZoomRegionWidth = 12;
    private static volatile int eyeZoomRegionHeight = 6;
    private static volatile int eyeZoomFactor = 10;
    private static volatile int eyeZoomRulerMax = 6;
    // Just below the crosshair rather than up in the sky: close enough to read
    // without moving your eye far, clear of the centre region being sampled.
    private static volatile double eyeZoomTop = 0.62;
    private static volatile double eyeZoomLeft = 0.5;
    private static volatile boolean eyeZoomReported;
    private static volatile boolean overlayHookReported;
    private static volatile boolean crosshairEnabled = true;
    private static volatile int crosshairSize = 3;
    private static volatile int crosshairGap = 2;

    /** Where the rendered area sits within the real screen. */
    public enum Align { LEFT, CENTER, RIGHT }

    @Override
    public void onInitializeClient() {
        loadConfig();
        LOGGER.info("[{}] ready: {} mode(s), toggle key {} ({})",
                MOD_ID, modes.size(), KeyCodes.nameOf(toggleKey, "?"), toggleKey);
    }

    private static volatile boolean centeringReported;

    /**
     * Called by {@code FramebufferMixin} the first time it actually runs.
     *
     * <p>The centring redirect is optional ({@code require = 0}), so if its
     * target ever stops matching the game still launches and simply renders
     * left-aligned. That trade means silence is ambiguous, so this logs once to
     * make "the redirect attached" observable in latest.log instead of
     * something you have to infer from pixels.
     */
    public static void noteCenteringActive() {
        if (centeringReported) return;
        centeringReported = true;
        LOGGER.info("[{}] centring active (align={})", MOD_ID, align);
    }

    /** True when the active mode is one the config lists for EyeZoom. */
    public static boolean eyeZoomActive() {
        String active = activeMode().name();
        for (String name : eyeZoomModes) {
            if (name.equalsIgnoreCase(active)) return true;
        }
        return false;
    }

    public static boolean crosshairEnabled() {
        return crosshairEnabled;
    }

    /** Arm length of the replacement crosshair, in GUI units. */
    public static int crosshairSize() {
        return crosshairSize;
    }

    /** Half-width of the gap left at the crosshair's centre, in GUI units. */
    public static int crosshairGap() {
        return crosshairGap;
    }

    public static int eyeZoomRegionWidth() {
        return eyeZoomRegionWidth;
    }

    public static int eyeZoomRegionHeight() {
        return eyeZoomRegionHeight;
    }

    public static int eyeZoomFactor() {
        return eyeZoomFactor;
    }

    public static int eyeZoomRulerMax() {
        return eyeZoomRulerMax;
    }

    /** Centre of the panel vertically, as a fraction of the strip's height. */
    public static double eyeZoomTop() {
        return eyeZoomTop;
    }

    /** Centre of the panel horizontally, as a fraction of the strip's width. */
    public static double eyeZoomLeft() {
        return eyeZoomLeft;
    }

    /**
     * Logged once the first time the render hook runs at all.
     *
     * <p>Paired with {@link #noteEyeZoomActive()} this gives a diagnostic
     * ladder in latest.log, so a non-appearing overlay can be placed exactly:
     * no line at all means the mixin never attached or the method is never
     * called; this line without the drawing line means the active mode is not
     * one listed under eyezoomModes; both lines mean it is drawing and the
     * problem is visual rather than structural.
     */
    public static void noteOverlayHookFired() {
        if (overlayHookReported) return;
        overlayHookReported = true;
        LOGGER.info("[{}] overlay hook firing (mode={}, eyezoom={})",
                MOD_ID, activeMode().name(), eyeZoomActive());
    }

    /**
     * Logged once when the overlay actually draws.
     *
     * <p>Its mixin is optional, so a failed injection is silent by design.
     * Without this line there would be no way to tell "attached and drawing"
     * from "never attached" except by squinting at the screen.
     */
    public static void noteEyeZoomActive() {
        if (eyeZoomReported) return;
        eyeZoomReported = true;
        LOGGER.info("[{}] eyezoom drawing: region {}x{} at {}x zoom, ruler +/-{}",
                MOD_ID, eyeZoomRegionWidth, eyeZoomRegionHeight, eyeZoomFactor, eyeZoomRulerMax);
    }

    public static void recordNativeSize(int width, int height) {
        nativeWidth = width;
        nativeHeight = height;
    }

    /**
     * Horizontal offset, in pixels, for the final blit.
     *
     * <p>Minecraft blits its render target with {@code viewport(0, 0, w, h)},
     * and GL's origin is bottom-left, so a smaller-than-screen viewport lands in
     * the corner. Shifting it by this much centres the strip instead.
     */
    public static int offsetX() {
        if (!isOverrideActive()) return 0;
        return offset(nativeWidth, activeMode().resolveWidth(nativeWidth));
    }

    /** Vertical offset for the final blit; matters for modes shorter than the screen. */
    public static int offsetY() {
        if (!isOverrideActive()) return 0;
        return offset(nativeHeight, activeMode().resolveHeight(nativeHeight));
    }

    private static int offset(int real, int shown) {
        int slack = real - shown;
        if (slack <= 0) return 0;
        switch (align) {
            case CENTER: return slack / 2;
            case RIGHT: return slack;
            default: return 0;
        }
    }

    public static Mode activeMode() {
        List<Mode> current = modes;
        if (current.isEmpty()) return DEFAULT_MODES.get(0);
        return current.get(Math.floorMod(activeIndex, current.size()));
    }

    /** True when Minecraft should be told a size other than the real surface size. */
    public static boolean isOverrideActive() {
        return !activeMode().isNative();
    }

    public static int toggleKey() {
        return toggleKey;
    }

    /** Advances to the next mode and returns it. Caller is responsible for triggering a resize. */
    public static Mode cycleMode() {
        List<Mode> current = modes;
        if (!current.isEmpty()) {
            activeIndex = Math.floorMod(activeIndex + 1, current.size());
        }
        Mode mode = activeMode();
        LOGGER.info("[{}] mode -> {}", MOD_ID, mode.name());
        return mode;
    }

    // ---- config -----------------------------------------------------------

    /**
     * Loads {@code config/toolscreen-mobile.properties}, writing a default file
     * if absent.
     *
     * <p>This exists because rebuilding the mod requires a computer, and the
     * device running it is an iPad. Tuning mode dimensions on-device without a
     * rebuild is the difference between a usable spike and a useless one.
     */
    private static void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".properties");
        Properties props = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("[{}] could not read {}, using defaults", MOD_ID, file, e);
                return;
            }
        } else {
            writeDefaultConfig(file);
            return;
        }

        toggleKey = KeyCodes.resolve(props.getProperty("toggleKey"), DEFAULT_TOGGLE_KEY);
        align = parseAlign(props.getProperty("align"), Align.CENTER);

        crosshairEnabled = !"false".equalsIgnoreCase(String.valueOf(props.getProperty("crosshair")).trim());
        crosshairSize = clampInt(props.getProperty("crosshairSize"), crosshairSize, 1, 64);
        crosshairGap = clampInt(props.getProperty("crosshairGap"), crosshairGap, 0, 32);

        eyeZoomModes = parseNameList(props.getProperty("eyezoomModes"), eyeZoomModes);
        eyeZoomRegionWidth = clampInt(props.getProperty("eyezoomRegionWidth"), eyeZoomRegionWidth, 2, 256);
        eyeZoomRegionHeight = clampInt(props.getProperty("eyezoomRegionHeight"), eyeZoomRegionHeight, 2, 256);
        eyeZoomFactor = clampInt(props.getProperty("eyezoomFactor"), eyeZoomFactor, 1, 64);
        eyeZoomRulerMax = clampInt(props.getProperty("eyezoomRulerMax"), eyeZoomRulerMax, 1, 128);
        eyeZoomTop = clampDouble(props.getProperty("eyezoomTop"), eyeZoomTop, 0.0, 1.0);
        eyeZoomLeft = clampDouble(props.getProperty("eyezoomLeft"), eyeZoomLeft, 0.0, 1.0);

        List<Mode> parsed = parseModes(props.getProperty("modes"));
        if (!parsed.isEmpty()) {
            modes = List.copyOf(parsed);
        }
    }

    private static List<String> parseNameList(String raw, List<String> fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names.isEmpty() ? fallback : List.copyOf(names);
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        if (raw == null) return fallback;
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            LOGGER.warn("[{}] bad integer '{}', using {}", MOD_ID, raw.trim(), fallback);
            return fallback;
        }
    }

    private static double clampDouble(String raw, double fallback, double min, double max) {
        if (raw == null) return fallback;
        try {
            return Math.min(max, Math.max(min, Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            LOGGER.warn("[{}] bad number '{}', using {}", MOD_ID, raw.trim(), fallback);
            return fallback;
        }
    }

    private static Align parseAlign(String raw, Align fallback) {
        if (raw == null) return fallback;
        try {
            return Align.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[{}] unknown align '{}', using {}", MOD_ID, raw.trim(), fallback);
            return fallback;
        }
    }

    /** Parses {@code Name:WxH, Name:WxH} where W and H are fractions of the native surface. */
    private static List<Mode> parseModes(String raw) {
        List<Mode> parsed = new ArrayList<>();
        if (raw == null || raw.isBlank()) return parsed;

        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int colon = trimmed.lastIndexOf(':');
                int x = trimmed.lastIndexOf('x');
                if (colon < 0 || x < colon) {
                    throw new IllegalArgumentException("expected Name:WxH");
                }
                String name = trimmed.substring(0, colon).trim();
                double w = Double.parseDouble(trimmed.substring(colon + 1, x).trim());
                double h = Double.parseDouble(trimmed.substring(x + 1).trim());
                parsed.add(new Mode(name, w, h));
            } catch (RuntimeException e) {
                LOGGER.warn("[{}] skipping malformed mode entry '{}': {}", MOD_ID, trimmed, e.getMessage());
            }
        }
        return parsed;
    }

    private static void writeDefaultConfig(Path file) {
        StringBuilder modeList = new StringBuilder();
        for (Mode mode : DEFAULT_MODES) {
            if (modeList.length() > 0) modeList.append(", ");
            modeList.append(mode.name()).append(':')
                    .append(mode.width()).append('x').append(mode.height());
        }

        Properties props = new Properties();
        props.setProperty("toggleKey", KeyCodes.nameOf(DEFAULT_TOGGLE_KEY, Integer.toString(DEFAULT_TOGGLE_KEY)));
        props.setProperty("align", Align.CENTER.name());
        props.setProperty("modes", modeList.toString());
        props.setProperty("crosshair", Boolean.toString(crosshairEnabled));
        props.setProperty("crosshairSize", Integer.toString(crosshairSize));
        props.setProperty("crosshairGap", Integer.toString(crosshairGap));
        props.setProperty("eyezoomModes", String.join(", ", eyeZoomModes));
        props.setProperty("eyezoomRegionWidth", Integer.toString(eyeZoomRegionWidth));
        props.setProperty("eyezoomRegionHeight", Integer.toString(eyeZoomRegionHeight));
        props.setProperty("eyezoomFactor", Integer.toString(eyeZoomFactor));
        props.setProperty("eyezoomRulerMax", Integer.toString(eyeZoomRulerMax));
        props.setProperty("eyezoomTop", Double.toString(eyeZoomTop));
        props.setProperty("eyezoomLeft", Double.toString(eyeZoomLeft));

        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Toolscreen mobile (spike). "
                        + "toggleKey = the key that cycles modes: a GLFW key name such as "
                        + "GRAVE_ACCENT, BACKSLASH, RIGHT_BRACKET or G, or a raw numeric code. "
                        + "align = LEFT, CENTER or RIGHT: where the rendered area sits on screen. "
                        + "modes = comma separated Name:WidthxHeight. A value of 1.0 or less "
                        + "is a fraction of the screen (0.14 = 14% of the width); anything "
                        + "larger is an absolute pixel count (280 = 280 pixels), which is how "
                        + "Toolscreen presets are written.");
            }
        } catch (IOException e) {
            LOGGER.warn("[{}] could not write default config to {}", MOD_ID, file, e);
        }
    }
}
