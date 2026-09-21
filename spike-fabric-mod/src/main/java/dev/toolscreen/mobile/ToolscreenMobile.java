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

    /** GLFW_KEY_F6. Amethyst's on-screen buttons emit GLFW key codes, so a key binding is also a touch binding. */
    private static final int DEFAULT_TOGGLE_KEY = 295;

    private static final List<Mode> DEFAULT_MODES = List.of(
            new Mode("Native", 1.00, 1.00),
            new Mode("Thin", 0.20, 1.00),
            new Mode("Eye Measure", 0.10, 1.00),
            new Mode("Wide Short", 1.00, 0.45)
    );

    private static volatile List<Mode> modes = DEFAULT_MODES;
    private static volatile int activeIndex = 0;
    private static volatile int toggleKey = DEFAULT_TOGGLE_KEY;

    @Override
    public void onInitializeClient() {
        loadConfig();
        LOGGER.info("[{}] ready: {} mode(s), toggle key code {}", MOD_ID, modes.size(), toggleKey);
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

        toggleKey = parseInt(props.getProperty("toggleKey"), DEFAULT_TOGGLE_KEY);

        List<Mode> parsed = parseModes(props.getProperty("modes"));
        if (!parsed.isEmpty()) {
            modes = List.copyOf(parsed);
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

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeDefaultConfig(Path file) {
        StringBuilder modeList = new StringBuilder();
        for (Mode mode : DEFAULT_MODES) {
            if (modeList.length() > 0) modeList.append(", ");
            modeList.append(mode.name()).append(':')
                    .append(mode.widthFraction()).append('x').append(mode.heightFraction());
        }

        Properties props = new Properties();
        props.setProperty("toggleKey", Integer.toString(DEFAULT_TOGGLE_KEY));
        props.setProperty("modes", modeList.toString());

        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Toolscreen mobile (spike). "
                        + "toggleKey = GLFW key code that cycles modes (295 = F6). "
                        + "modes = comma separated Name:WidthFractionxHeightFraction, "
                        + "fractions of the native surface, 0.01 to 1.0.");
            }
        } catch (IOException e) {
            LOGGER.warn("[{}] could not write default config to {}", MOD_ID, file, e);
        }
    }
}
