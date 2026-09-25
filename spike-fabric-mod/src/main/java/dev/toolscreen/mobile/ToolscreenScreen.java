package dev.toolscreen.mobile;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The settings menu, opened with comma.
 *
 * <p>Three controls. The crop and the main zoom are constants - they have one
 * correct value each, found on the device, and an adjustable one only invites
 * being moved to something that reads plausibly and measures wrongly. The
 * stretch is not like that: it trades how much of the frame the panel shows
 * against how legible a pixel is, and which side of that trade is right depends
 * on what is being measured.
 *
 * <h2>Why it is positioned the way it is</h2>
 *
 * The menu lives inside Minecraft's framebuffer, which in this mode is a narrow
 * strip thousands of rows tall, most of it off-screen. Laid out the usual way -
 * centred on {@code height / 2} - it would sit at the framebuffer's middle,
 * which is not the middle of the display while the crop is offset, and the crop
 * always is. So it centres on the row the crop actually shows.
 */
public class ToolscreenScreen extends Screen {

    /**
     * Top of the sensitivity slider. 0.5 is 100% on Minecraft's own scale, so
     * the whole useful range is reachable and nothing is clamped by this end.
     */
    private static final double MAX_SENSITIVITY = 0.5;

    /** Slider tops. X needs the range to stay legible; Y only needs context. */
    private static final int MAX_STRETCH_X = 96;
    private static final int MAX_STRETCH_Y = 24;

    private static final int ROWS = 4;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_WIDGET_WIDTH = 200;

    public ToolscreenScreen() {
        super(new LiteralText("Toolscreen"));
    }

    /**
     * The world keeps rendering and ticking behind this.
     *
     * <p>Required, not cosmetic: the sensitivity is judged by moving the view,
     * and a paused single-player world stops redrawing it.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int widgetWidth = Math.min(MAX_WIDGET_WIDTH, Math.max(60, this.width - 12));
        int left = (this.width - widgetWidth) / 2;
        int y = firstRowY((int) Math.round(this.height * ToolscreenMobile.cropCentre()));

        addButton(new StretchSlider(left, y, widgetWidth, 20, true));
        y += ROW_HEIGHT;
        addButton(new StretchSlider(left, y, widgetWidth, 20, false));
        y += ROW_HEIGHT;
        addButton(new SensitivitySlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;
        addButton(new ButtonWidget(left, y, widgetWidth, 20, new LiteralText("Done"),
                button -> onClose()));
    }

    /** Top of the control block, centred on the row the crop shows. */
    private static int firstRowY(int centre) {
        return centre - (ROWS * ROW_HEIGHT) / 2;
    }

    @Override
    public void onClose() {
        ToolscreenMobile.save();
        if (this.client != null) {
            this.client.openScreen(null);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        // No renderBackground: the view behind is what the sensitivity is being
        // judged against, and dimming it would defeat the purpose.
        int centre = (int) Math.round(this.height * ToolscreenMobile.cropCentre());
        int top = firstRowY(centre);

        drawCentredLine(matrices,
                ToolscreenMobile.renderWidth() + "x" + ToolscreenMobile.renderHeight(), top - 22);
        drawCentredLine(matrices, "Ninjabrain: " + ToolscreenMobile.renderHeight(), top - 11);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void drawCentredLine(MatrixStack matrices, String text, int y) {
        int x = (this.width - this.textRenderer.getWidth(text)) / 2;
        this.textRenderer.drawWithShadow(matrices, text, x, y, 0xFFFFFF);
    }

    /**
     * Screen pixels per framebuffer pixel, on one axis of the clone panel.
     *
     * <p>Shows the real figure rather than a factor with a multiplier applied
     * elsewhere, so the number on the slider is the number of pixels drawn.
     *
     * <p>Because the panel is a fixed size, raising this shows less of the
     * frame at a larger scale and lowering it shows more at a smaller one; it
     * does not resize anything. The two axes are separate because the ruler
     * counts horizontal offsets, so the horizontal figure is what has to be
     * legible while the vertical one only buys context.
     */
    private class StretchSlider extends SliderWidget {

        private final boolean horizontal;

        StretchSlider(int x, int y, int width, int height, boolean horizontal) {
            super(x, y, width, height, new LiteralText(""),
                    toSlider(horizontal ? ToolscreenMobile.eyeZoomFactorX()
                            : ToolscreenMobile.eyeZoomFactorY(), horizontal));
            this.horizontal = horizontal;
            updateMessage();
        }

        private int factor() {
            int max = this.horizontal ? MAX_STRETCH_X : MAX_STRETCH_Y;
            return Math.max(1, (int) Math.round(this.value * max));
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(
                    (this.horizontal ? "Stretch X  " : "Stretch Y  ") + factor() + " px"));
        }

        @Override
        protected void applyValue() {
            if (this.horizontal) {
                ToolscreenMobile.setStretchX(factor());
            } else {
                ToolscreenMobile.setStretchY(factor());
            }
        }
    }

    /**
     * Slider position for a stretch factor. Static because it is needed to build
     * the slider's initial value, which is a {@code super(...)} argument - and
     * an instance method cannot be called before the supertype constructor runs.
     */
    private static double toSlider(int factor, boolean horizontal) {
        int max = horizontal ? MAX_STRETCH_X : MAX_STRETCH_Y;
        return Math.max(0.0, Math.min(1.0, factor / (double) max));
    }

    /**
     * Mouse sensitivity used while measuring.
     *
     * <p>Shows both the percentage and the raw value: the percentage matches
     * what Minecraft's own options screen would say, and the raw number is what
     * actually gets written. If the view stops getting slower before the slider
     * reaches its bottom, those two together say whether this control is the
     * thing clamping or something downstream of it is.
     */
    private class SensitivitySlider extends SliderWidget {

        SensitivitySlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    ToolscreenMobile.measureSensitivity() / MAX_SENSITIVITY);
            updateMessage();
        }

        private double sensitivity() {
            return Math.max(0.0, this.value * MAX_SENSITIVITY);
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Aim sens  %.1f%%  (%.3f)",
                    sensitivity() * 200.0, sensitivity())));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setMeasureSensitivity(sensitivity());
        }
    }
}
