package principal;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.*;
import javafx.scene.paint.Color;
import java.nio.ByteBuffer;

/**
 * Renderer of the Tetris. It receives the state of the game ({@link Game})
 * and composes the WHOLE screen: board 10x20 + separator + info panel.
 * <p>
 * The game is built by the application and handed over in the constructor:
 * the renderer reads it but never sends it commands, so the keyboard goes
 * straight from {@code Tetris} to {@code Game}. A method here that only
 * forwarded a key to the game would be a reference that is missing.
 * <p>
 * The board draws itself cell per cell (pixelSize x pixelSize) in a
 * WritableImage via PixelWriter (atomic painting). The info panel (preview
 * box, score and lines markers) lives in {@link InfoPanel}, which paints its
 * own buffers; here it is only asked to draw itself on the same image.
 * <p>
 * The number of logical cells is fixed by the model (board 10x20 plus the
 * info panel); only the pixels per cell are derived from the canvas size, so
 * a bigger canvas paints exactly the same layout bigger. Whatever the canvas
 * does not cover is letterboxed in black and the grid is centered.
 */
class Renderer {

    // ------------------------------------------------------------------
    // Layout constants (all of them in logical cells, never in pixels)
    // ------------------------------------------------------------------
    /** Cells wide of the info panel, to the right of the separator column. */
    private static final int PANEL_CELLS = 4;
    /**
     * Rows the active piece is painted above its collision position: the rules
     * place the piece at posY and only the picture is shifted one row up.
     * Changing this value moves the drawing, not the game.
     */
    private static final int DRAW_OFFSET_Y = -1;

    private final GraphicsContext gc;
    private final int width;
    private final int height;
    /** Real pixels per logical cell, derived from the canvas size. */
    private final int pixelSize;
    /** Columns of the logical grid: board + separator + info panel. */
    private final int cellWidth;
    /** Rows of the logical grid: exactly the rows of the board. */
    private final int cellHeight;
    /** Margins that center the logical grid inside the canvas. */
    private final int offsetX;
    private final int offsetY;
    /** Board size as declared by the model (Board), never duplicated here. */
    private final int boardCols;
    private final int boardRows;

    /** Atomic image to dump to the canvas. */
    private final WritableImage screenImage;
    private final PixelWriter pixelWriter;
    private final WritablePixelFormat<ByteBuffer> pixelFormat =
            PixelFormat.getByteBgraInstance();

    /** Palette of colors. */
    private final Palette palette = new Palette();
    /** The game (holds all the logic). */
    private final Game game;
    /** Info panel: preview box and the two markers. */
    private final InfoPanel panel;

    /** Logical matrix of representation (palette indices). */
    private final int[][] cellData;
    /** 3D edge of each cell of the representation (1 left,2 top,4 bottom,8 right). */
    private final int[][] cellEdges;

    /** Reusable block of pixels BGRA (a board cell). */
    private final byte[] blockBuffer;

    /**
     * Constructs the renderer.
     *
     * @param gc         graphical context of the canvas (must not be null)
     * @param w          width in real pixels (must be positive)
     * @param h          height in real pixels (must be positive)
     * @param game       the game the screen is read from (must not be null)
     */
    public Renderer(GraphicsContext gc, int w, int h, Game game) {
        if (gc == null) {
            throw new IllegalArgumentException("GraphicsContext cannot be null");
        }
        if (game == null) {
            throw new IllegalArgumentException("Game cannot be null");
        }
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }

        this.gc = gc;
        this.width = w;
        this.height = h;
        this.game = game;

        boardCols = game.boardCols();
        boardRows = game.boardRows();

        // The grid is always board + separator + panel, whatever the canvas
        // size; the canvas only decides how many pixels each cell gets.
        int infoX0 = boardCols + 1;
        cellWidth  = infoX0 + PANEL_CELLS;
        cellHeight = boardRows;
        pixelSize  = Math.max(1, Math.min(width / cellWidth, height / cellHeight));
        offsetX    = Math.max(0, (width  - cellWidth  * pixelSize) / 2);
        offsetY    = Math.max(0, (height - cellHeight * pixelSize) / 2);

        // Atomic image: each area paints pixel per pixel on it
        screenImage = new WritableImage(width, height);
        pixelWriter = screenImage.getPixelWriter();

