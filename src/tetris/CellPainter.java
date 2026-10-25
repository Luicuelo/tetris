package tetris;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import java.nio.ByteBuffer;

/**
 * Pixel layer shared by the two screens of the project: the game
 * ({@link Renderer}) and the level editor ({@link EditorRenderer}).
 * <p>
 * It owns the {@link WritableImage}, the {@link PixelWriter}, the pixels per
 * logical cell and the origin of the grid, and offers the primitives every
 * composition is built from: one cell with its 3D edges, one flat rectangle,
 * one digit marker and the playfield itself. Which cells go where is the
 * business of each composition; here a coordinate becomes pixels.
 * <p>
 * The image is the size of the canvas and is initialised to black once; the
 * letterbox is never repainted because it is never touched after that. Both
 * buffers are reused, so a frame allocates nothing.
 */
final class CellPainter {

    /** Opaque black: the letterbox around the grid and the preview background. */
    static final byte[] BLACK = {0, 0, 0, (byte) 255};

    /**
     * Smallest pixels per logical cell the composition supports: below it the
     * digits of the markers no longer fit inside their frame. The smallest
     * canvas of the game is therefore 105 × 140 pixels (15 × 20 cells).
     */
    static final int MIN_PIXEL_SIZE = 7;

    private final GraphicsContext gc;
    private final int width;
    private final int height;
    /** Pixels per logical cell, derived from the canvas size. */
    private final int pixelSize;
    /** Top-left corner of the grid; the letterbox is what lies outside it. */
    private final int originX, originY;

    private final WritableImage screenImage;
    private final PixelWriter   pixelWriter;
    private final WritablePixelFormat<ByteBuffer> pixelFormat =
            PixelFormat.getByteBgraInstance();

    /** Reusable BGRA buffer for one cell; it grows to the largest cell painted. */
    private byte[] cellBuffer = new byte[0];

    /** Reusable BGRA row for flat fills; one canvas wide. */
    private final byte[] fillRow;

    private final Palette palette = new Palette();

    /** Metrics of the last marker painted: a different box rebuilds them. */
    private DigitFont digitFont;
    private int digitBoxW, digitBoxH;

    /**
     * Creates the pixel layer of one canvas.
     *
     * @param gc           graphics context of the canvas (must not be null)
     * @param canvasWidth  canvas width in pixels (at least
     *                     {@code totalCols × MIN_PIXEL_SIZE})
     * @param canvasHeight canvas height in pixels (at least
     *                     {@code totalRows × MIN_PIXEL_SIZE})
     * @param totalCols    columns of the grid in logical cells, letterbox
     *                     included (must be positive)
     * @param totalRows    rows of the grid in logical cells (must be positive)
     */
    CellPainter(GraphicsContext gc, int canvasWidth, int canvasHeight,
                int totalCols, int totalRows) {
        if (gc == null) {
            throw new IllegalArgumentException("GraphicsContext cannot be null");
        }
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }
        if (totalCols <= 0 || totalRows <= 0) {
            throw new IllegalArgumentException("Grid size must be positive");
        }
        if (canvasWidth < totalCols * MIN_PIXEL_SIZE
                || canvasHeight < totalRows * MIN_PIXEL_SIZE) {
            throw new IllegalArgumentException("Canvas must be at least "
                    + (totalCols * MIN_PIXEL_SIZE) + "×" + (totalRows * MIN_PIXEL_SIZE)
                    + " pixels (" + MIN_PIXEL_SIZE + " px per cell)");
        }

        this.gc     = gc;
        this.width  = canvasWidth;
        this.height = canvasHeight;

        pixelSize = Math.min(canvasWidth / totalCols, canvasHeight / totalRows);
        originX   = (canvasWidth  - totalCols * pixelSize) / 2;
        originY   = (canvasHeight - totalRows * pixelSize) / 2;

