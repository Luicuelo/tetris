package tetris;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import java.nio.ByteBuffer;

/**
 * Renderer of the Tetris: turns the {@link GameState} into pixels on the canvas.
 * <p>
 * Layout (all measurements in logical cells, converted to pixels once):
 * <pre>
 *   canvas (width × height, any size)
 *   └── grid (centred, black letterbox around it)
 *       ├── board  : boardCols × boardRows cells  (mosaic + pieces)
 *       ├── gap    : 1 cell wide separator
 *       └── panel  : PANEL_CELLS × boardRows cells
 *           ├── preview rect  (PREVIEW_CELLS × PREVIEW_CELLS)
 *           ├── score rect    (centred in the first third below the preview)
 *           └── lines rect    (centred in the second third below the preview)
 * </pre>
 * The canvas size only decides the pixels per logical cell. Every
 * sub-rectangle is a multiple of that size, so there are no fractional cells
 * and no gap-filling arithmetic.
 * <p>
 * Which cell goes where is decided by this class; a coordinate becomes pixels
 * in its {@link CellPainter}, with the colours of its {@link Palette}. The level
 * editor reuses the same pixel layer, so both programs paint identical blocks.
 */
final class Renderer {

    /** Cells wide of the info panel, to the right of the separator column. */
    private static final int PANEL_CELLS = 4;

    /** Cells of the square preview box at the top of the panel. */
    private static final int PREVIEW_CELLS = 4;

    /** Brightness of the palette while the game is paused (0..1). */
    private static final double PAUSE_BRIGHTNESS = 0.55;

    /** The pixel layer, shared with the level editor. */
    private final CellPainter painter;
    private final GameState   gameState;

    /** Pixel rectangle of the board area. */
    private final int boardX, boardY, boardW, boardH;

    /** Pixel rectangle of the info panel. */
    private final int panelX, panelY, panelW, panelH;

    /** Pixel rectangle of the preview box. */
    private final int previewX, previewY, previewSize;

    /** Pixel rectangle of the score marker. */
    private final int scoreX, scoreY, markerW, markerH;

    /** Pixel rectangle of the lines marker. */
    private final int linesX, linesY;

    /**
     * Constructs the renderer.
     *
     * @param gc        graphics context of the canvas (must not be null)
     * @param w         canvas width in pixels (must be positive)
     * @param h         canvas height in pixels (must be positive)
     * @param gameState the observable game state: board, pieces, score and the
     *                  flags that say whether the game is paused or over
     */
    Renderer(GraphicsContext gc, int w, int h, GameState gameState) {
        if (gameState == null) throw new IllegalArgumentException("gameState cannot be null");

        this.gameState = gameState;

        int boardCols = gameState.board().cols();
        int boardRows = gameState.board().rows();
        painter   = new CellPainter(gc, w, h, gridColumns(boardCols), boardRows);
        int pixelSize = painter.pixelSize();

        // Board sub-rectangle
        boardX = painter.originX();
        boardY = painter.originY();
        boardW = boardCols * pixelSize;
        boardH = boardRows * pixelSize;

        // Panel sub-rectangle (right of gap)
        panelX = boardX + (boardCols + 1) * pixelSize;
        panelY = boardY;
        panelW = PANEL_CELLS * pixelSize;
        panelH = boardRows   * pixelSize;

        // Preview: PREVIEW_CELLS × PREVIEW_CELLS, centred horizontally in the panel
        previewSize = PREVIEW_CELLS * pixelSize;
        int boxOff  = (panelW - previewSize) / 2;
        previewX    = panelX + boxOff;
        previewY    = panelY;

        // Score and lines markers: same width as preview, distributed below it
        markerH = previewSize / 2;
        markerW = previewSize;
        int below = panelH - previewSize;
        int third = below / 3;
        scoreX = previewX;
        scoreY = panelY + previewSize + third     - markerH / 2;
        linesX = previewX;
        linesY = panelY + previewSize + 2 * third - markerH / 2;
    }

    // ==================================================================
    // Canvas size
    // ==================================================================

    /**
     * Columns of the whole grid: the playfield, the separator and the panel. The
     * one place the horizontal layout is written down.
     *
     * @param boardCols columns of the playfield
     */
    private static int gridColumns(int boardCols) {
        return boardCols + 1 + PANEL_CELLS;
    }

