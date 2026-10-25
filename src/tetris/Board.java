package tetris;

/**
 * The playfield: single owner of the board geometry (rows x cols) and of the
 * cell state (values, 3D edge flags, per-row occupied counters, the first
 * occupied row and the hidden mask).
 * <p>
 * The game ({@link Engine}) drives the rules and asks the board to perform the
 * state changes; the {@link Renderer} only reads it.
 * <p>
 * The edge flags follow the {@link Edges} contract: every side without a
 * neighbour is marked, and the renderer turns those edges into the 3D relief.
 * A cell under the hidden mask keeps its value and its rules but is not drawn
 * while its blink lasts (a cell the difficulty adds, a completed line).
 * <p>
 * The cells above row 0 do not exist in the matrix: a piece enters the
 * playfield from there, so {@link #isFree} answers "free" for them. Writing only
 * accepts cells that {@link #inBounds} accepts.
 */
final class Board {

    /** Board width in cells (classic Tetris). */
    static final int DEFAULT_COLS = 10;
    /** Board height in cells (classic Tetris). */
    static final int DEFAULT_ROWS = 20;

    private final int rows;
    private final int cols;

    /** Cell values: 0 = empty, 1..10 = palette index of the piece colour. */
    private int[][] cells;
    /** Edge flags of every cell. */
    private int[][] edges;
    /** Hidden mask: true = the cell is on the board but the picture skips it. */
    private boolean[][] hidden;
    /** Number of occupied cells per row, used to detect completed lines. */
    private int[] occupied;
    /** First row that contains any content (the smallest occupied index). */
    private int firstOccupiedRow;

    /** Creates a classic 20x10 board, already cleared. */
    Board() {
        this(DEFAULT_ROWS, DEFAULT_COLS);
    }

