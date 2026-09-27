package dev.toolscreen.mobile;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The settings menu, opened with comma.
 *
 * <p>Two controls. The crop, the main zoom and the clone panel's stretch are
 * constants - each has one correct value, found on the device, and an
 * adjustable one only invites being moved to something that reads plausibly
 * and measures wrongly. What is left is the main screen's stretch, which
 * corrects a fault whose cause is still unknown, and the aim sensitivity, which
 * is a matter of preference.
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

    /** Main-screen stretch range. 1.0 is uncorrected; the measured fault wants ~0.66. */
    private static final double MIN_STRETCH = 0.25;
    private static final double MAX_STRETCH = 2.0;

    private static final int ROWS = 3;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_WIDGET_WIDTH = 200;

    public ToolscreenScreen() {
        super(new LiteralText("Toolscreen"));
    }

    /**
     * The world keeps rendering and ticking behind this.
     *
     * <p>Required, not cosmetic: both controls are judged against the live view,
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

        addButton(new StretchSlider(left, y, widgetWidth, 20));
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

    /**
     * Slider position for a stretch value. Static because it is needed to build
     * the slider's initial value, which is a {@code super(...)} argument - and
     * an instance method cannot be called before the supertype constructor runs.
     */
    private static double stretchToSlider(double stretch) {
        return Math.max(0.0, Math.min(1.0, (stretch - MIN_STRETCH) / (MAX_STRETCH - MIN_STRETCH)));
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
        // No renderBackground: the view behind is what both controls are judged
        // against, and dimming it would defeat the purpose.
        int top = firstRowY((int) Math.round(this.height * ToolscreenMobile.cropCentre()));

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
     * The main screen's horizontal scale.
     *
     * <p>Below 1 narrows the view, which is the direction that undoes a
     * horizontal stretch. Applied as it is dragged: it only changes a viewport,
     * so unlike the render height there is nothing expensive to rebuild.
     *
     * <p>Cosmetic only. The ruler counts pixels sampled from the framebuffer,
     * which this never touches, so no position of this slider can make a
     * reading wrong.
     */
    private class StretchSlider extends SliderWidget {

        StretchSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    stretchToSlider(ToolscreenMobile.mainStretch()));
            updateMessage();
        }

        private double stretch() {
            return MIN_STRETCH + this.value * (MAX_STRETCH - MIN_STRETCH);
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Main stretch X  %.2fx", stretch())));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setMainStretch(stretch());
        }
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
