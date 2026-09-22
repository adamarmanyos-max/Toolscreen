package dev.toolscreen.mobile;

/**
 * Every on-screen size and position for EyeZoom mode, derived from the screen
 * size and a handful of fractions.
 *
 * <p>Nothing here is a pixel constant. The original is a Windows tool sized
 * against one desktop monitor, so copying its measurements literally would only
 * be right on a 2778x1940 screen; they are stored as fractions of the screen
 * instead, which reproduce those numbers at that resolution and stay sensible
 * elsewhere. The fractions themselves live in {@link ToolscreenMobile}, so this
 * class is pure arithmetic and can be reasoned about on its own.
 *
 * <h2>Why the grid is built outward from the centre</h2>
 *
 * The measurement is an offset in framebuffer pixels either side of the
 * crosshair, so two things have to be exactly true rather than approximately
 * true: one ruler cell is one framebuffer pixel, and the divider sits on the
 * boundary between the two middle pixels. Both fall out of choosing an integer
 * cell width first and then laying columns out from the centre line, rather
 * than dividing a panel width by a cell count and rounding each cell
 * separately - which is what made some magnified columns a pixel wider than
 * others.
 */
public final class Layout {

    /** Screen, in real pixels. */
    public final int screenWidth;
    public final int screenHeight;

    /** The game window on screen: full height, horizontally centred. */
    public final int gameX;
    public final int gameY;
    public final int gameWidth;
    public final int gameHeight;

    /** The magnifier panel. */
    public final int panelX;
    public final int panelY;
    public final int panelWidth;
    public final int panelHeight;

    /** One framebuffer pixel, magnified. Width is the ruler cell width exactly. */
    public final int cellWidth;

    /** Framebuffer columns the panel shows. Always even, so there are two middle ones. */
    public final int columns;

    /** The divider: the boundary between the two middle framebuffer pixels. */
    public final int centreX;

    public final int rulerX;
    public final int rulerY;
    public final int rulerWidth;
    public final int rulerHeight;
    public final int rulerCells;

    private Layout(int screenWidth, int screenHeight, int gameX, int gameY, int gameWidth,
                   int gameHeight, int panelX, int panelY, int panelWidth, int panelHeight,
                   int cellWidth, int columns, int centreX, int rulerX, int rulerY,
                   int rulerWidth, int rulerHeight, int rulerCells) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.gameX = gameX;
        this.gameY = gameY;
        this.gameWidth = gameWidth;
        this.gameHeight = gameHeight;
        this.panelX = panelX;
        this.panelY = panelY;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        this.cellWidth = cellWidth;
        this.columns = columns;
        this.centreX = centreX;
        this.rulerX = rulerX;
        this.rulerY = rulerY;
        this.rulerWidth = rulerWidth;
        this.rulerHeight = rulerHeight;
        this.rulerCells = rulerCells;
    }

    /**
     * Works out the whole layout, or returns null if the screen cannot hold it.
     *
     * @param renderWidth the framebuffer width, which is also the on-screen game
     *                    window width: the framebuffer is blitted at 1:1, so
     *                    they are the same number by construction
     */
    public static Layout compute(int screenWidth, int screenHeight, int renderWidth) {
        if (screenWidth < 16 || screenHeight < 16 || renderWidth < 2) return null;

        int gameWidth = Math.min(renderWidth, screenWidth);
        int gameX = (screenWidth - gameWidth) / 2;

        int panelHeight = even(Math.round(screenHeight * (float) ToolscreenMobile.panelHeightFraction()));
        int panelWidth = even(Math.round(panelHeight * (float) ToolscreenMobile.panelAspect()));
        if (panelHeight < 16 || panelWidth < 16) return null;

        int panelY = (screenHeight - panelHeight) / 2;

        // Centred in the band to the left of the game window, which makes the
        // left margin equal to the gap between panel and game window.
        int panelX = (gameX - panelWidth) / 2;
        if (panelX < 0) return null;

        int rulerCells = ToolscreenMobile.rulerCells();
        if (rulerCells < 2 || rulerCells % 2 != 0) return null;

        // Integer cell width, so every magnified column is identical. The ruler
        // is then exactly cells x cellWidth, which is at or just under the
        // configured fraction of the panel rather than over it.
        int cellWidth = (int) Math.floor(panelWidth * ToolscreenMobile.rulerWidthFraction() / rulerCells);
        if (cellWidth < 1) return null;

        int centreX = panelX + panelWidth / 2;
        int rulerWidth = cellWidth * rulerCells;
        int rulerX = centreX - rulerWidth / 2;
        int rulerHeight = Math.max(1, Math.round(screenHeight * (float) ToolscreenMobile.rulerHeightFraction()));
        int rulerY = panelY + (panelHeight - rulerHeight) / 2;

        // As many whole columns as fit the panel, kept even so the divider lands
        // between two of them rather than through the middle of one.
        int columns = 2 * (panelWidth / (2 * cellWidth));
        if (columns < rulerCells) columns = rulerCells;

        return new Layout(screenWidth, screenHeight, gameX, 0, gameWidth, screenHeight,
                panelX, panelY, panelWidth, panelHeight, cellWidth, columns, centreX,
                rulerX, rulerY, rulerWidth, rulerHeight, rulerCells);
    }

    /** Left edge of the magnified image: the column grid, centred on the divider. */
    public int pixelsX() {
        return centreX - (columns / 2) * cellWidth;
    }

    /** True if the panel would touch the game window - it never should. */
    public boolean overlapsGame() {
        int left = Math.min(pixelsX(), rulerX);
        int right = Math.max(pixelsX() + columns * cellWidth, rulerX + rulerWidth);
        return left < gameX + gameWidth && right > gameX;
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    @Override
    public String toString() {
        return String.format(
                "screen %dx%d | game %dx%d at x=%d | panel %dx%d at (%d,%d) | cell %dpx x %d cols"
                        + " | ruler %dx%d at (%d,%d), %d cells | divider x=%d",
                screenWidth, screenHeight, gameWidth, gameHeight, gameX,
                panelWidth, panelHeight, panelX, panelY, cellWidth, columns,
                rulerWidth, rulerHeight, rulerX, rulerY, rulerCells, centreX);
    }
}