    /**
     * Smallest canvas width that paints the whole picture: a cell never drops
     * below {@link CellPainter#MIN_PIXEL_SIZE} pixels, which is what keeps the
     * digits inside the markers.
     *
     * @param boardCols columns of the playfield
     */
    static int minimumWidth(int boardCols) {
        return gridColumns(boardCols) * CellPainter.MIN_PIXEL_SIZE;
    }

    /**
     * The same minimum, in height, for a playfield of {@code boardRows} rows.
     *
     * @param boardRows rows of the playfield
     */
    static int minimumHeight(int boardRows) {
        return boardRows * CellPainter.MIN_PIXEL_SIZE;
    }

    // ==================================================================
    // Composition
    // ==================================================================

    /**
     * Paints the current game state into the image and dumps it to the canvas.
     * Called at ~15 fps by the shell; does not advance the game.
     *
     * @param gameOverBrightness game-over darkening factor read by the shell:
     *                           1.0 at full brightness, approaching 0 as the
     *                           screen goes black; ignored unless the game is over
     */
    void paint(double gameOverBrightness) {
        painter.palette().setBrightness(brightnessFactor(gameOverBrightness));
        paintBoard();
        paintGap();
        paintActivePiece();
        paintPanelBackground();
        paintPreview(gameState.nextPiece());
        paintMarker(gameState.score(),     scoreX, scoreY, Palette.BLACK,     Palette.DIGIT_RED);
        paintMarker(gameState.linesLeft(), linesX, linesY, Palette.MARKER_BG, Palette.DIGIT_BLUE);
        painter.present();
    }

    // ==================================================================
    // Board
    // ==================================================================

    /** Paints every cell of the board: background mosaic then locked pieces. */
    private void paintBoard() {
        painter.paintBoard(gameState.board(), boardX, boardY);
    }

    /** Paints the one-cell-wide separator column between board and panel. */
    private void paintGap() {
        painter.paintSeparator(boardX + boardW, boardY, gameState.board().rows());
    }

    /**
     * Paints the active piece over the board at its actual position. A cell that
     * falls above row 0 is clipped by {@link Board#inBounds}, so the piece is
     * drawn exactly while it is inside the playfield.
     */
    private void paintActivePiece() {
        Piece.ActivePiece p = gameState.activePiece();
        if (p == null) return;
        p.forEachFilledCell((row, col, value, edgeBits) -> {
            int r = row + p.getPosY();
            int c = col + p.getPosX();
            if (gameState.board().inBounds(r, c))
                painter.paintCell(boardX + c * painter.pixelSize(),
                                  boardY + r * painter.pixelSize(),
                                  Palette.indexOfValue(value), edgeBits);
            return true;
        });
    }

    // ==================================================================
    // Info panel
    // ==================================================================

    /** Fills the full panel rectangle with the INFO_BG colour. */
    private void paintPanelBackground() {
        painter.fillRect(panelX, panelY, panelW, panelH,
                         painter.palette().pixel(Palette.INFO_BG, Palette.BASE));
    }

    /**
     * Paints the next-piece preview box: black background, then the piece
     * centred on its bounding box.
     */
    private void paintPreview(Piece.ActivePiece piece) {
        painter.fillRect(previewX, previewY, previewSize, previewSize, CellPainter.BLACK);

        if (piece == null) return;
        int boxCells = PREVIEW_CELLS;
        int dx = Math.round((boxCells - piece.previewCols()) / 2.0f);
        int dy = Math.round((boxCells - piece.previewRows()) / 2.0f);

        piece.forEachFilledCell((r, col, value, edgeBits) -> {
            int pr = r   + dy;
            int pc = col + dx;
            if (pr >= 0 && pr < boxCells && pc >= 0 && pc < boxCells)
                painter.paintCell(previewX + pc * painter.pixelSize(),
                                  previewY + pr * painter.pixelSize(),
                                  Palette.indexOfValue(value), edgeBits);
            return true;
        });
    }

