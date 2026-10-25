package tetris;

import javafx.scene.canvas.GraphicsContext;

/**
 * Composes the canvas of the level editor, {@link LevelEditor}.
 * <p>
 * The grid is the one of the game's board — ten columns by twenty rows of the
 * same size they have in play — so the level is seen with the proportions it
 * will have. Nothing else shares the canvas: the buttons, the colour matrix and
 * the number of the screen are ordinary JavaFX nodes in the tool column, because
 * a level editor gains nothing from painting its own widgets.
 * <p>
 * It also answers the only question the mouse asks: which cell lies under a
 * point.
 */
class EditorRenderer {

    /** The pixel layer, shared with the game screen. */
    private final CellPainter painter;
    private final int boardCols;
    private final int boardRows;

    /** Pixel rectangle of the board area. */
    private final int boardX, boardY;

    /**
     * Constructs the renderer of the editor.
     *
     * @param gc       graphics context of the canvas (must not be null)
     * @param w        canvas width in pixels (must be positive)
     * @param h        canvas height in pixels (must be positive)
     * @param geometry board that gives the dimensions of the grid
     */
    EditorRenderer(GraphicsContext gc, int w, int h, Board geometry) {
        if (geometry == null) throw new IllegalArgumentException("geometry cannot be null");

        boardCols = geometry.cols();
        boardRows = geometry.rows();

        painter = new CellPainter(gc, w, h, boardCols, boardRows);
        boardX  = painter.originX();
        boardY  = painter.originY();
    }

    /** One cell of the playfield. */
    record Cell(int row, int column) {
    }

    // ==================================================================
    // Composition
    // ==================================================================

    /**
     * Paints the obstacles of {@code board}.
     *
     * @param board playfield being edited
     */
    void paint(Board board) {
        painter.paintBoard(board, boardX, boardY);
        painter.present();
    }

    /**
     * Cell under a canvas point.
     * <p>
     * The floor keeps the points to the left of the board out of column 0, so a
     * click on the margin never paints the first column by accident.
     *
     * @param x horizontal position in canvas pixels
     * @param y vertical position in canvas pixels
     * @return the cell, or {@code null} when the point lies outside the board
     */
    Cell cellAt(double x, double y) {
        int pixelSize = painter.pixelSize();
        int column = (int) Math.floor((x - boardX) / pixelSize);
        int row    = (int) Math.floor((y - boardY) / pixelSize);
        if (column < 0 || column >= boardCols || row < 0 || row >= boardRows) {
            return null;
        }
        return new Cell(row, column);
    }
}
