package principal;

import java.nio.ByteBuffer;

import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritablePixelFormat;

/**
 * Info panel of the screen: the column of the preview box and the two digit
 * markers (score and lines left), painted pixel by pixel.
 * <p>
 * This is the second painting model of the program. The board, the falling
 * piece and the preview live on the 15 x 20 logical grid that {@link Renderer}
 * composes; the markers do not: they are free-form rectangles with a 4 x 5
 * bitmap font, painted into their own BGRA buffer and copied to the image with
 * a single blit. Keeping them here leaves the renderer with one single
 * responsibility: compose the grid and dump it to pixels.
 * <p>
 * The layout is derived from the logical grid (cells) and the resulting pixels
 * per cell; the only constant measured in pixels is {@link #SCORE_GAP}.
 */
class InfoPanel {

    /** Next-piece box: square of BOX_CELLS cells. */
    static final int BOX_CELLS = 4;
    /** Extra pixels between the next-piece box and the score marker. */
    private static final int SCORE_GAP = 2;
    /** Digits of every marker. */
    private static final int MARKER_DIGITS = 4;

    /**
     * Geometry of the logical grid, as derived by the {@link Renderer}: the
     * panel is laid out inside that grid, never in absolute canvas pixels.
     */
    record Layout(int cellWidth, int cellHeight, int pixelSize,
                  int offsetX, int offsetY, int canvasWidth, int canvasHeight) { }

    private final Palette palette;
    private final PixelWriter pixelWriter;
    private final WritablePixelFormat<ByteBuffer> pixelFormat;
    private final Layout grid;

    /** Side length of the preview box, in pixels. */
    private final int boxPx;
    /** Height of a marker: half the preview box. */
    private final int markerH;
    /** Column where the preview box starts (cells) and the same thing in pixels. */
    private final int boxXCells;
    private final int boxXpx;
    /** y of the score marker (below the box) and y of the lines marker (bottom). */
    private final int scoreY;
    private final int linesY;
    /** Width of each digit slot and scale of the font. */
    private final int slot;
    private final int digAn;
    private final int digLa;

    /** Reusable block of pixels BGRA (a marker box of boxPx x markerH). */
    private final byte[] markerBuffer;

    /**
     * Creates the panel.
     *
     * @param palette     palette the colours are read from
     * @param pixelWriter writer of the image the markers are copied to
     * @param pixelFormat byte format of that image (BGRA)
     * @param grid        geometry of the logical grid derived by the renderer
     * @param infoX0      first column of the panel (right after the separator)
     */
    InfoPanel(Palette palette, PixelWriter pixelWriter,
              WritablePixelFormat<ByteBuffer> pixelFormat, Layout grid, int infoX0) {
        this.palette = palette;
        this.pixelWriter = pixelWriter;
        this.pixelFormat = pixelFormat;
        this.grid = grid;

        boxPx = BOX_CELLS * grid.pixelSize();
        markerH = boxPx / 2;

        // The box is centred in the panel; the markers go below it
        int panelW = Math.max(0, grid.cellWidth() - infoX0);
        int extra = Math.max(0, (panelW - BOX_CELLS) / 2);
        boxXCells = infoX0 + extra;
        boxXpx = grid.offsetX() + boxXCells * grid.pixelSize();
        scoreY = grid.offsetY() + boxPx + SCORE_GAP;
        linesY = grid.offsetY() + Math.max(0, grid.cellHeight() * grid.pixelSize() - boxPx);

        int lps = boxPx - 4;
        slot = Math.max(4, Math.round((float) lps / MARKER_DIGITS));
        digAn = Math.max(2, Math.round((float) (slot - 2) / 4));
        digLa = Math.max(3, Math.round((float) (markerH - 1) / 5));

        markerBuffer = new byte[boxPx * markerH * 4];
    }

    // ==================================================================
    // Geometry (asked by the Renderer to place the preview piece)
    // ==================================================================

    /** Column of the grid where the preview box starts. */
    int boxXCells() {
        return boxXCells;
    }

