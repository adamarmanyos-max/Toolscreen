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

    /**
     * Bumped whenever this build's tuned defaults should replace a config file
     * written by an older one.
     *
     * <p>The file is written once and then read forever, which quietly pinned
     * every device to the defaults of whichever build happened to create it -
     * so a rebuild with better numbers changed nothing, and the reason was
     * invisible from a screenshot. An older file is now replaced rather than
     * obeyed. That does discard hand edits, which is the right trade while
     * these numbers are still being fitted against the original screenshot by
     * screenshot; it stops once the shape settles.
     */
    private static final int CONFIG_VERSION = 3;
    static final Logger LOGGER = LogManager.getLogger(MOD_ID);

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
            // Renders far taller than the screen on purpose. Angle per pixel is
            // the vertical field of view divided by the render height, so a
            // 16384-pixel render resolves a horizontal offset about eight times
            // more finely than a screen-height one - that is the whole
            // mechanism the eye measurement rests on. The width is a fraction
            // because the framebuffer is blitted 1:1, which makes the render
            // width and the on-screen window width the same number: 0.161 of
            // the screen is 446 pixels at 2778 wide, the measured target.
            new Mode("Eye Measure", 0.161, 16384),
            new Mode("Wide Short", 1.00, 0.25)
    );

    private static volatile List<Mode> modes = DEFAULT_MODES;
    private static volatile int activeIndex = 0;
    private static volatile int toggleKey = DEFAULT_TOGGLE_KEY;

    /**
     * Prints the measurement check. Default M, which vanilla leaves unbound.
     */
    private static volatile int reportKey = 77;
    private static volatile Align align = Align.CENTER;

    /**
     * The real surface size, recorded by {@code WindowMixin} as it reads the
     * shadowed fields. Needed to work out how far to offset the rendered strip:
     * the overridden getters report the fake size, so the true size is not
     * otherwise reachable from here.
     */
    private static volatile int nativeWidth;
    private static volatile int nativeHeight;

    /**
     * The framebuffer size actually in use, as last reported to Minecraft.
     *
     * <p>Recorded rather than recomputed because it is the number that has to be
     * typed into Ninjabrain Bot's tall-resolution box, and a number derived a
     * second time from the config is a number that can disagree with the one the
     * game is really rendering at. This is the one the game got.
     */
    private static volatile int renderWidth;
    private static volatile int renderHeight;

    // ---- EyeZoom ----------------------------------------------------------
    // Per-mode, matching the Windows tool, where the EyeZoom settings live
    // under Modes rather than as a global toggle.
    private static volatile List<String> eyeZoomModes = List.of("Eye Measure");
    // ---- Layout ----------------------------------------------------------
    //
    // Every one of these is a fraction of the screen, never a pixel count. The
    // original is a Windows tool measured against one monitor, so its published
    // numbers are only correct at that resolution; stored proportionally they
    // reproduce those numbers there and stay sensible on a phone. Layout turns
    // them into pixels.
    //
    // The values reproduce, at 2778x1940: game window 446 wide, panel 931x1455,
    // ruler 744x58 with 31-pixel cells.

    /** Panel height, as a fraction of screen height. */
    private static volatile double panelHeightFraction = 0.75;

    /** Panel width, as a fraction of its own height. */
    private static volatile double panelAspect = 0.64;

    /** Ruler width, as a fraction of the panel width. */
    private static volatile double rulerWidthFraction = 0.80;

    /** Ruler height, as a fraction of screen height. */
    private static volatile double rulerHeightFraction = 0.03;

    /** Ruler cells, total: half either side of the divider. */
    private static volatile int rulerCells = 24;

    /**
     * Rows of framebuffer per row of panel.
     *
     * <p>One, so the panel shows the game view's own vertical scale and only the
     * horizontal axis is magnified. The horizontal zoom is not configurable at
     * all: it is the ruler cell width, because one cell has to be one
     * framebuffer pixel for the count to mean anything.
     */
    private static volatile int verticalStride = 1;

    // The original fills the area around the game rather than leaving it bare;
    // its localization table carries background, bg_image_path and color_stops.
    private static volatile boolean backgroundEnabled = true;

    private static volatile int backgroundTop = 0x1A0533;
    private static volatile int backgroundBottom = 0x4A1594;

    /**
     * Multiplier on the game's field of view. Below 1.0 narrows it, which
     * magnifies the view.
     *
     * <p>Minecraft's own FOV slider stops at 30, and for measuring you want to
     * go further: a narrower field spreads the same view over more pixels, so
     * each pixel covers a smaller angle and a pixel count means more.
     */

    private static volatile boolean eyeZoomReported;
    private static volatile boolean fovReported;
    private static volatile boolean panelReported;
    private static volatile Layout layout;
    private static volatile boolean maxTextureReported;
    private static volatile boolean clampReported;
    private static volatile boolean feedbackReported;
    private static volatile int lastReportedWidth;
    private static volatile int lastReportedHeight;
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

    public static double panelHeightFraction() {
        return panelHeightFraction;
    }

    public static double panelAspect() {
        return panelAspect;
    }

    public static double rulerWidthFraction() {
        return rulerWidthFraction;
    }

    public static double rulerHeightFraction() {
        return rulerHeightFraction;
    }

    public static int rulerCells() {
        return rulerCells;
    }

    public static int verticalStride() {
        return verticalStride;
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
        LOGGER.info("[{}] eyezoom drawing", MOD_ID);
    }

    /** Logged once: the driver's texture ceiling, which bounds the render height. */
    public static void noteMaxTexture(int max) {
        if (maxTextureReported) return;
        maxTextureReported = true;
        LOGGER.info("[{}] GL_MAX_TEXTURE_SIZE is {}", MOD_ID, max);
    }

    /**
     * Logged once if the configured render size had to be reduced.
     *
     * <p>A warning rather than a note: the measurement stays self-consistent
     * because the ruler counts real framebuffer pixels either way, but the
     * number that has to go into Ninjabrain Bot is now the clamped one, and
     * using the configured one instead would be wrong by their ratio.
     */
    public static void noteTextureClamp(int requested, int clamped) {
        if (clampReported) return;
        clampReported = true;
        LOGGER.warn("[{}] render size {} exceeds this GPU's texture limit; using {}. "
                        + "Enter {} in Ninjabrain Bot, not {}",
                MOD_ID, requested, clamped, clamped, requested);
    }

    /** Framebuffer width actually in use. */
    public static int renderWidth() {
        return renderWidth;
    }

    /** Framebuffer height actually in use - the number Ninjabrain Bot wants. */
    public static int renderHeight() {
        return renderHeight;
    }

    /**
     * Records the framebuffer size Minecraft was actually given, and announces
     * it the first time and whenever it changes.
     *
     * <p>Logged at INFO with the Ninjabrain wording attached, because getting
     * this number wrong is silent: the tool keeps working and every reading it
     * produces is wrong by the ratio of the two heights.
     */
    public static void recordRenderSize(int width, int height) {
        if (width < 2 || height < 2) return;
        if (width == renderWidth && height == renderHeight) return;
        renderWidth = width;
        renderHeight = height;
        LOGGER.info("[{}] render resolution {}x{} - enter {} as the tall resolution "
                        + "in Ninjabrain Bot", MOD_ID, width, height, height);
    }

    /** Real surface width, before any mode override. */
    public static int nativeWidth() {
        return nativeWidth;
    }

    /** Real surface height, before any mode override. */
    public static int nativeHeight() {
        return nativeHeight;
    }

    /**
     * The current layout, or null if one cannot be built for this screen.
     *
     * <p>Cached: it is read several times a frame and the arithmetic does not
     * change unless the screen or the render width does.
     */
    public static Layout layout() {
        int screenW = nativeWidth;
        int screenH = nativeHeight;
        int renderW = renderWidth;
        if (screenW < 16 || screenH < 16 || renderW < 2) return null;

        Layout cached = layout;
        if (cached != null && cached.screenWidth == screenW && cached.screenHeight == screenH
                && cached.gameWidth == Math.min(renderW, screenW)) {
            return cached;
        }
        Layout built = Layout.compute(screenW, screenH, renderW);
        layout = built;
        return built;
    }

    /**
     * Logs the computed geometry once.
     *
     * <p>Every number in the layout is derived, and when the panel once covered
     * the game window there was no way to tell from a screenshot which input had
     * been wrong. Printing the result settles it in one run.
     */
    public static void notePanelGeometry(Layout value) {
        if (panelReported || value == null) return;
        panelReported = true;
        LOGGER.info("[{}] layout: {}", MOD_ID, value);
        if (value.overlapsGame()) {
            LOGGER.warn("[{}] panel overlaps the game window - it will not be drawn", MOD_ID);
        }
    }

    public static boolean backgroundEnabled() {
        return backgroundEnabled;
    }

    public static int backgroundTop() {
        return backgroundTop;
    }

    public static int backgroundBottom() {
        return backgroundBottom;
    }

    public static void recordNativeSize(int width, int height) {
        if (width < 2 || height < 2) return;

        // Ignore our own numbers coming back as the "real" size.
        //
        // Amethyst's glfwSetWindowSize is a stub that only records what it is
        // given, and glfwGetFramebufferSize reads those same numbers back, so a
        // width we returned from the override can arrive here as the surface
        // size. Resolving the mode against that shrinks the render by the mode's
        // fraction a second time, and again on the next resize: 2360 becomes
        // 260, then 28, then the floor. The game is then rendering a handful of
        // pixels and the launcher stretches them over the strip, which is what a
        // screenshot showed - a perfect 57-pixel grid across the whole strip,
        // horizontally and vertically, which no amount of perspective can
        // produce.
        //
        // The native size is whatever was last seen that we did not produce.
        if (nativeWidth >= 2 && width == lastReportedWidth && height == lastReportedHeight) {
            noteSizeFeedback(width, height);
            return;
        }

        nativeWidth = width;
        nativeHeight = height;
    }

    /** Records what the override last reported, so it can be recognised coming back. */
    public static void recordReportedSize(int width, int height) {
        lastReportedWidth = width;
        lastReportedHeight = height;
    }

    private static void noteSizeFeedback(int width, int height) {
        if (feedbackReported) return;
        feedbackReported = true;
        LOGGER.warn("[{}] ignoring {}x{} as the surface size: it is what this mod "
                        + "last reported, so treating it as real would shrink the render again",
                MOD_ID, width, height);
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

    public static int reportKey() {
        return reportKey;
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

        int version = clampInt(props.getProperty("configVersion"), 0, 0, 10000);
        if (version < CONFIG_VERSION) {
            LOGGER.info("[{}] config at {} is version {}, this build expects {} - "
                            + "replacing it with this build's defaults",
                    MOD_ID, file, version, CONFIG_VERSION);
            writeDefaultConfig(file);
            return;
        }

        toggleKey = KeyCodes.resolve(props.getProperty("toggleKey"), DEFAULT_TOGGLE_KEY);
        reportKey = KeyCodes.resolve(props.getProperty("reportKey"), reportKey);
        align = parseAlign(props.getProperty("align"), Align.CENTER);

        crosshairEnabled = !"false".equalsIgnoreCase(String.valueOf(props.getProperty("crosshair")).trim());
        crosshairSize = clampInt(props.getProperty("crosshairSize"), crosshairSize, 1, 64);
        crosshairGap = clampInt(props.getProperty("crosshairGap"), crosshairGap, 0, 32);

        eyeZoomModes = parseNameList(props.getProperty("eyezoomModes"), eyeZoomModes);
        panelHeightFraction = clampDouble(props.getProperty("panelHeightFraction"), panelHeightFraction, 0.1, 1.0);
        panelAspect = clampDouble(props.getProperty("panelAspect"), panelAspect, 0.1, 4.0);
        rulerWidthFraction = clampDouble(props.getProperty("rulerWidthFraction"), rulerWidthFraction, 0.1, 1.0);
        rulerHeightFraction = clampDouble(props.getProperty("rulerHeightFraction"), rulerHeightFraction, 0.005, 0.5);
        rulerCells = clampInt(props.getProperty("rulerCells"), rulerCells, 2, 256);
        if (rulerCells % 2 != 0) rulerCells--;
        verticalStride = clampInt(props.getProperty("verticalStride"), verticalStride, 1, 64);

        backgroundEnabled = !"false".equalsIgnoreCase(String.valueOf(props.getProperty("background")).trim());
        backgroundTop = parseColour(props.getProperty("backgroundTop"), backgroundTop);
        backgroundBottom = parseColour(props.getProperty("backgroundBottom"), backgroundBottom);

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

    /** Parses {@code #RRGGBB} or {@code RRGGBB}; falls back on anything else. */
    private static int parseColour(String raw, int fallback) {
        if (raw == null) return fallback;
        String hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return (int) (Long.parseLong(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            LOGGER.warn("[{}] bad colour '{}', using #{}", MOD_ID, raw.trim(), Integer.toHexString(fallback));
            return fallback;
        }
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
        props.setProperty("panelHeightFraction", Double.toString(panelHeightFraction));
        props.setProperty("panelAspect", Double.toString(panelAspect));
        props.setProperty("rulerWidthFraction", Double.toString(rulerWidthFraction));
        props.setProperty("rulerHeightFraction", Double.toString(rulerHeightFraction));
        props.setProperty("rulerCells", Integer.toString(rulerCells));
        props.setProperty("verticalStride", Integer.toString(verticalStride));
        props.setProperty("reportKey", KeyCodes.nameOf(reportKey, Integer.toString(reportKey)));
        props.setProperty("configVersion", Integer.toString(CONFIG_VERSION));
        props.setProperty("background", Boolean.toString(backgroundEnabled));
        props.setProperty("backgroundTop", String.format("#%06X", backgroundTop));
        props.setProperty("backgroundBottom", String.format("#%06X", backgroundBottom));

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