        cellData = new int[cellHeight][cellWidth];
        cellEdges = new int[cellHeight][cellWidth];
        blockBuffer = new byte[pixelSize * pixelSize * 4];

        panel = new InfoPanel(palette, pixelWriter, pixelFormat,
                new InfoPanel.Layout(cellWidth, cellHeight, pixelSize,
                        offsetX, offsetY, width, height),
                infoX0);

        clearScreen();
    }

    // ==================================================================
    // Public API (order and loop)
    // ==================================================================

    /**
     * Composes the screen from the current state of the game and dumps it on the
     * canvas.
     * <p>
     * It does not advance the game and it does not decide when: the shell owns
     * the pulse timestamps, so it owns the two rates — 60 ticks of engine per
     * second and one picture every two ticks. Here the state is only read.
     */
    public void paint() {
        updateImageFromPalette();
        present();
    }

    // ==================================================================
    // Composition of the representation
    // ==================================================================

    /**
     * Takes the logical state (board matrix of the engine, active piece,
     * next piece), puts it in 'cellData'/'cellEdges' and converts it to
     * pixels according to the palette; afterwards asks the info panel to
     * draw itself in the same image.
     */
    private void updateImageFromPalette() {
        // Current dim factor of the palette (pause / end of game)
        palette.setDim(game.dimFactor());

        composeScreen();
        bufferToScreen();
        panel.draw(game.points(), game.linesLeft());
    }

    /** Fills 'cellData' and 'cellEdges' from the game state. */
    private void composeScreen() {
        // 1. Background: mosaic of the board, separator and info panel
        for (int r = 0; r < cellHeight; r++) {
            for (int c = 0; c < cellWidth; c++) {
                int idx;
                if (c < boardCols) {
                    // mosaic: alternating colors
                    idx = (((r + c) & 1) != 0) ? Palette.BG_B : Palette.BG_A;
                } else if (c == boardCols) {
                    idx = Palette.GAP;
                } else {
                    idx = Palette.INFO_BG;
                }
                cellData[r][c] = idx;
                cellEdges[r][c] = 0;
            }
        }

        // 2. Fixed content of the board (pieces and obstacles)
        int rows = Math.min(boardRows, cellHeight);
        int cols = Math.min(boardCols, cellWidth);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = game.cellValue(r, c);
                if (v != 0) {
                    cellData[r][c] = colorIndex(v);
                    cellEdges[r][c] = game.edgeValue(r, c);
                }
            }
        }

        // 3. Active piece (painted DRAW_OFFSET_Y rows from its collision row)
        Piece.Active p = game.currentPiece();
        if (p != null) {
            p.forEachCell((row, col, value, edgeBits) -> {
                int r = row + p.getPosY() + DRAW_OFFSET_Y;
                int c = col + p.getPosX();
                if (r >= 0 && r < cellHeight && c >= 0 && c < cellWidth) {
                    cellData[r][c]  = colorIndex(value);
                    cellEdges[r][c] = edgeBits;
                }
                return true;
            });
        }

        // 4. Next piece in the preview box (centered on its bounding box)
        if (panel.hasBox()) {
            int boxCells = InfoPanel.BOX_CELLS;
            int boxX = panel.boxXCells();
            for (int r = 0; r < boxCells; r++) {
                for (int c = 0; c < boxCells; c++) {
                    cellData[r][boxX + c] = 0;  // black background
                }
            }
            Piece.Active q = game.nextPiece();
            if (q != null) {
                // Center the piece within the box using its bounding box
                int dx = Math.round((boxCells - q.previewCols()) / 2.0f);
                int dy = Math.round((boxCells - q.previewRows()) / 2.0f);

                q.forEachCell((row, col, value, edgeBits) -> {
                    int r = row + dy;
                    int c = boxX + col + dx;
                    if (r >= 0 && r < cellHeight && c >= 0 && c < cellWidth) {
                        cellData[r][c]  = colorIndex(value);
                        cellEdges[r][c] = edgeBits;
                    }
                    return true;
                });
            }
        }
    }

    /** Palette index of a matrix value (1..14 -> BASE_COLORS + v - 1). */
    private int colorIndex(int v) {
        return Palette.indexOfValue(v);
    }

    // ==================================================================
    // Painting of the board (block per block, as in the other projects)
    // ==================================================================

    /** Passes cellData + cellEdges to the pixels of screenImage. */
    private void bufferToScreen() {
        for (int r = 0; r < cellHeight; r++) {
            int screenY = offsetY + r * pixelSize;
            // A canvas smaller than one pixel per cell simply gets clipped
            if (screenY + pixelSize > height) {
                break;
            }
            for (int c = 0; c < cellWidth; c++) {
                int screenX = offsetX + c * pixelSize;
                if (screenX + pixelSize > width) {
                    break;
                }
                paintCell(screenX, screenY, cellData[r][c], cellEdges[r][c]);
            }
        }
    }

    /**
     * Fills the block buffer with the cell color and its 3D edges
     * (left/top = bright, bottom/right = dark).
     */
    private void paintCell(int screenX, int screenY, int idx, int edgeBits) {
        byte[] base = palette.pixel(idx, Palette.BASE);
        byte[] bright = palette.pixel(idx, Palette.BRIGHT);
        byte[] dark = palette.pixel(idx, Palette.DARK);

        // fill
        for (int i = 0; i < pixelSize * pixelSize; i++) {
            int pos = i * 4;
            blockBuffer[pos] = base[0];
            blockBuffer[pos + 1] = base[1];
            blockBuffer[pos + 2] = base[2];
            blockBuffer[pos + 3] = base[3];
        }

        // 3D edges (painted in this order: the last one wins)
        if (Edges.has(edgeBits, Edges.LEFT))   paintLine(0, 0, 0, pixelSize - 1, bright);
        if (Edges.has(edgeBits, Edges.TOP))    paintLine(0, 0, pixelSize - 1, 0, bright);
        if (Edges.has(edgeBits, Edges.BOTTOM)) paintLine(0, pixelSize - 1, pixelSize - 1, pixelSize - 1, dark);
        if (Edges.has(edgeBits, Edges.RIGHT))  paintLine(pixelSize - 1, 0, pixelSize - 1, pixelSize - 1, dark);

        pixelWriter.setPixels(screenX, screenY, pixelSize, pixelSize,
                pixelFormat, blockBuffer, 0, pixelSize * 4);
    }

    /** Draws a horizontal or vertical segment inside the block buffer for 3D edges. */
    private void paintLine(int x1, int y1, int x2, int y2, byte[] c) {
        if (x1 == x2) {
            for (int y = y1; y <= y2; y++) {
                setPixel(y * pixelSize + x1, c);
            }
        } else {
            for (int x = x1; x <= x2; x++) {
                setPixel(y1 * pixelSize + x, c);
            }
        }
    }

    /** Puts a BGRA color into a pixel of the block buffer. */
    private void setPixel(int cellPos, byte[] c) {
        int pos = cellPos * 4;
        blockBuffer[pos] = c[0];
        blockBuffer[pos + 1] = c[1];
        blockBuffer[pos + 2] = c[2];
        blockBuffer[pos + 3] = c[3];
    }

    /** Clears the representation (used in the constructor). */
    private void clearScreen() {
        for (int r = 0; r < cellHeight; r++) {
            for (int c = 0; c < cellWidth; c++) {
                cellData[r][c] = 0;
                cellEdges[r][c] = 0;
            }
        }
        updateImageFromPalette();
        present();
    }

    /**
     * Dumps the composed image on the canvas, painting black only the bands the
     * grid does not cover: a canvas that fits the grid exactly pays nothing, and
     * a bigger one gets its letterbox margins.
     */
    private void present() {
        int gridWidth  = cellWidth  * pixelSize;
        int gridHeight = cellHeight * pixelSize;
        int right      = width  - offsetX - gridWidth;
        int bottom     = height - offsetY - gridHeight;

        gc.setFill(Color.BLACK);
        if (offsetX > 0) gc.fillRect(0, offsetY, offsetX, gridHeight);
        if (right   > 0) gc.fillRect(offsetX + gridWidth, offsetY, right, gridHeight);
        if (offsetY > 0) gc.fillRect(0, 0, width, offsetY);
        if (bottom  > 0) gc.fillRect(0, offsetY + gridHeight, width, bottom);
        gc.drawImage(screenImage, 0, 0);
    }
}
