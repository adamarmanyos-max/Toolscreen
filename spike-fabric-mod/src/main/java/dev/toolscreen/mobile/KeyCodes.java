package dev.toolscreen.mobile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GLFW key names to codes.
 *
 * <p>Generated from Amethyst's own {@code Natives/glfw_keycodes.h}, so the
 * codes match exactly what the launcher delivers — including its {@code DPAD_*}
 * naming for the arrow keys, which is aliased to the standard GLFW names here.
 *
 * <p>Exists so the config can say {@code toggleKey=GRAVE_ACCENT} instead of
 * {@code toggleKey=96}. Editing config on a tablet is painful enough without
 * having to look up numbers.
 */
public final class KeyCodes {

    private static final Map<String, Integer> BY_NAME;

    static {
        Map<String, Integer> map = new HashMap<>();
        map.put("SPACE", 32);
        map.put("APOSTROPHE", 39);
        map.put("COMMA", 44);
        map.put("MINUS", 45);
        map.put("PERIOD", 46);
        map.put("SLASH", 47);
        map.put("0", 48);
        map.put("1", 49);
        map.put("2", 50);
        map.put("3", 51);
        map.put("4", 52);
        map.put("5", 53);
        map.put("6", 54);
        map.put("7", 55);
        map.put("8", 56);
        map.put("9", 57);
        map.put("SEMICOLON", 59);
        map.put("EQUAL", 61);
        map.put("A", 65);
        map.put("B", 66);
        map.put("C", 67);
        map.put("D", 68);
        map.put("E", 69);
        map.put("F", 70);
        map.put("G", 71);
        map.put("H", 72);
        map.put("I", 73);
        map.put("J", 74);
        map.put("K", 75);
        map.put("L", 76);
        map.put("M", 77);
        map.put("N", 78);
        map.put("O", 79);
        map.put("P", 80);
        map.put("Q", 81);
        map.put("R", 82);
        map.put("S", 83);
        map.put("T", 84);
        map.put("U", 85);
        map.put("V", 86);
        map.put("W", 87);
        map.put("X", 88);
        map.put("Y", 89);
        map.put("Z", 90);
        map.put("LEFT_BRACKET", 91);
        map.put("BACKSLASH", 92);
        map.put("RIGHT_BRACKET", 93);
        map.put("GRAVE_ACCENT", 96);
        map.put("WORLD_1", 161);
        map.put("WORLD_2", 162);
        map.put("ESCAPE", 256);
        map.put("ENTER", 257);
        map.put("TAB", 258);
        map.put("BACKSPACE", 259);
        map.put("INSERT", 260);
        map.put("DELETE", 261);
        map.put("DPAD_RIGHT", 262);
        map.put("RIGHT", 262);
        map.put("DPAD_LEFT", 263);
        map.put("LEFT", 263);
        map.put("DOWN", 264);
        map.put("DPAD_DOWN", 264);
        map.put("DPAD_UP", 265);
        map.put("UP", 265);
        map.put("PAGE_UP", 266);
        map.put("PAGE_DOWN", 267);
        map.put("HOME", 268);
        map.put("END", 269);
        map.put("CAPS_LOCK", 280);
        map.put("SCROLL_LOCK", 281);
        map.put("NUM_LOCK", 282);
        map.put("PRINT_SCREEN", 283);
        map.put("PAUSE", 284);
        map.put("F1", 290);
        map.put("F2", 291);
        map.put("F3", 292);
        map.put("F4", 293);
        map.put("F5", 294);
        map.put("F6", 295);
        map.put("F7", 296);
        map.put("F8", 297);
        map.put("F9", 298);
        map.put("F10", 299);
        map.put("F11", 300);
        map.put("F12", 301);
        map.put("F13", 302);
        map.put("F14", 303);
        map.put("F15", 304);
        map.put("F16", 305);
        map.put("F17", 306);
        map.put("F18", 307);
        map.put("F19", 308);
        map.put("F20", 309);
        map.put("F21", 310);
        map.put("F22", 311);
        map.put("F23", 312);
        map.put("F24", 313);
        map.put("F25", 314);
        map.put("NUMPAD_0", 320);
        map.put("NUMPAD_1", 321);
        map.put("NUMPAD_2", 322);
        map.put("NUMPAD_3", 323);
        map.put("NUMPAD_4", 324);
        map.put("NUMPAD_5", 325);
        map.put("NUMPAD_6", 326);
        map.put("NUMPAD_7", 327);
        map.put("NUMPAD_8", 328);
        map.put("NUMPAD_9", 329);
        map.put("NUMPAD_DECIMAL", 330);
        map.put("NUMPAD_DIVIDE", 331);
        map.put("NUMPAD_MULTIPLY", 332);
        map.put("NUMPAD_SUBTRACT", 333);
        map.put("NUMPAD_ADD", 334);
        map.put("NUMPAD_ENTER", 335);
        map.put("NUMPAD_EQUAL", 336);
        map.put("LEFT_SHIFT", 340);
        map.put("LEFT_CONTROL", 341);
        map.put("LEFT_ALT", 342);
        map.put("LEFT_SUPER", 343);
        map.put("RIGHT_SHIFT", 344);
        map.put("RIGHT_CONTROL", 345);
        map.put("RIGHT_ALT", 346);
        map.put("RIGHT_SUPER", 347);
        map.put("MENU", 348);
        BY_NAME = Collections.unmodifiableMap(map);
    }

    private KeyCodes() {
    }

    /**
     * Resolves a key name ({@code GRAVE_ACCENT}, {@code G}, {@code RIGHT}) or a
     * raw numeric code. Case and surrounding whitespace are ignored, and a
     * {@code GLFW_KEY_} prefix is tolerated.
     *
     * @return the key code, or {@code fallback} if it cannot be resolved
     */
    public static int resolve(String raw, int fallback) {
        if (raw == null) return fallback;
        String token = raw.trim();
        if (token.isEmpty()) return fallback;

        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            // Not numeric, so treat it as a name.
        }

        String name = token.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (name.startsWith("GLFW_KEY_")) {
            name = name.substring("GLFW_KEY_".length());
        }
        Integer code = BY_NAME.get(name);
        return code != null ? code : fallback;
    }

    /** Reverse lookup, for writing a readable default config. */
    public static String nameOf(int code, String fallback) {
        for (Map.Entry<String, Integer> entry : BY_NAME.entrySet()) {
            if (entry.getValue() == code) return entry.getKey();
        }
        return fallback;
    }
}