    /**
     * Creates a board of the given size.
     *
     * @param rows number of rows (must be positive)
     * @param cols number of columns (must be positive)
     */
    Board(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("Board size must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        clear();
    }

    // ==================================================================
    // Geometry
    // ==================================================================

    /** Number of rows of the playfield. */
    int rows() {
        return rows;
    }

    /** Number of columns of the playfield. */
    int cols() {
        return cols;
    }

    /** First occupied row (the rows above it are empty). */
    int firstOccupiedRow() {
        return firstOccupiedRow;
    }

    /** Occupied cells in {@code row}. */
    int occupiedCount(int row) {
        return occupied[row];
    }

    /** true if {@code row} is inside the board and completely filled. */
    boolean isLineComplete(int row) {
        return row >= 0 && row < rows && occupied[row] >= cols;
    }

    /** true if (row, col) lies inside the playfield. */
    boolean inBounds(int row, int col) {
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    // ==================================================================
    // Reading (used by the Renderer and by the collision checks)
    // ==================================================================

    /** Value of a cell (1..10, or 0 if empty); the cell must be inside the board. */
    int valueAt(int row, int col) {
        return cells[row][col];
    }

    /**
     * true when a piece may occupy the cell. This is the single answer to the
     * question "is this cell free?":
     * <ul>
     *   <li>above row 0 (negative row): free, because that is where a piece
     *       enters the playfield and the matrix has no cell to collide with;</li>
     *   <li>outside the board sideways, or below it: blocked;</li>
     *   <li>inside the board: free only when the cell is empty.</li>
     * </ul>
     * Collision and locking ask only this method, so the vertical overhang of a
     * spawning piece is resolved in one place.
     */
    boolean isFree(int row, int col) {
        if (col < 0 || col >= cols || row >= rows) return false;
        return row < 0 || cells[row][col] == 0;
    }

    /** Edge flags of a cell (1=left, 2=top, 4=bottom, 8=right). */
    int edgeAt(int row, int col) {
        return edges[row][col];
    }

    /**
     * Value of a cell as the picture must draw it: the same value as
     * {@link #valueAt}, except 0 while the hidden mask covers the cell. Only
     * the renderer calls it; the rules read the board itself.
     */
    int visibleCellValue(int row, int col) {
        return hidden[row][col] ? 0 : cells[row][col];
    }

    // ==================================================================
    // Writing (state transitions requested by the engine)
    // ==================================================================

    /** Empties the whole board and resets the bookkeeping. */
    void clear() {
        cells            = new int[rows][cols];
        edges            = new int[rows][cols];
        hidden           = new boolean[rows][cols];
        occupied         = new int[rows];
        firstOccupiedRow = rows - 1;
    }

    /**
     * Writes a single cell and counts it in its row when the cell was empty: a
     * piece lock and an obstacle always fill an empty cell, and writing over an
     * occupied one only changes its value. Emptying is the job of
     * {@link #clearCell}; this method does not take 0 as a value.
     *
     * @param row      board row (0-based)
     * @param col      board column (0-based)
     * @param value    palette value to write (1..10)
     * @param edgeBits the four edges to outline ({@link Edges} bits)
     */
    void setCell(int row, int col, int value, int edgeBits) {
        if (cells[row][col] == 0) occupied[row]++;
        cells[row][col] = value;
        edges[row][col] = edgeBits;
        if (row < firstOccupiedRow) {
            firstOccupiedRow = row;
        }
    }

    /**
     * Empties a single cell and takes it out of the counter of its row. When it
     * was the last cell of the first occupied row, that mark moves down to the
     * next row that still holds content (or to the floor), so the rows above it
     * stay empty.
     */
    void clearCell(int row, int col) {
        if (cells[row][col] == 0) return;
        cells[row][col] = 0;
        edges[row][col] = 0;
        occupied[row]--;
        if (row == firstOccupiedRow && occupied[row] == 0) {
            firstOccupiedRow = firstOccupiedRowFrom(row);
        }
    }

    /**
     * Places a fully outlined cell (pre-placed obstacle or difficulty block).
     *
     * @param row   board row (0-based)
     * @param col   board column (0-based)
     * @param value palette value to write (1..10)
     */
    void place(int row, int col, int value) {
        setCell(row, col, value, Edges.ALL);
    }

    /**
     * First row at or below {@code from} that still holds content; the last row
     * when there is none, which is where {@link #clear()} leaves the board.
     *
     * @param from row to start the search at
     */
    private int firstOccupiedRowFrom(int from) {
        for (int row = from; row < rows; row++) {
            if (occupied[row] > 0) {
                return row;
            }
        }
        return rows - 1;
    }

    /**
     * Marks the edges of a completed line and of its neighbours: bottom on the
     * row above, top+bottom on the line itself, top on the row below.
     *
     * @param row row of the completed line (0-based)
     */
    void markCompletedLineEdges(int row) {
        for (int c = 0; c < cols; c++) {
            if (row > 0)        edges[row - 1][c] = Edges.setBottom(edges[row - 1][c]);
            edges[row][c] = Edges.setTop(Edges.setBottom(edges[row][c]));
            if (row < rows - 1) edges[row + 1][c] = Edges.setTop(edges[row + 1][c]);
        }
    }

    /**
     * Removes {@code row}: every row above it (down to the first occupied one)
     * shifts one step down, and the first occupied row becomes empty.
     *
     * @param row row to remove (0-based)
     */
    void removeRow(int row) {
        for (int r = row - 1; r >= firstOccupiedRow; r--) {
            cells[r + 1]     = cells[r];
            edges[r + 1]     = edges[r];
            hidden[r + 1]    = hidden[r];
            occupied[r + 1]  = occupied[r];
        }
        cells[firstOccupiedRow]     = new int[cols];
        edges[firstOccupiedRow]     = new int[cols];
        hidden[firstOccupiedRow]    = new boolean[cols];
        occupied[firstOccupiedRow]  = 0;
        firstOccupiedRow++;
    }

    /**
     * Fills the whole row with {@code value}, giving the leftmost cell a left
     * edge, the rightmost a right edge and the middle ones only top/bottom.
     * Used by the transition fill effect.
     *
     * @param row   board row to fill (0-based)
     * @param value cell value to write
     */
    void fillRow(int row, int value) {
        for (int c = 0; c < cols; c++) {
            int e = Edges.setTop(Edges.setBottom(0));
            if (c == 0)        e = Edges.setLeft(e);
            if (c == cols - 1) e = Edges.setRight(e);
            cells[row][c]  = value;
            edges[row][c]  = e;
            hidden[row][c] = false;   // what the fill paints is always visible
        }
    }

    // ==================================================================
    // Hidden mask (written by the effects, read by the renderer)
    // ==================================================================

    /** Hides a cell: it keeps its value, but the picture no longer draws it. */
    void hide(int row, int col) {
        hidden[row][col] = true;
    }

    /** Shows a hidden cell again. */
    void show(int row, int col) {
        hidden[row][col] = false;
    }

    /** Hides the whole row (the dark phase of the blink of a completed line). */
    void hideRow(int row) {
        for (int c = 0; c < cols; c++) {
            hidden[row][c] = true;
        }
    }

    /** Shows the whole row again (the bright phase of that blink). */
    void showRow(int row) {
        for (int c = 0; c < cols; c++) {
            hidden[row][c] = false;
        }
    }

    /** Reveals every cell: the mask belongs to the decorations and they restart here. */
    void clearHidden() {
        for (int r = 0; r < rows; r++) {
            showRow(r);
        }
    }
}
