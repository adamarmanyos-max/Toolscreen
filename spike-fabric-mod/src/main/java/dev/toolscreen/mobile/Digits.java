package dev.toolscreen.mobile;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;

/**
 * A plain sans-serif digit font, drawn as rectangles.
 *
 * <p>The ruler numbers in the original are not Minecraft's font, and Minecraft's
 * is the only one a mod has to hand - it ships no other, and loading a system
 * typeface from a Fabric mod means a font atlas, a texture and a resource pack,
 * which is a lot of machinery for twenty-odd numerals. Ten 5x7 bitmaps cover
 * every label the ruler can show.
 *
 * <p>Drawn with the same fill call as everything else here, so the glyphs scale
 * with the ruler and stay crisp at any size: each bitmap pixel becomes one
 * rectangle, which is how the rest of the panel is drawn anyway.
 */
public final class Digits {

    /** 5 wide, 7 tall, one bit per pixel, most significant bit on the left. */
    private static final byte[][] GLYPHS = {
            {0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110}, // 0
            {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // 1
            {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111}, // 2
            {0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110}, // 3
            {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010}, // 4
            {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110}, // 5
            {0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110}, // 6
            {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000}, // 7
            {0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110}, // 8
            {0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100}, // 9
    };

    public static final int GLYPH_WIDTH = 5;
    public static final int GLYPH_HEIGHT = 7;
    private static final int SPACING = 1;

    private Digits() {
    }

    /** Width of {@code text} once drawn at {@code scale}, in pixels. */
    public static int width(String text, int scale) {
        if (text.isEmpty()) return 0;
        return (text.length() * (GLYPH_WIDTH + SPACING) - SPACING) * scale;
    }

    public static int height(int scale) {
        return GLYPH_HEIGHT * scale;
    }

    /**
     * Largest whole scale at which {@code text} fits the given box.
     *
     * <p>Whole rather than fractional so glyph pixels stay square and identical,
     * for the same reason the magnified columns do.
     */
    public static int fitScale(String text, int maxWidth, int maxHeight) {
        if (text.isEmpty()) return 0;
        int byWidth = maxWidth / Math.max(1, text.length() * (GLYPH_WIDTH + SPACING) - SPACING);
        int byHeight = maxHeight / GLYPH_HEIGHT;
        return Math.max(1, Math.min(byWidth, byHeight));
    }

    /** Draws {@code text} with its top-left corner at {@code x, y}. */
    public static void draw(MatrixStack matrices, String text, int x, int y, int scale, int colour) {
        if (scale < 1) return;
        int penX = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                drawGlyph(matrices, GLYPHS[c - '0'], penX, y, scale, colour);
            }
            penX += (GLYPH_WIDTH + SPACING) * scale;
        }
    }

    private static void drawGlyph(MatrixStack matrices, byte[] glyph, int x, int y, int scale, int colour) {
        for (int row = 0; row < GLYPH_HEIGHT; row++) {
            int bits = glyph[row] & 0xFF;
            int col = 0;
            while (col < GLYPH_WIDTH) {
                if ((bits & (1 << (GLYPH_WIDTH - 1 - col))) == 0) {
                    col++;
                    continue;
                }
                // Merge horizontal runs, as the magnifier does: fewer draw calls
                // for the same output, which matters at 24 labels a frame.
                int run = 1;
                while (col + run < GLYPH_WIDTH
                        && (bits & (1 << (GLYPH_WIDTH - 1 - col - run))) != 0) {
                    run++;
                }
                int px = x + col * scale;
                int py = y + row * scale;
                DrawableHelper.fill(matrices, px, py, px + run * scale, py + scale, colour);
                col += run;
            }
        }
    }
}
