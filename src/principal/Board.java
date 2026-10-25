package principal;

/**
 * The playfield: single owner of the board geometry (rows x cols) and of the
 * cell state (values, 3D edge flags, per-row fill counters and the topmost
 * occupied row).
 * <p>
 * The game ({@link Game}) drives the rules and asks the board to perform the
 * state changes; the {@link Renderer} only reads it through the accessors, so
 * the dimensions are declared exactly once, here.
 * <p>
 * The edge flags follow the {@link Edges} contract: every side without a
 * neighbour is marked, and the renderer turns that mask into the 3D relief.
 */
class Board {

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

    /** Value of a cell (1..10, or 0 if empty). */
    int valueAt(int row, int col) {
        return cells[row][col];
    }

    /** Edge flags of a cell (1=left, 2=top, 4=bottom, 8=right). */
    int edgeAt(int row, int col) {
        return edges[row][col];
    }

    // ==================================================================
    // Writing (state transitions requested by the engine)
    // ==================================================================

    /** Empties the whole board and resets the bookkeeping. */
    void clear() {
        cells  = new int[rows][cols];
        edges  = new int[rows][cols];
        fill   = new int[rows];
        topRow = rows - 1;
    }

    /**
     * Writes a single cell and updates the row counter and the topmost
     * occupied row. The cell is overwritten unconditionally, both when a piece
     * locks and when an obstacle appears.
     */
    void setCell(int row, int col, int value, int edgeBits) {
        cells[row][col] = value;
        edges[row][col] = edgeBits;
        fill[row]++;
        if (row < topRow) {
            topRow = row;
        }
    }

    /** Places a fully outlined cell (pre-placed obstacle or difficulty block). */
    void place(int row, int col, int value) {
        setCell(row, col, value, Edges.ALL);
    }

    /**
     * Sets the flash edge flags on the rows surrounding a completed line:
     * bottom on the row above, top+bottom on the line itself, top on the row
     * below.
     */
    void markLineFlash(int row) {
        for (int c = 0; c < cols; c++) {
            if (row > 0)         edges[row - 1][c] |= Edges.BOTTOM;
            edges[row][c] |= (Edges.TOP | Edges.BOTTOM);
            if (row < rows - 1)  edges[row + 1][c] |= Edges.TOP;
        }
    }

    /**
     * Removes row 'row': every row above it (down to the topmost occupied one)
     * shifts one step down, and the freed top row becomes empty.
     */
    void removeRow(int row) {
        for (int a = row - 1; a >= topRow; a--) {
            cells[a + 1] = cells[a];
            edges[a + 1] = edges[a];
            fill[a + 1]  = fill[a];
        }
        cells[topRow]  = new int[cols];
        edges[topRow]  = new int[cols];
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
            int edgeBits = (c == 0) ? (Edges.LEFT | Edges.TOP | Edges.BOTTOM)
                       : (c == cols - 1) ? (Edges.TOP | Edges.BOTTOM | Edges.RIGHT)
                       : (Edges.TOP | Edges.BOTTOM);
            cells[row][c] = value;
            edges[row][c] = edgeBits;
        }
    }
}
