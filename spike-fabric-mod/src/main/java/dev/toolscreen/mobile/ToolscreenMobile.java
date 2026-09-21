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

    private static final List<Mode> DEFAULT_MODES = List.of(
            new Mode("Native", 1.00, 1.00),
            new Mode("Thin", 0.20, 1.00),
            new Mode("Eye Measure", 0.10, 1.00),
            new Mode("Wide Short", 1.00, 0.45)
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

        List<Mode> parsed = parseModes(props.getProperty("modes"));
        if (!parsed.isEmpty()) {
            modes = List.copyOf(parsed);
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
                    .append(mode.widthFraction()).append('x').append(mode.heightFraction());
        }

        Properties props = new Properties();
        props.setProperty("toggleKey", KeyCodes.nameOf(DEFAULT_TOGGLE_KEY, Integer.toString(DEFAULT_TOGGLE_KEY)));
        props.setProperty("align", Align.CENTER.name());
        props.setProperty("modes", modeList.toString());

        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Toolscreen mobile (spike). "
                        + "toggleKey = the key that cycles modes: a GLFW key name such as "
                        + "GRAVE_ACCENT, BACKSLASH, RIGHT_BRACKET or G, or a raw numeric code. "
                        + "align = LEFT, CENTER or RIGHT: where the rendered area sits on screen. "
                        + "modes = comma separated Name:WidthFractionxHeightFraction, "
                        + "fractions of the native surface, 0.01 to 1.0.");
            }
        } catch (IOException e) {
            LOGGER.warn("[{}] could not write default config to {}", MOD_ID, file, e);
        }
    }
}