    /**
     * Paints one 4-digit marker at ({@code x}, {@code y}): a flat background with
     * its raised frame, then the digits on top. The layout of the digits (right
     * to left, leading zeros) lives in {@link CellPainter#paintMarker}.
     *
     * @param value            number to display; only the last four digits fit
     * @param x                left edge of the marker in canvas pixels
     * @param y                top edge of the marker in canvas pixels
     * @param backgroundColour palette index of the marker background
     * @param digitColour      palette index of the digits
     */
    private void paintMarker(int value, int x, int y, int backgroundColour, int digitColour) {
        painter.paintMarker(value, x, y, markerW, markerH, backgroundColour, digitColour);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Brightness the palette is rebuilt with: the game-over curve while the
     * screen darkens, the fixed pause factor while paused, full light otherwise.
     *
     * @param gameOverBrightness brightness of the fade, handed in by the shell
     */
    private double brightnessFactor(double gameOverBrightness) {
        if (gameState.isGameOver()) return gameOverBrightness;
        if (gameState.isPaused())   return PAUSE_BRIGHTNESS;
        return 1.0;
    }

    // ==================================================================
    // Pixel layer: the palette and the primitives of the picture
    // ==================================================================

    /**
     * Palette of colors of the game.
     * Indices:
     * 0     : black (preview box and score-marker background).
     * 1, 2  : alternating background of the board (mosaic).
     * 3     : background of the separator column.
     * 4     : background of the info panel.
     * 5..14 : the 10 colors of the pieces:
     *         index = BASE_COLORS + (v - 1), being v the value of the matrix.
     *         Index 12 (pink) is also the fill of the level transition.
     * 15    : spare color, reserved and never painted.
     * 16,17 : digits of the markers (red score, blue marker).
     * 18    : background of the markers.
     * <p>
     * Every color keeps the base RGB and two variants built from it: brighter for
     * the left/top edges and darker for the right/bottom ones. The colors are
     * delivered in a buffer BGRA for the PixelWriter.
     */
    static final class Palette {

        /** Index of the first palette color of the form colors. */
        static final int BASE_COLORS = 5;
        /**
         * Number of colors of forms. A cell value is an offset from 1, so the legal
         * values are exactly 1..N_FORMCOLORS; the palette indices they reach are
         * BASE_COLORS..BASE_COLORS+N_FORMCOLORS-1.
         */
        static final int N_FORMCOLORS = 10;

        static final int BLACK = 0;
        static final int BG_A = 1;
        static final int BG_B = 2;
        static final int GAP = 3;
        static final int INFO_BG = 4;
        static final int TRANS_FILL = 12;
        static final int DIGIT_RED = 16;
        static final int DIGIT_BLUE = 17;
        static final int MARKER_BG = 18;

        /** Variants of a color. */
        static final int BASE = 0;
        static final int BRIGHT = 1;
        static final int DARK = 2;

        /** Number of indices of the palette. */
        private static final int COLOR_ENTRIES = 19;

        /**
         * Palette index of a cell value: pieces and obstacles carry 1..N_FORMCOLORS,
         * so the colour is BASE_COLORS + value - 1. A value outside that range
         * would reach a reserved index (a digit colour, a marker background), which
         * is why the level data is validated against N_FORMCOLORS and not against a
         * palette index.
         */
        static int indexOfValue(int v) {
            return BASE_COLORS + v - 1;
        }

        /** Matrix value that paints as palette index {@code index} (inverse of the above). */
        static int valueOfIndex(int index) {
            return index - BASE_COLORS + 1;
        }

        // Colors as {r, g, b} (0..255). Index 0 reserved (black).
        private static final int[][] RGB = {
                {0, 0, 0},                      // 0 reserved (box / black bg)
                {24, 26, 44},                   // 1 background alternated A of the board
                {38, 40, 66},                   // 2 background alternated B of the board
                {70, 70, 110},                  // 3 separator column
                {128, 128, 128},                // 4 info panel (gray)
                {70, 190, 70},                  // 5  piece color 1: green
                {185, 75, 75},                  // 6  piece color 2: red
                {40, 185, 185},                 // 7  piece color 3: cyan
                {215, 200, 65},                 // 8  piece color 4: yellow
                {180, 75, 180},                 // 9  piece color 5: magenta
                {220, 135, 30},                 // 10 piece color 6: orange
                {75, 85, 200},                  // 11 piece color 7: blue
                {210, 130, 165},                // 12 piece color 8: pink (fill of transition)
                {110, 110, 110},                // 13 piece color 9: gray
                {120, 30, 60},                  // 14 piece color 10: dark crimson
                {200, 40, 40},                  // 15 spare color, never painted
                {255, 0, 0},                    // 16 digits of the score (red)
                {0, 0, 178},                    // 17 digits of the marker (dark blue)
                {220, 220, 220},                // 18 background of the marker (light gray)
        };

        /** Factor applied to build the dark variant of a color. */
        private static final double DARKFACTOR = 0.6;
        /** Amount added to build the bright variant of a color. */
        private static final double BRIGHTADD = 45.0;
        /** First and last palette index that follow the brightness factor of
         * the pause and the end of the game; the backgrounds and the digits keep their
         * own brightness. */
        private static final int BRIGHTNESS_FROM = BASE_COLORS;
        private static final int BRIGHTNESS_TO = BASE_COLORS + N_FORMCOLORS;   // last piece color

        /** LUT of BGRA blocks that is recalculated when the palette changes. */
        private final byte[][][] lut = new byte[COLOR_ENTRIES][3][4];
        private boolean dirty = true;
        /** Brightness 0..1 with which the colours in play are rebuilt (1 = as declared). */
        private double brightness = 1.0;

        /** If dirty rebuilds the LUT from the current RGB and brightness. */
        private void rebuild() {
            if (!dirty) {
                return;
            }
            for (int c = 0; c < COLOR_ENTRIES; c++) {
                // only the colours of the game take the brightness factor;
                // backgrounds and digits stay intact
                double factor = (c >= BRIGHTNESS_FROM && c <= BRIGHTNESS_TO) ? brightness : 1.0;
                double r = RGB[c][0] * factor;
                double g = RGB[c][1] * factor;
                double b = RGB[c][2] * factor;
                fillVariant(lut[c][BASE], r, g, b);
                fillVariant(lut[c][BRIGHT], cap(r + BRIGHTADD), cap(g + BRIGHTADD), cap(b + BRIGHTADD));
                fillVariant(lut[c][DARK], r * DARKFACTOR, g * DARKFACTOR, b * DARKFACTOR);
            }
            dirty = false;
        }

        /** Copies the RGB calculation into a block BGRA. */
        private void fillVariant(byte[] block, double r, double g, double b) {
            block[0] = (byte) b;    // blue
            block[1] = (byte) g;    // green
            block[2] = (byte) r;    // red
            block[3] = (byte) 255;  // alpha
        }

        /** Caps a value in the range 0..255. */
        private double cap(double v) {
            return (v > 255.0) ? 255.0 : v;
        }

        /**
         * Delivers the BGRA block of a color and variant for the PixelWriter.
         *
         * @param index   palette index
         * @param variant BASE, BRIGHT or DARK
         * @return block of 4 bytes {b, g, r, a}
         */
        byte[] pixel(int index, int variant) {
            rebuild();
            return lut[index][variant];
        }

        /**
         * Sets the brightness with which the colours in play are rebuilt: 1.0 keeps
         * them as declared, 0.55 is the pause and 0.0 turns them black at the end of
         * the game. Backgrounds and digits are not affected.
         * <p>
         * It is not the BRIGHT variant of a colour: that one is a fixed amount added
         * to draw the lit edges of a cell, and it does not depend on this factor.
         */
        void setBrightness(double factor) {
            if (Math.abs(factor - brightness) > 1e-9) {
                brightness = Math.max(0.0, Math.min(1.0, factor));
                dirty = true;
            }
        }
    }

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
     * The image is the size of the canvas and is filled with black once; the
     * letterbox is never repainted. Both buffers are reused, so a frame
     * allocates nothing.
     */
    static final class CellPainter {

        /** Opaque black: the letterbox around the grid and the preview background. */
        static final byte[] BLACK = {0, 0, 0, (byte) 255};

        /**
         * Smallest pixels per logical cell the composition supports: below it the
         * digits of the markers no longer fit inside their frame. The smallest
         * canvas of the game is therefore 105 × 140 pixels (15 × 20 cells), the
         * number the shell asks for with {@link Renderer#minimumWidth(int)}.
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

        /** Reusable BGRA buffer for one row of a flat fill; one canvas wide. */
        private final byte[] fillBuffer;

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
            fillBuffer  = new byte[canvasWidth * 4];
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
         * The colour is copied once into the shared {@code fillBuffer} and then
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
                fillBuffer[p] = colour[0]; fillBuffer[p+1] = colour[1];
                fillBuffer[p+2] = colour[2]; fillBuffer[p+3] = colour[3];
            }
            for (int row = top; row < bottom; row++)
                pixelWriter.setPixels(left, row, columns, 1, pixelFormat, fillBuffer, 0, columns * 4);
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
}