    /** true if the preview box fits inside the grid. */
    boolean hasBox() {
        return (boxXCells + BOX_CELLS <= grid.cellWidth())
                && (BOX_CELLS <= grid.cellHeight());
    }

    // ==================================================================
    // Markers
    // ==================================================================

    /**
     * Paints both markers: the score (black background, red digits) and the
     * lines left (light background, blue digits). A marker that does not fit
     * in the canvas is simply skipped.
     *
     * @param score     current score
     * @param linesLeft remaining lines needed to clear the screen
     */
    void draw(int score, int linesLeft) {
        if (hasMarker(scoreY)) drawMarker(score, true);
        if (hasMarker(linesY)) drawMarker(linesLeft, false);
    }

    /** true if a marker box of boxPx x markerH fits at y-offset 'y'. */
    private boolean hasMarker(int y) {
        return (boxXpx + boxPx <= grid.canvasWidth())
                && (y >= 0) && (y + markerH <= grid.canvasHeight());
    }

    /**
     * Draws one marker box: background, raised 3D border, and four right-aligned
     * digits using the scaled 4x5 bitmap font.
     */
    private void drawMarker(int value, boolean isScore) {
        int idxBg = isScore ? 0 : Palette.MARKER_BG;
        int idxFg = isScore ? Palette.DIGIT_RED : Palette.DIGIT_BLUE;
        byte[] bg = palette.pixel(idxBg, Palette.BASE);
        byte[] bgBr = palette.pixel(idxBg, Palette.BRIGHT);
        byte[] bgDa = palette.pixel(idxBg, Palette.DARK);
        byte[] fg = palette.pixel(idxFg, Palette.BASE);
        byte[] fgBr = palette.pixel(idxFg, Palette.BRIGHT);
        byte[] fgDa = palette.pixel(idxFg, Palette.DARK);

        java.util.Arrays.fill(markerBuffer, (byte) 0);
        fillRect(0, 0, boxPx, markerH, bg);

        int mm = value;
        int margY = 2;
        for (int d = 0; d < MARKER_DIGITS; d++) {
            int n = mm % 10;
            mm /= 10;
            int slotX = boxPx - (d + 1) * slot + 1;
            drawDigit(n, slotX, margY, fg, fgBr, fgDa);
        }

        // Raised border of the box: top/left bright, bottom/right dark
        // (the last one wins in the corners)
        hLine(0, 0, boxPx - 1, bgBr);
        vLine(0, 0, markerH - 1, bgBr);
        hLine(0, markerH - 1, boxPx - 1, bgDa);
        vLine(boxPx - 1, 0, markerH - 1, bgDa);

        pixelWriter.setPixels(boxXpx, isScore ? scoreY : linesY,
                boxPx, markerH, pixelFormat, markerBuffer, 0, boxPx * 4);
    }

