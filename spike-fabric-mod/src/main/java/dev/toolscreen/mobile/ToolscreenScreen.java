package dev.toolscreen.mobile;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The settings menu, opened with comma.
 *
 * <p>The clone panel's stretch is a constant; everything that shapes the main
 * screen in Eye Measure is here: its zoom, crop, width and horizontal stretch,
 * plus the aim speed used while measuring.
 *
 * <h2>Why it is positioned the way it is</h2>
 *
 * The menu lives inside Minecraft's framebuffer, which in this mode is a narrow
 * strip thousands of rows tall with only a slice on screen. GUI coordinates run
 * top-down while the crop is counted from the bottom, so the row the crop puts
 * at screen centre is at {@code (1 - crop) * height} here - and the menu centres
 * on that, following it as the crop moves, so it can never be adjusted out of
 * reach.
 */
public class ToolscreenScreen extends Screen {

    private static final double MAX_MAIN_ZOOM = 16.0;

    private static final double MIN_WIDTH = 0.02;
    private static final double MAX_WIDTH = 0.40;

    private static final double MIN_STRETCH = 0.25;
    private static final double MAX_STRETCH = 2.0;

    private static final double MIN_AIM = 0.01;

    /** Crop nudges: one screen height is about 12% of the render at 8x zoom. */
    private static final double FINE_STEP = 0.001;
    private static final double COARSE_STEP = 0.01;

    private static final int ROWS = 7;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_WIDGET_WIDTH = 200;

    public ToolscreenScreen() {
        super(new LiteralText("Toolscreen"));
    }

    /**
     * The world keeps rendering and ticking behind this.
     *
     * <p>Required, not cosmetic: every control is judged against the live view,
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
        int y = firstRowY();

        addButton(new MainZoomSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;

        addButton(new CropSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;
        int quarter = widgetWidth / 4;
        addButton(new ButtonWidget(left, y, quarter - 2, 20, new LiteralText("<<"),
                b -> nudgeCrop(-COARSE_STEP)));
        addButton(new ButtonWidget(left + quarter, y, quarter - 2, 20, new LiteralText("<"),
                b -> nudgeCrop(-FINE_STEP)));
        addButton(new ButtonWidget(left + 2 * quarter, y, quarter - 2, 20, new LiteralText(">"),
                b -> nudgeCrop(FINE_STEP)));
        addButton(new ButtonWidget(left + 3 * quarter, y, quarter - 2, 20, new LiteralText(">>"),
                b -> nudgeCrop(COARSE_STEP)));
        y += ROW_HEIGHT;

        addButton(new WidthSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;
        addButton(new StretchSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;
        addButton(new AimSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;

        addButton(new ButtonWidget(left, y, widgetWidth, 20, new LiteralText("Done"),
                b -> onClose()));
    }

    /** Top of the control block, centred on the row the crop shows mid-screen. */
    private int firstRowY() {
        int centre = (int) Math.round(this.height * (1.0 - ToolscreenMobile.cropCentre()));
        return centre - (ROWS * ROW_HEIGHT) / 2;
    }

    private void nudgeCrop(double delta) {
        ToolscreenMobile.setCropCentre(ToolscreenMobile.cropCentre() + delta);
        rebuild();
    }

    /** Re-runs {@link #init()} so the rows follow the crop they just moved. */
    private void rebuild() {
        if (this.client != null) {
            this.init(this.client, this.width, this.height);
        }
    }

    /** Rebuilds the render target after a change to its size. */
    private void resizeRenderTarget() {
        if (this.client != null) {
            this.client.onResolutionChanged();
        }
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
        // No renderBackground: the view behind is what every control is judged
        // against, and dimming it would defeat the purpose.
        int top = firstRowY();
        drawCentredLine(matrices,
                ToolscreenMobile.renderWidth() + "x" + ToolscreenMobile.renderHeight(), top - 22);
        drawCentredLine(matrices, "Ninjabrain: " + ToolscreenMobile.renderHeight(), top - 11);
        super.render(matrices, mouseX, mouseY, delta);
    }

    private void drawCentredLine(MatrixStack matrices, String text, int y) {
        int x = (this.width - this.textRenderer.getWidth(text)) / 2;
        this.textRenderer.drawWithShadow(matrices, text, x, y, 0xFFFFFF);
    }

    private static double toSlider(double value, double min, double max) {
        return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    /**
     * How much taller than the screen the game renders - the main screen's zoom.
     *
     * <p>Applied on release: each change reallocates a framebuffer of tens of
     * megabytes. Not cosmetic - it sets the render height, the figure Ninjabrain
     * Bot needs, so the label shows it and it has to be re-entered there
     * whenever this moves.
     */
    private class MainZoomSlider extends SliderWidget {

        MainZoomSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    toSlider(ToolscreenMobile.mainZoom(), 1.0, MAX_MAIN_ZOOM));
            updateMessage();
        }

        private double zoom() {
            return 1.0 + this.value * (MAX_MAIN_ZOOM - 1.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Main zoom  %.1fx  (%d px)",
                    zoom(), ToolscreenMobile.renderHeight())));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setMainZoom(zoom());
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            resizeRenderTarget();
            updateMessage();
        }
    }

    /** Which framebuffer row lands at the centre of the screen. 50% is centred. */
    private class CropSlider extends SliderWidget {

        CropSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""), ToolscreenMobile.cropCentre());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Crop  %.1f%%", this.value * 100.0)));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setCropCentre(this.value);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            rebuild();
        }
    }

    /**
     * Width of the game window in Eye Measure, as a share of the screen. Also the
     * framebuffer width, so applied on release like the zoom.
     */
    private class WidthSlider extends SliderWidget {

        WidthSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    toSlider(ToolscreenMobile.eyeWidth(), MIN_WIDTH, MAX_WIDTH));
            updateMessage();
        }

        private double share() {
            return MIN_WIDTH + this.value * (MAX_WIDTH - MIN_WIDTH);
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Width  %.1f%%  (%d px)",
                    share() * 100.0, ToolscreenMobile.renderWidth())));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setEyeWidth(share());
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            resizeRenderTarget();
            updateMessage();
        }
    }

    /**
     * Horizontal scale of the drawn view. Cosmetic: the ruler reads the
     * framebuffer, which this never touches.
     */
    private class StretchSlider extends SliderWidget {

        StretchSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    toSlider(ToolscreenMobile.mainStretch(), MIN_STRETCH, MAX_STRETCH));
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
     * Look speed while measuring, as a share of the player's normal speed.
     * Linear, with no floor - see {@code MouseMixin} for why that matters.
     */
    private class AimSlider extends SliderWidget {

        AimSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    toSlider(ToolscreenMobile.aimSpeed(), MIN_AIM, 1.0));
            updateMessage();
        }

        private double speed() {
            return MIN_AIM + this.value * (1.0 - MIN_AIM);
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(String.format("Aim speed  %.0f%% of normal", speed() * 100.0)));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setAimSpeed(speed());
        }
    }
}
