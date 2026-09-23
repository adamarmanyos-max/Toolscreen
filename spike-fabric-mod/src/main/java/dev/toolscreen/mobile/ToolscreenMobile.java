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
    private static final int CONFIG_VERSION = 4;
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
            // Width as before; height far beyond the screen on purpose. That
            // height is the zoom: angle per pixel is the vertical field of view
            // over the render height, so 16384 rows magnify the view about eight
            // times without touching the camera - which the earlier fovScale did,
            // and which silently falsified every reading it produced.
            new Mode("Eye Measure", 0.11, 16384),
            new Mode("Wide Short", 1.00, 0.25)
    );

    private static volatile List<Mode> modes = DEFAULT_MODES;
    private static volatile int activeIndex = 0;
    private static volatile int toggleKey = DEFAULT_TOGGLE_KEY;

    /** Prints the measurement check. Default M, which vanilla leaves unbound. */
    private static volatile int reportKey = 77;

    /** Opens the settings menu. Default comma, which vanilla leaves unbound. */
    private static volatile int menuKey = 44;
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
     * <p>Recorded rather than recomputed: it is the number that has to be typed
     * into Ninjabrain Bot's tall-resolution box, and a figure derived a second
     * time from the config is one that can disagree with what the game is really
     * rendering at. This is what the game got.
     */
    private static volatile int renderWidth;
    private static volatile int renderHeight;
    private static volatile boolean maxTextureReported;
    private static volatile boolean maxViewportReported;
    private static volatile boolean cropReported;
    private static volatile boolean clampReported;

    // ---- EyeZoom ----------------------------------------------------------
    // Per-mode, matching the Windows tool, where the EyeZoom settings live
    // under Modes rather than as a global toggle.
    private static volatile List<String> eyeZoomModes = List.of("Eye Measure");
    // How many game pixels are magnified, and how far each one is blown up.
    //
    // The two are a budget, not independent settings: region times factor is
    // the panel size, and the panel has to fit the letterbox beside the strip.
    // On an iPad in Eye Measure that band is around 1050 pixels wide, so 24
    // pixels at 34x fills it. Asking for more of both is what produced a panel
    // 1680 wide that ran straight across the game; EyeZoom reduces the factors
    // until the panel fits, so these are an upper bound rather than a promise.
    //
    // The region is far taller than it is wide because the original's panel is:
    // measured off two of its screenshots it is 617x757 and 605x745, both about
    // 0.81 wide for every unit tall. Ours was landscape, which is why it looked
    // nothing like it however the colours were tuned.
    private static volatile int eyeZoomRegionWidth = 24;
    private static volatile int eyeZoomRegionHeight = 60;

    // Separate horizontal and vertical magnification, as the original has:
    // its strings carry clone_width and clone_height independently. The stretch
    // runs along X - each game pixel is drawn wider than it is tall, which
    // suits a ruler that counts horizontal offsets.
    private static volatile int eyeZoomFactorX = 34;
    private static volatile int eyeZoomFactorY = 17;
    // The widest offset the ruler labels. Beyond half the region there are no
    // pixels left to label, so this tracks eyeZoomRegionWidth / 2. Twelve is
    // also what the original labels in two of its screenshots.
    private static volatile int eyeZoomRulerMax = 12;
    // Just below the crosshair rather than up in the sky: close enough to read
    // without moving your eye far, clear of the centre region being sampled.
    // Centred in the left letterbox band, matching where the Windows tool puts
    // it. These are fractions of the whole screen when eyezoomSide is on.
    private static volatile double eyeZoomTop = 0.5;
    private static volatile double eyeZoomLeft = 0.23;
    private static volatile boolean eyeZoomSide = true;

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
    private static volatile double fovScale = 0.5;

    private static volatile boolean eyeZoomReported;
    private static volatile boolean overlayHookReported;
    private static volatile boolean surfaceReported;
    private static volatile boolean bindingReported;
    private static volatile boolean fovReported;
    private static volatile boolean panelReported;
    private static volatile boolean suppressReported;
    private static volatile boolean feedbackReported;
    private static volatile int lastReportedWidth;
    private static volatile int lastReportedHeight;
    private static volatile boolean crosshairEnabled = true;
    private static volatile int crosshairSize = 3;
    private static volatile int crosshairGap = 2;

    /**
     * Draw Minecraft's own crosshair texture rather than a plain cross.
     *
     * <p>Off falls back to the drawn cross, for the case where a translation
     * layer mishandles the inverting blend the vanilla one relies on.
     */
    private static volatile boolean crosshairVanilla = true;

    /**
     * Multiplier on the crosshair's size, which is otherwise derived from the
     * width of the game window so it stays proportional on any device.
     */
    private static volatile double crosshairScale = 1.0;

    /**
     * Which row of the framebuffer lands at the centre of the screen, as a
     * fraction of its height.
     *
     * <p>0.5 is the crosshair, and is what a centred crop means. It is settable
     * because a driver that clamps the oversized viewport moves the crop
     * somewhere else, and the symptom - the view cut off at one edge only - then
     * needs correcting on the device rather than in another build.
     */
    private static volatile double cropCentre = 0.5;

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
    public static boolean crosshairVanilla() {
        return crosshairVanilla;
    }

    public static double crosshairScale() {
        return crosshairScale;
    }

    public static void setCropCentre(double value) {
        cropCentre = Math.max(0.0, Math.min(1.0, value));
    }

    public static void setEyeZoomFactorX(int value) {
        eyeZoomFactorX = Math.max(1, Math.min(256, value));
    }

    public static void setEyeZoomFactorY(int value) {
        eyeZoomFactorY = Math.max(1, Math.min(256, value));
    }

    /** Writes the current settings back to the config file. */
    public static void save() {
        writeDefaultConfig(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".properties"));
    }

    public static double cropCentre() {
        return cropCentre;
    }

    /**
     * Logged once: the crop actually asked for.
     *
     * <p>The arithmetic here is simple enough to be obviously right, and was,
     * while the result on screen was still wrong - so the numbers handed to GL
     * go on the record next to the limits above.
     */
    public static void noteCrop(int screenHeight, int renderHeight, int viewportY) {
        if (cropReported) return;
        cropReported = true;
        LOGGER.info("[{}] crop: screen height {}, render height {}, viewport y {}",
                MOD_ID, screenHeight, renderHeight, viewportY);
    }

    public static int crosshairGap() {
        return crosshairGap;
    }

    public static int eyeZoomRegionWidth() {
        return eyeZoomRegionWidth;
    }

    public static int eyeZoomRegionHeight() {
        return eyeZoomRegionHeight;
    }

    public static int eyeZoomFactorX() {
        return eyeZoomFactorX;
    }

    public static int eyeZoomFactorY() {
        return eyeZoomFactorY;
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
        LOGGER.info("[{}] eyezoom drawing: region {}x{} at {}x{} zoom, ruler +/-{}",
                MOD_ID, eyeZoomRegionWidth, eyeZoomRegionHeight,
                eyeZoomFactorX, eyeZoomFactorY, eyeZoomRulerMax);
    }

    /**
     * Logged once when the FOV override actually takes effect.
     *
     * <p>Same reasoning as {@link #noteEyeZoomActive()}: the injection is
     * optional, so without this the difference between "narrowing the view" and
     * "mixin never attached" is a judgement call made from a screenshot.
     */
    public static void noteFovActive(double original, double scaled) {
        if (fovReported) return;
        fovReported = true;
        LOGGER.info("[{}] fov override: {} -> {} (scale {})",
                MOD_ID, original, scaled, fovScale);
    }

    /**
     * Logged once, the first time the side panel works out where to go.
     *
     * <p>Added because the fit-to-letterbox arithmetic produced a panel that
     * covered the strip anyway. Which input was wrong could not be settled by
     * reasoning about it, so the numbers are now on the record instead.
     */
    public static void notePanelGeometry(int realW, int realH, int blitX, int blitW,
                                         int panelX, int panelY, int panelW, int panelH) {
        if (panelReported) return;
        panelReported = true;
        LOGGER.info("[{}] panel: surface {}x{}, blit x={} w={}, panel x={} y={} {}x{}",
                MOD_ID, realW, realH, blitX, blitW, panelX, panelY, panelW, panelH);
    }

    /** Logged once if the panel had to be dropped for overlapping the strip. */
    public static void notePanelSuppressed(int panelX, int panelW, int blitX, int blitW) {
        if (suppressReported) return;
        suppressReported = true;
        LOGGER.warn("[{}] panel suppressed: it spans {}..{} which overlaps the strip at {}..{}",
                MOD_ID, panelX, panelX + panelW, blitX, blitX + blitW);
    }

    /** Framebuffer width actually in use. */
    public static int renderWidth() {
        return renderWidth;
    }

    /** Framebuffer height actually in use - the number Ninjabrain Bot wants. */
    public static int renderHeight() {
        return renderHeight;
    }

    public static int reportKey() {
        return reportKey;
    }

    public static int menuKey() {
        return menuKey;
    }

    /**
     * Records the framebuffer size Minecraft was actually given, and announces it
     * when it changes.
     *
     * <p>Getting this number wrong is silent: the tool keeps working and every
     * reading is wrong by the ratio of the two heights, so it is logged with the
     * Ninjabrain wording attached rather than left to be inferred.
     */
    public static void recordRenderSize(int width, int height) {
        if (width < 2 || height < 2) return;
        if (width == renderWidth && height == renderHeight) return;
        renderWidth = width;
        renderHeight = height;
        LOGGER.info("[{}] render resolution {}x{} - enter {} as the tall resolution "
                + "in Ninjabrain Bot", MOD_ID, width, height, height);
    }

    /**
     * Logged once: the viewport ceiling, which also bounds the render height.
     *
     * <p>Worth its own line because it is the limit that moves the crop rather
     * than failing outright, and nothing else would show it.
     */
    public static void noteMaxViewport(int width, int height) {
        if (maxViewportReported) return;
        maxViewportReported = true;
        LOGGER.info("[{}] GL_MAX_VIEWPORT_DIMS is {}x{}", MOD_ID, width, height);
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
     * <p>A warning, not a note: the ruler still counts real framebuffer pixels
     * either way, but the figure for Ninjabrain Bot is now the clamped one, and
     * using the configured one would be wrong by their ratio.
     */
    public static void noteSizeClamp(int requested, int clamped) {
        if (clampReported) return;
        clampReported = true;
        LOGGER.warn("[{}] render size {} exceeds this GPU's texture or viewport limit; "
                + "using {}. Enter {} in Ninjabrain Bot, not {}",
                MOD_ID, requested, clamped, clamped, requested);
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
     * True to draw the panel in the letterboxed area beside the strip, where
     * the Windows tool puts it, rather than over the game.
     */
    public static boolean eyeZoomSide() {
        return eyeZoomSide;
    }

    /**
     * Logs the surface and blit geometry once.
     *
     * <p>The background fill paints the four bands around the strip, which is
     * only correct if the blit rectangle and the surface size are in the same
     * coordinate space. When the fill covered everything, those numbers were
     * the missing evidence, and they cannot be obtained from a build machine.
     */
    public static void noteSurfaceGeometry(int surfaceW, int surfaceH,
                                           int blitX, int blitY, int blitW, int blitH) {
        if (surfaceReported) return;
        surfaceReported = true;
        LOGGER.info("[{}] surface {}x{}, blit x={} y={} w={} h={}",
                MOD_ID, surfaceW, surfaceH, blitX, blitY, blitW, blitH);
    }

    /**
     * Logs which framebuffer was bound when the background tried to paint.
     *
     * <p>Zero is the screen, which is the only safe target. Anything else is
     * Minecraft's own framebuffer, and painting there covers the world - the
     * failure seen twice already.
     */
    public static void noteFramebufferBinding(int binding) {
        if (bindingReported) return;
        bindingReported = true;
        LOGGER.info("[{}] background: framebuffer {} bound ({})",
                MOD_ID, binding, binding == 0 ? "screen, painting" : "not the screen, skipping");
    }

    public static double fovScale() {
        return fovScale;
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
        menuKey = KeyCodes.resolve(props.getProperty("menuKey"), menuKey);
        align = parseAlign(props.getProperty("align"), Align.CENTER);

        crosshairEnabled = !"false".equalsIgnoreCase(String.valueOf(props.getProperty("crosshair")).trim());
        crosshairSize = clampInt(props.getProperty("crosshairSize"), crosshairSize, 1, 64);
        crosshairGap = clampInt(props.getProperty("crosshairGap"), crosshairGap, 0, 32);
        crosshairVanilla = !"false".equalsIgnoreCase(
                String.valueOf(props.getProperty("crosshairVanilla")).trim());
        crosshairScale = clampDouble(props.getProperty("crosshairScale"), crosshairScale, 0.1, 8.0);
        cropCentre = clampDouble(props.getProperty("cropCentre"), cropCentre, 0.0, 1.0);

        eyeZoomModes = parseNameList(props.getProperty("eyezoomModes"), eyeZoomModes);
        eyeZoomRegionWidth = clampInt(props.getProperty("eyezoomRegionWidth"), eyeZoomRegionWidth, 2, 256);
        eyeZoomRegionHeight = clampInt(props.getProperty("eyezoomRegionHeight"), eyeZoomRegionHeight, 2, 256);
        // eyezoomFactor stays as a single-value shorthand: it sets both axes,
        // and the per-axis keys override it if also present.
        int both = clampInt(props.getProperty("eyezoomFactor"), 0, 0, 256);
        if (both > 0) {
            eyeZoomFactorX = both;
            eyeZoomFactorY = both;
        }
        eyeZoomFactorX = clampInt(props.getProperty("eyezoomFactorX"), eyeZoomFactorX, 1, 256);
        eyeZoomFactorY = clampInt(props.getProperty("eyezoomFactorY"), eyeZoomFactorY, 1, 256);
        eyeZoomRulerMax = clampInt(props.getProperty("eyezoomRulerMax"), eyeZoomRulerMax, 1, 128);
        eyeZoomTop = clampDouble(props.getProperty("eyezoomTop"), eyeZoomTop, 0.0, 1.0);
        eyeZoomLeft = clampDouble(props.getProperty("eyezoomLeft"), eyeZoomLeft, 0.0, 1.0);
        eyeZoomSide = !"false".equalsIgnoreCase(String.valueOf(props.getProperty("eyezoomSide")).trim());

        fovScale = clampDouble(props.getProperty("fovScale"), fovScale, 0.05, 1.0);

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
        props.setProperty("eyezoomRegionWidth", Integer.toString(eyeZoomRegionWidth));
        props.setProperty("eyezoomRegionHeight", Integer.toString(eyeZoomRegionHeight));
        props.setProperty("eyezoomFactorX", Integer.toString(eyeZoomFactorX));
        props.setProperty("eyezoomFactorY", Integer.toString(eyeZoomFactorY));
        props.setProperty("eyezoomRulerMax", Integer.toString(eyeZoomRulerMax));
        props.setProperty("eyezoomTop", Double.toString(eyeZoomTop));
        props.setProperty("eyezoomLeft", Double.toString(eyeZoomLeft));
        props.setProperty("eyezoomSide", Boolean.toString(eyeZoomSide));
        props.setProperty("fovScale", Double.toString(fovScale));
        props.setProperty("crosshairVanilla", Boolean.toString(crosshairVanilla));
        props.setProperty("crosshairScale", Double.toString(crosshairScale));
        props.setProperty("cropCentre", Double.toString(cropCentre));
        props.setProperty("reportKey", KeyCodes.nameOf(reportKey, Integer.toString(reportKey)));
        props.setProperty("menuKey", KeyCodes.nameOf(menuKey, Integer.toString(menuKey)));
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