    /** Draws one digit of the 4x5 scaled bitmap font with 3D edges. */
    private void drawDigit(int n, int slotX, int y0,
                           byte[] fg, byte[] fgBr, byte[] fgDa) {
        boolean[][] mask = DIGITS[n];
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 4; col++) {
                if (!mask[row][col]) continue;
                int px = slotX + digAn * col;
                int py = y0 + digLa * row;
                fillRect(px, py, digAn, digLa, fg);
                if (col == 0 || !mask[row][col - 1]) vLine(px, py, digLa, fgBr);
                if (row == 0 || !mask[row - 1][col]) hLine(px, py, digAn, fgBr);
                if (row == 4 || !mask[row + 1][col]) hLine(px, py + digLa - 1, digAn, fgDa);
                if (col == 3 || !mask[row][col + 1]) vLine(px + digAn - 1, py, digLa, fgDa);
            }
        }
    }

    // ==================================================================
    // Mini rasteriser of the marker buffer (every write is clamped)
    // ==================================================================

    /** Fills a rectangle of the marker buffer with a BGRA color. */
    private void fillRect(int x, int y, int w, int h, byte[] c) {
        for (int r = y; r < y + h; r++) {
            if (r < 0 || r >= markerH) {
                continue;
            }
            for (int cc = x; cc < x + w; cc++) {
                if (cc < 0 || cc >= boxPx) {
                    continue;
                }
                put(r * boxPx + cc, c);
            }
        }
    }

    /** Horizontal segment of the marker buffer. */
    private void hLine(int x, int y, int x2, byte[] c) {
        for (int cc = x; cc <= x2; cc++) {
            if (cc >= 0 && cc < boxPx && y >= 0 && y < markerH) {
                put(y * boxPx + cc, c);
            }
        }
    }

    /** Vertical segment of the marker buffer. */
    private void vLine(int x, int y, int h, byte[] c) {
        for (int r = y; r < y + h; r++) {
            if (x >= 0 && x < boxPx && r >= 0 && r < markerH) {
                put(r * boxPx + x, c);
            }
        }
    }

    /** Puts a color BGRA in a pixel of the marker buffer. */
    private void put(int cellPos, byte[] c) {
        int pos = cellPos * 4;
        markerBuffer[pos] = c[0];
        markerBuffer[pos + 1] = c[1];
        markerBuffer[pos + 2] = c[2];
        markerBuffer[pos + 3] = c[3];
    }

    // ==================================================================
    // Font of the digits: 4x5 bitmap scaled to the height of the marker
    // ==================================================================

    /** Bitmap masks for digits 0..9, layout [row][column]. */
    private static final boolean[][][] DIGITS = new boolean[10][5][4];

    static {
        // Each call marks one pixel of the digit: set(digit, row, column)
        set(1, 1, 1); set(1, 4, 2); set(1, 0, 2); set(1, 1, 2); set(1, 2, 2); set(1, 3, 2);
        set(2, 1, 0); set(2, 4, 0); set(2, 0, 1); set(2, 3, 1); set(2, 4, 1);
        set(2, 0, 2); set(2, 2, 2); set(2, 4, 2); set(2, 1, 3); set(2, 4, 3);
        set(3, 0, 0); set(3, 4, 0); set(3, 0, 1); set(3, 2, 1); set(3, 4, 1);
        set(3, 0, 2); set(3, 2, 2); set(3, 4, 2); set(3, 0, 3); set(3, 1, 3);
        set(3, 2, 3); set(3, 3, 3); set(3, 4, 3);
        set(4, 2, 0); set(4, 1, 1); set(4, 2, 1); set(4, 4, 2); set(4, 0, 2);
        set(4, 2, 2); set(4, 3, 2); set(4, 2, 3);
        set(5, 0, 1); set(5, 0, 2); set(5, 0, 3); set(5, 1, 0); set(5, 2, 0);
        set(5, 2, 1); set(5, 2, 2); set(5, 3, 3); set(5, 4, 0); set(5, 4, 1); set(5, 4, 2);
        set(6, 2, 0); set(6, 3, 0); set(6, 1, 1); set(6, 2, 1); set(6, 4, 0);
        set(6, 4, 1); set(6, 0, 2); set(6, 2, 2); set(6, 4, 2); set(6, 3, 3);
        set(7, 0, 0); set(7, 0, 1); set(7, 0, 2); set(7, 1, 2); set(7, 2, 2);
        set(7, 3, 2); set(7, 4, 2);
        set(8, 1, 0); set(8, 3, 0); set(8, 0, 1); set(8, 2, 1); set(8, 4, 1);
        set(8, 0, 2); set(8, 2, 2); set(8, 4, 2); set(8, 1, 3); set(8, 3, 3);
        set(9, 1, 0); set(9, 4, 3); set(9, 0, 1); set(9, 2, 1); set(9, 0, 2);
        set(9, 2, 2); set(9, 1, 3); set(9, 2, 3); set(9, 3, 3);
        set(0, 1, 0); set(0, 2, 0); set(0, 3, 0); set(0, 0, 1); set(0, 4, 1);
        set(0, 0, 2); set(0, 4, 2); set(0, 1, 3); set(0, 2, 3); set(0, 3, 3);
    }

    private static void set(int digit, int row, int col) {
        DIGITS[digit][row][col] = true;
    }
}
