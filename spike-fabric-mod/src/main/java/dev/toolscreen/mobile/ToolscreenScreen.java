package dev.toolscreen.mobile;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The settings menu, opened with comma.
 *
 * <p>These two numbers were config-file entries, which meant a round trip
 * through a text editor and a relaunch to try a value - and the crop in
 * particular is something you judge by looking at the screen, not by reasoning
 * about it. Everything here applies as it is dragged, so the answer comes from
 * the view rather than from another build.
 *
 * <h2>Why it is positioned the way it is</h2>
 *
 * The menu lives inside Minecraft's framebuffer, which in this mode is a narrow
 * strip thousands of rows tall, most of it off-screen. A screen laid out the
 * usual way - centred on {@code height / 2} - would therefore sit at the
 * framebuffer's middle, which is only the middle of the display when the crop
 * is centred. Since the whole point of this menu is fixing a crop that is
 * <em>not</em> centred, that would put the controls out of reach exactly when
 * they are needed. So it centres on the same row the crop puts at the middle of
 * the screen, and follows it as that changes.
 */
public class ToolscreenScreen extends Screen {

    /** Fine and coarse steps for the crop, as fractions of the render height. */
    private static final double FINE_STEP = 0.002;
    private static final double COARSE_STEP = 0.02;

    /** Upper bound of the stretch sliders; the panel reduces it to fit. */
    private static final int MAX_STRETCH = 64;

    /** Upper bound of the main-screen zoom; the GPU's limits reduce it further. */
    private static final double MAX_MAIN_ZOOM = 16.0;

    private static final int ROW_HEIGHT = 22;
    private static final int MAX_WIDGET_WIDTH = 200;

    public ToolscreenScreen() {
        super(new LiteralText("Toolscreen"));
    }

    /**
     * The world keeps rendering and ticking behind this.
     *
     * <p>Required, not cosmetic: every control here is judged against the live
     * view, and a paused single-player world stops redrawing it.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int widgetWidth = Math.min(MAX_WIDGET_WIDTH, Math.max(60, this.width - 12));
        int left = (this.width - widgetWidth) / 2;

        // The row the crop places at the centre of the display. See the class
        // comment: using height / 2 would hide this menu whenever the crop is
        // the thing that needs fixing.
        int centre = (int) Math.round(this.height * ToolscreenMobile.cropCentre());
        int y = centre - 3 * ROW_HEIGHT;

        addButton(new CropSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;

        int third = widgetWidth / 4;
        addButton(new ButtonWidget(left, y, third - 2, 20, new LiteralText("<<"),
                b -> nudgeCrop(-COARSE_STEP)));
        addButton(new ButtonWidget(left + third, y, third - 2, 20, new LiteralText("<"),
                b -> nudgeCrop(-FINE_STEP)));
        addButton(new ButtonWidget(left + 2 * third, y, third - 2, 20, new LiteralText(">"),
                b -> nudgeCrop(FINE_STEP)));
        addButton(new ButtonWidget(left + 3 * third, y, third - 2, 20, new LiteralText(">>"),
                b -> nudgeCrop(COARSE_STEP)));
        y += ROW_HEIGHT;

        addButton(new MainZoomSlider(left, y, widgetWidth, 20));
        y += ROW_HEIGHT;
        addButton(new StretchSlider(left, y, widgetWidth, 20, true));
        y += ROW_HEIGHT;
        addButton(new StretchSlider(left, y, widgetWidth, 20, false));
        y += ROW_HEIGHT;

        int half = widgetWidth / 2;
        addButton(new ButtonWidget(left, y, half - 2, 20, new LiteralText("Centre"), b -> {
            ToolscreenMobile.setCropCentre(0.5);
            rebuild();
        }));
        addButton(new ButtonWidget(left + half, y, half - 2, 20, new LiteralText("Done"),
                b -> onClose()));
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

    @Override
    public void onClose() {
        ToolscreenMobile.save();
        if (this.client != null) {
            this.client.openScreen(null);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        // No renderBackground: the view behind is what every control here is
        // being judged against, and dimming it would defeat the purpose.
        int centre = (int) Math.round(this.height * ToolscreenMobile.cropCentre());

        String resolution = ToolscreenMobile.renderWidth() + "x" + ToolscreenMobile.renderHeight();
        drawCentredLine(matrices, resolution, centre - 3 * ROW_HEIGHT - 10);
        drawCentredLine(matrices, "Ninjabrain: " + ToolscreenMobile.renderHeight(),
                centre - 3 * ROW_HEIGHT);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void drawCentredLine(MatrixStack matrices, String text, int y) {
        int x = (this.width - this.textRenderer.getWidth(text)) / 2;
        this.textRenderer.drawWithShadow(matrices, text, x, y, 0xFFFFFF);
    }

    /**
     * Which framebuffer row lands at the centre of the screen.
     *
     * <p>Shown as a percentage rather than the raw fraction, and paired with the
     * pixel row it selects, because the useful comparison while adjusting is
     * against the render height rather than against 1.0.
     */
    private class CropSlider extends SliderWidget {

        CropSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""), ToolscreenMobile.cropCentre());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int row = (int) Math.round(ToolscreenMobile.renderHeight() * this.value);
            setMessage(new LiteralText(String.format("Crop %.1f%%  (row %d)", this.value * 100.0, row)));
        }

        @Override
        protected void applyValue() {
            ToolscreenMobile.setCropCentre(this.value);
        }
    }

    /**
     * How much taller than the screen the game renders, which is the main
     * screen's zoom.
     *
     * <p>Applied on release rather than while dragging: each change reallocates
     * a framebuffer that can be tens of megabytes, and doing that every frame of
     * a drag would stall the game rather than preview anything.
     *
     * <p>Independent of the clone panel's sliders below by design. This one
     * decides how much angle a framebuffer pixel covers - the resolution of the
     * measurement - while those decide how large that pixel is drawn.
     */
    private class MainZoomSlider extends SliderWidget {

        MainZoomSlider(int x, int y, int width, int height) {
            super(x, y, width, height, new LiteralText(""),
                    (ToolscreenMobile.mainZoom() - 1.0) / (MAX_MAIN_ZOOM - 1.0));
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
            if (client != null) {
                // Forces the render target to be rebuilt at the new height; the
                // reported framebuffer size has changed but nothing re-reads it
                // until a resize happens.
                client.onResolutionChanged();
            }
            updateMessage();
        }
    }

    /**
     * Horizontal or vertical magnification of the clone panel.
     *
     * <p>The slider is over 1..64; the panel reduces whatever it is given until
     * the result fits beside the game window, so a large value here is a request
     * rather than a promise.
     */
    private class StretchSlider extends SliderWidget {

        private final boolean horizontal;

        StretchSlider(int x, int y, int width, int height, boolean horizontal) {
            super(x, y, width, height, new LiteralText(""),
                    (horizontal ? ToolscreenMobile.eyeZoomFactorX()
                            : ToolscreenMobile.eyeZoomFactorY()) / (double) MAX_STRETCH);
            this.horizontal = horizontal;
            updateMessage();
        }

        private int factor() {
            return Math.max(1, (int) Math.round(this.value * MAX_STRETCH));
        }

        @Override
        protected void updateMessage() {
            setMessage(new LiteralText(
                    (this.horizontal ? "Stretch X  " : "Stretch Y  ") + factor() + "x"));
        }

        @Override
        protected void applyValue() {
            if (this.horizontal) {
                ToolscreenMobile.setEyeZoomFactorX(factor());
            } else {
                ToolscreenMobile.setEyeZoomFactorY(factor());
            }
        }
    }
}
