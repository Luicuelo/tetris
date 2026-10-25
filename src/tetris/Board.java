package tetris;

/**
 * The playfield: single owner of the board geometry (rows x cols) and of the
 * cell state (values, 3D edge flags, per-row fill counters, the topmost
 * occupied row and the hidden mask).
 * <p>
 * The game ({@link Engine}) drives the rules and asks the board to perform the
 * state changes; the {@link Renderer} only reads it, so the dimensions are
 * declared exactly once, here.
 * <p>
 * The edge flags follow the {@link Edges} contract: every side without a
 * neighbour is marked, and the renderer turns those edges into the 3D relief.
 * The hidden mask is the other half of the picture: a cell marked there keeps
 * its value and its rules, but is not drawn (the blink of a new block, the
 * flash of a completed line).
 * <p>
 * The cells above the top row do not exist in the matrix: a piece enters the
 * playfield from there, so {@link #isFree} answers "free" for them and nobody
 * has to index a row the board does not have. Writing is the other half of that
 * contract and only accepts cells that {@link #inBounds} accepts.
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
    /** Number of filled cells per row, used to detect completed lines. */
    private int[] fill;
    /** Topmost row that contains any content (smallest occupied row index). */
    private int topRow;

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

    /** Topmost occupied row index (rows above it are guaranteed empty). */
    int topRow() {
        return topRow;
    }

    /** Filled cells in row 'row'. */
    int fillCount(int row) {
        return fill[row];
    }

    /** true if row 'row' is inside the board and completely filled. */
    boolean isLineComplete(int row) {
        return row >= 0 && row < rows && fill[row] >= cols;
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
     *   <li>above the top row (negative row): free, because that is where a
     *       piece enters the playfield and the matrix has no cell to collide
     *       with;</li>
     *   <li>outside the board sideways, or below it: blocked;</li>
     *   <li>inside the board: free only when the cell is empty.</li>
     * </ul>
     * Collision and locking ask this method instead of translating a position
     * and reading the matrix themselves, so the vertical overhang of a spawning
     * piece is resolved in one place.
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
        cells  = new int[rows][cols];
        edges  = new int[rows][cols];
        hidden = new boolean[rows][cols];
        fill   = new int[rows];
        topRow = rows - 1;
    }

    /**
     * Writes a single cell and counts it in its row when the cell was empty:
     * a piece lock, an obstacle and a painted cell always fill an empty cell,
     * and painting over an occupied one only changes its value. Emptying is the
     * job of {@link #clearCell}; this method does not take 0 as a value.
     */
    void setCell(int row, int col, int value, int edgeBits) {
        if (cells[row][col] == 0) fill[row]++;
        cells[row][col] = value;
        edges[row][col] = edgeBits;
        if (row < topRow) {
            topRow = row;
        }
    }

    /**
     * Empties a single cell and takes it out of the counter of its row. When it
     * was the last cell of the topmost occupied row, the top row moves down to
     * the next row that still holds content (or to the floor), so the rows above
     * it stay empty.
     */
    void clearCell(int row, int col) {
        if (cells[row][col] == 0) return;
        cells[row][col] = 0;
        edges[row][col] = 0;
        fill[row]--;
        if (row == topRow && fill[row] == 0) {
            topRow = firstFilledRow(row);
        }
    }

    /** Places a fully outlined cell (pre-placed obstacle or difficulty block). */
    void place(int row, int col, int value) {
        setCell(row, col, value, Edges.ALL);
    }

    /**
     * First row at or below {@code from} that still holds content; the last row
     * when there is none, which is where {@link #clear()} leaves the board.
     */
    private int firstFilledRow(int from) {
        for (int row = from; row < rows; row++) {
            if (fill[row] > 0) {
                return row;
            }
        }
        return rows - 1;
    }

    /**
     * Marks the edges of a completed line and of its neighbours: bottom on the
     * row above, top+bottom on the line itself, top on the row below.
     */
    void markCompletedLineEdges(int row) {
        for (int c = 0; c < cols; c++) {
            if (row > 0)        edges[row - 1][c] = Edges.setBottom(edges[row - 1][c]);
            edges[row][c] = Edges.setTop(Edges.setBottom(edges[row][c]));
            if (row < rows - 1) edges[row + 1][c] = Edges.setTop(edges[row + 1][c]);
        }
    }

    /**
     * Removes row 'row': every row above it (down to the topmost occupied one)
     * shifts one step down, and the freed top row becomes empty.
     */
    void removeRow(int row) {
        for (int r = row - 1; r >= topRow; r--) {
            cells[r + 1]  = cells[r];
            edges[r + 1]  = edges[r];
            hidden[r + 1] = hidden[r];
            fill[r + 1]   = fill[r];
        }
        cells[topRow]  = new int[cols];
        edges[topRow]  = new int[cols];
        hidden[topRow] = new boolean[cols];
        fill[topRow]   = 0;
        topRow++;
    }

    /**
     * Fills the whole row 'row' with 'value', giving the leftmost cell a left
     * edge, the rightmost a right edge and the middle ones only top/bottom.
     * Used by the level-transition fill effect.
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

    /** Hides the whole row (the dark phase of the flash of a completed line). */
    void hideRow(int row) {
        for (int c = 0; c < cols; c++) {
            hidden[row][c] = true;
        }
    }

    /** Shows the whole row again (the bright phase of that flash). */
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