        screenImage = new WritableImage(canvasWidth, canvasHeight);
        pixelWriter = screenImage.getPixelWriter();
        fillRow     = new byte[canvasWidth * 4];
        fillRect(0, 0, canvasWidth, canvasHeight, BLACK);
    }

    // ==================================================================
    // Geometry and shared state
    // ==================================================================

    /** Pixels per logical cell. */
    int pixelSize() {
        return pixelSize;
    }

    /** Left edge of the grid in canvas pixels. */
    int originX() {
        return originX;
    }

    /** Top edge of the grid in canvas pixels. */
    int originY() {
        return originY;
    }

    /** The palette both screens paint with; the game darkens it while paused. */
    Palette palette() {
        return palette;
    }

    /** Dumps the composed image to the canvas. */
    void present() {
        gc.drawImage(screenImage, 0, 0);
    }

    // ==================================================================
    // Playfield
    // ==================================================================

    /**
     * Paints a whole playfield at ({@code x}, {@code y}): the background mosaic
     * and, over it, the cells the picture must show.
     * <p>
     * The mosaic alternates by the parity of {@code row + column}, so a cell and
     * its neighbours never share the background, and the cells are read through
     * {@link Board#visibleCellValue}, so a cell the hidden mask covers leaves
     * the background in sight.
     *
     * @param board playfield to paint
     * @param x     left edge of the board in canvas pixels
     * @param y     top edge of the board in canvas pixels
     */
    void paintBoard(Board board, int x, int y) {
        for (int row = 0; row < board.rows(); row++) {
            for (int column = 0; column < board.cols(); column++) {
                int value = board.visibleCellValue(row, column);
                int index = (value != 0)
                        ? Palette.indexOfValue(value)
                        : (((row + column) & 1) != 0 ? Palette.BG_B : Palette.BG_A);
                int edges = (value != 0) ? board.edgeAt(row, column) : 0;
                paintCell(x + column * pixelSize, y + row * pixelSize, index, edges);
            }
        }
    }

    /**
     * Paints the one-cell-wide separator column between board and panel.
     *
     * @param x     left edge of the column in canvas pixels
     * @param y     top edge of the column in canvas pixels
     * @param cells rows to paint
     */
    void paintSeparator(int x, int y, int cells) {
        for (int row = 0; row < cells; row++) {
            paintCell(x, y + row * pixelSize, Palette.GAP, 0);
        }
    }

    // ==================================================================
    // Cells
    // ==================================================================

    /** Paints one board cell: a {@code pixelSize × pixelSize} square. */
    void paintCell(int x, int y, int paletteIndex, int edgeBits) {
        paintCell(x, y, paletteIndex, edgeBits, pixelSize, pixelSize);
    }

    /**
     * Writes one rectangular cell with 3D edges into the image.
     * <p>
     * The cell is composed in the shared {@code cellBuffer} (base colour, then
     * the lit and shaded edge lines) and flushed with a single {@code setPixels},
     * so a frame allocates nothing. Board cells and the cells of a digit share
     * this method; only their size changes.
     *
     * @param x            left edge in canvas pixels
     * @param y            top edge in canvas pixels
     * @param paletteIndex palette index of the base colour
     * @param edgeBits     sides that need an edge line ({@link Edges})
     * @param cellW        width in pixels
     * @param cellH        height in pixels
     */
    void paintCell(int x, int y, int paletteIndex, int edgeBits, int cellW, int cellH) {
        byte[] base   = palette.pixel(paletteIndex, Palette.BASE);
        byte[] bright = palette.pixel(paletteIndex, Palette.BRIGHT);
        byte[] dark   = palette.pixel(paletteIndex, Palette.DARK);

        reserveCellBuffer(cellW, cellH);
        for (int i = 0; i < cellW * cellH; i++) {
            int p = i * 4;
            cellBuffer[p]   = base[0]; cellBuffer[p+1] = base[1];
            cellBuffer[p+2] = base[2]; cellBuffer[p+3] = base[3];
        }
        int lastX = cellW - 1;
        int lastY = cellH - 1;
        if (Edges.hasLeft(edgeBits))   paintLine(0,     0, 0,     lastY, cellW, bright);
        if (Edges.hasTop(edgeBits))    paintLine(0,     0, lastX, 0,     cellW, bright);
        if (Edges.hasBottom(edgeBits)) paintLine(0, lastY, lastX, lastY, cellW, dark);
        if (Edges.hasRight(edgeBits))  paintLine(lastX, 0, lastX, lastY, cellW, dark);

        pixelWriter.setPixels(x, y, cellW, cellH,
                pixelFormat, cellBuffer, 0, cellW * 4);
    }

    /** Makes sure the shared cell buffer holds {@code w × h} BGRA pixels. */
    private void reserveCellBuffer(int cellW, int cellH) {
        int needed = cellW * cellH * 4;
        if (cellBuffer.length < needed) {
            cellBuffer = new byte[needed];
        }
    }

    // ==================================================================
    // Digit marker
    // ==================================================================

    /**
     * Paints one 4-digit marker at ({@code x}, {@code y}): a flat background with
     * its raised frame, then the digits on top.
     * <p>
     * The background and the frame are one single cell
     * ({@code paintCell(x, y, backgroundColour, Edges.ALL, boxW, boxH)}), so the
     * whole frame costs one {@code setPixels} instead of four line fills. The
     * digits are laid out from right to left, so the least significant digit
     * always sits on the right edge and small numbers get leading zeros, like an
     * arcade display.
     *
     * @param value            number to display; only the last four digits fit
     * @param x                left edge of the marker in canvas pixels
     * @param y                top edge of the marker in canvas pixels
     * @param boxW             width of the marker in pixels
     * @param boxH             height of the marker in pixels
     * @param backgroundColour palette index of the marker background
     * @param digitColour      palette index of the digits
     */
    void paintMarker(int value, int x, int y, int boxW, int boxH,
                     int backgroundColour, int digitColour) {
        DigitFont font = fontFor(boxW, boxH);
        paintCell(x, y, backgroundColour, Edges.ALL, boxW, boxH);

        int remaining = value;
        for (int position = 0; position < DigitFont.MARKER_DIGITS; position++) {
            int digitValue = remaining % 10;
            remaining     /= 10;
            // Right to left: the slot of this digit hangs off the right edge.
            int digitLeft = x + boxW - (position + 1) * font.digitSlotW + 1;
            paintDigit(font, digitValue, digitLeft, y + DigitFont.TOP_MARGIN, digitColour);
        }
    }

    /**
     * Paints one digit of the bitmap font.
     * <p>
     * A digit is a small mosaic of cells: every "on" pixel of the 4×5 matrix
     * becomes a cell of {@code digitW × digitH} pixels, painted with the same
     * {@link #paintCell} as the board. The edge flags of its cells are
     * pre-computed by {@link DigitFont}, so the 3D bevel follows the outline of
     * the digit without inspecting the neighbouring pixels here.
     *
     * @param font         metrics of the marker being painted
     * @param value        digit to paint (0–9)
     * @param left         left edge of the digit in canvas pixels
     * @param top          top edge of the digit in canvas pixels
     * @param paletteIndex palette index of the digit colour
     */
    private void paintDigit(DigitFont font, int value, int left, int top, int paletteIndex) {
        int cellW = font.digitW;
        int cellH = font.digitH;

        for (int row = 0; row < DigitFont.DIGIT_ROWS; row++) {
            for (int col = 0; col < DigitFont.DIGIT_COLUMNS; col++) {
                if (!DigitFont.digitPixel(value, row, col)) continue;
                paintCell(left + col * cellW, top + row * cellH,
                          paletteIndex, DigitFont.edgesOf(value, row, col), cellW, cellH);
            }
        }
    }

    /** Metrics for a marker of {@code boxW × boxH} pixels, built once per size. */
    private DigitFont fontFor(int boxW, int boxH) {
        if (digitFont == null || digitBoxW != boxW || digitBoxH != boxH) {
            digitFont = new DigitFont(boxW, boxH);
            digitBoxW = boxW;
            digitBoxH = boxH;
        }
        return digitFont;
    }

    // ==================================================================
    // Pixel writer helpers
    // ==================================================================

    /**
     * Fills a solid rectangle of the image with one colour.
     * <p>
     * The colour is copied once into the shared {@code fillRow} buffer and then
     * written a row at a time, so every flat area (the letterbox, the panel fill
     * and the preview background) follows the same path. The rectangle is clipped
     * to the canvas, so a fill that runs past an edge loses the protruding pixels
     * instead of throwing.
     *
     * @param x      left edge in canvas pixels
     * @param y      top edge in canvas pixels
     * @param w      width in pixels
     * @param h      height in pixels
     * @param colour BGRA block to fill with
     */
    void fillRect(int x, int y, int w, int h, byte[] colour) {
        int left    = Math.max(0, x);
        int top     = Math.max(0, y);
        int columns = Math.min(width,  x + w) - left;
        int bottom  = Math.min(height, y + h);
        if (columns <= 0) return;

        for (int i = 0; i < columns; i++) {
            int p = i * 4;
            fillRow[p] = colour[0]; fillRow[p+1] = colour[1];
            fillRow[p+2] = colour[2]; fillRow[p+3] = colour[3];
        }
        for (int row = top; row < bottom; row++)
            pixelWriter.setPixels(left, row, columns, 1, pixelFormat, fillRow, 0, columns * 4);
    }

    /**
     * Draws a horizontal or vertical run of one colour inside the current cell.
     * Coordinates are cell-local pixels and {@code cellW} is the row width of the
     * cell being composed, not the canvas width.
     */
    private void paintLine(int x1, int y1, int x2, int y2, int cellW, byte[] colour) {
        if (x1 == x2) {
            for (int y = y1; y <= y2; y++) setPixel(y * cellW + x1, colour);
        } else {
            for (int x = x1; x <= x2; x++) setPixel(y1 * cellW + x, colour);
        }
    }

    /** Writes one colour into the cell buffer; {@code pos} is in cell-local pixels. */
    private void setPixel(int pos, byte[] colour) {
        pos *= 4;
        cellBuffer[pos]   = colour[0]; cellBuffer[pos+1] = colour[1];
        cellBuffer[pos+2] = colour[2]; cellBuffer[pos+3] = colour[3];
    }
}
