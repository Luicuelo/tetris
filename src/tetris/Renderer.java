package tetris;

import javafx.scene.canvas.GraphicsContext;

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
 * The pixels are written through a {@link CellPainter}, the same layer the
 * level editor paints with; this class only decides which cell goes where.
 */
final class Renderer {

    /** Cells wide of the info panel, to the right of the separator column. */
    private static final int PANEL_CELLS = 4;

    /** Cells of the square preview box at the top of the panel. */
    private static final int PREVIEW_CELLS = 4;

    /**
     * Brightness of the palette while the game is paused (0..1). It lives here
     * because it is a decision of the picture: the state only says that the game
     * is paused, and the game-over curve belongs to {@link Effects}.
     */
    private static final double PAUSE_BRIGHTNESS = 0.55;

    /** The pixel layer, shared with the level editor. */
    private final CellPainter painter;
    private final GameState   gameState;

    /** Rows of the board in cells (the separator runs as tall as the board). */
    private final int boardRows;

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

        boardRows = gameState.board().rows();

        int boardCols = gameState.board().cols();
        int totalCols = boardCols + 1 + PANEL_CELLS;   // board + gap + panel
        painter   = new CellPainter(gc, w, h, totalCols, boardRows);
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
        paintMarker(gameState.points(),    scoreX, scoreY, Palette.BLACK,     Palette.DIGIT_RED);
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
        painter.paintSeparator(boardX + boardW, boardY, boardRows);
    }

    /**
     * Paints the active piece over the board at its actual position. A cell that
     * falls above the top row is clipped by {@link Board#inBounds}, so the piece
     * is drawn exactly while it is inside the playfield.
     */
    private void paintActivePiece() {
        Piece.ActivePiece p = gameState.currentPiece();
        if (p == null) return;
        p.forEachCell((row, col, value, edgeBits) -> {
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

        piece.forEachCell((r, col, value, edgeBits) -> {
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
}
