package principal;

/**
 * Pieces and shapes of the Tetris.
 * <p>
 * Every matrix here is indexed {@code [row][column]}, the same order the
 * {@link Board} uses, so a cell of a piece and a cell of the playfield are read
 * and written with the same pair of indices and never have to be swapped.
 * <p>
 * Type is the enum of the ten pieces: T, Long, S, L, L mirrored, Z, Square, 2L,
 * 6 and 9. Shape holds the single base position of a type. Rotations
 * pre-calculates the 4 rotation states of a type (built with mirrors, so the
 * shapes turn exactly) and the edge masks that give the 3D relief when
 * painting. Active is the live piece: rotation state (0..3) and board position.
 */
class Piece {

    /** Side length of the square matrix every shape lives in. */
    static final int SIZE = 4;

    // ------------------------------------------------------------------
    // Base shapes: [row][column], max size 4x4.
    // ------------------------------------------------------------------
    static class Shape {
        final int maxRow;         // max extent in y (rows)
        final int maxCol;         // max extent in x (columns)
        final boolean[][] cells = new boolean[SIZE][SIZE];

        /** @param points occupied cells, each one as {@code {row, column}} */
        Shape(int maxRow, int maxCol, int[][] points) {
            this.maxRow = maxRow;
            this.maxCol = maxCol;
            for (int[] p : points) {
                cells[p[0]][p[1]] = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // The ten piece types. Each constant declares its template and owns the
    // four rotation states built from it.
    // ------------------------------------------------------------------
    enum Type {
        /** T: vertical stem, middle top. */
        T(1, new Shape(2, 2, new int[][]{{1, 0}, {1, 1}, {1, 2}, {0, 1}})),
        /** Long bar: 4 cells vertical. */
        LONG(2, new Shape(3, 2, new int[][]{{0, 1}, {1, 1}, {2, 1}, {3, 1}})),
        /** S: skew piece. */
        S(3, new Shape(2, 1, new int[][]{{0, 0}, {1, 0}, {1, 1}, {2, 1}})),
        /** L: vertical bar on the left, foot to the right. */
        L(4, new Shape(2, 1, new int[][]{{0, 0}, {1, 0}, {2, 0}, {2, 1}})),
        /** L mirrored: vertical bar on the right, foot to the left. */
        L_MIRRORED(5, new Shape(2, 1, new int[][]{{0, 1}, {1, 1}, {2, 1}, {2, 0}})),
        /** Z: S mirrored. */
        Z(6, new Shape(2, 1, new int[][]{{0, 1}, {1, 1}, {1, 0}, {2, 0}})),
        /** Square O (2x2). */
        SQUARE(7, new Shape(1, 1, new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}})),
        /** 2L: 4x4 box with an L, stem 2 + border. */
        TWO_L(8, new Shape(3, 3, new int[][]{{0, 2}, {1, 2}, {2, 1}, {2, 2}, {2, 3}})),
        /** 6: 2x2 square with an extra cell at the right of the top row. */
        SIX(9, new Shape(1, 2, new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, 2}})),
        /** 9: the same square with the extra cell at the right of the bottom row. */
        NINE(10, new Shape(1, 2, new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}, {1, 2}}));

        /** Colour value of the cells of this type (1..10). */
        final int color;
        /** The four rotation states of this type, built once and shared. */
        final Rotations rotations;

        Type(int color, Shape template) {
            this.color = color;
            this.rotations = new Rotations(template, color);
        }

        /** The ten types, in declaration order: the only list of pieces there is. */
        private static final Type[] VALUES = values();

        /** Number of piece types defined. */
        static int count() {
            return VALUES.length;
        }

        /**
         * Piece type that occupies position 'index' in declaration order, so the
         * first N are the classic set. The array is read, never copied.
         *
         * @param index 0..count()-1
         */
        static Type at(int index) {
            return VALUES[index];
        }

        /**
         * true if this type is among the first 'size' ones in declaration
         * order, that is, one of the pieces a screen with that many types may
         * draw.
         *
         * @param size number of types in play
         */
        boolean allowedIn(int size) {
            return ordinal() < size;
        }
    }

    // ------------------------------------------------------------------
    // Rotations: pre-calculates the 4 rotation states of a type.
    // ------------------------------------------------------------------
    static class Rotations {
        final int[][][] shapes = new int[SIZE][SIZE][SIZE];   // [state][row][column]
        final int[][][] edges = new int[SIZE][SIZE][SIZE];
        final Shape template;

        Rotations(Shape template, int color) {
            this.template = template;
            // Pre-compute all 4 rotation states at construction time
            for (int state = 0; state < 4; state++) {
                shapes[state] = buildShape(state, color);
                edges[state] = buildEdges(state);
            }
        }

        /** Color matrix [row][column] for rotation state 'state' (0 = empty). */
        int[][] shape(int state) {
            return shapes[state];
        }

        /** Edge flags (1=left, 2=top, 4=bottom, 8=right) for rotation state 'state'. */
        int[][] edgesFor(int state) {
            return edges[state];
        }

        /**
         * true if this state swaps rows and columns (rotated 90° or 270°).
         * The four states are generated clockwise: 0 up, 1 right, 2 down,
         * 3 left, so state + 1 always turns the piece to the right.
         */
        boolean isRotated(int state) {
            return (state % 2) != 0;
        }

        /** true if the state keeps its rows, that is, no up/down mirror. */
        private boolean keepsRows(int state) {
            return (state == 0 || state == 3);
        }

        /** true if the state keeps its columns, that is, no left/right mirror. */
        private boolean keepsColumns(int state) {
            return state < 2;
        }

        /** Rows of the bounding box for rotation state 'state'. */
        int boxRows(int state) {
            return (isRotated(state) ? template.maxCol : template.maxRow) + 1;
        }

        /** Columns of the bounding box for rotation state 'state'. */
        int boxCols(int state) {
            return (isRotated(state) ? template.maxRow : template.maxCol) + 1;
        }

        /**
         * Builds the color matrix for rotation state 'state' using mirrors.
         * Each cell of the template is written at a new [row][column]
         * position; nothing is copied cell by cell. State 0 is the template
         * unchanged, so the declared matrix is the shape that appears.
         */
        private int[][] buildShape(int state, int color) {
            int[][] result = new int[SIZE][SIZE];
            for (int row = 0; row <= template.maxRow; row++) {
                for (int col = 0; col <= template.maxCol; col++) {
                    int pr = row;
                    int pc = col;
                    if (!keepsColumns(state)) {
                        pc = template.maxCol - col;    // mirror left/right
                    }
                    if (!keepsRows(state)) {
                        pr = template.maxRow - row;    // mirror up/down
                    }
                    if (isRotated(state)) {
                        int t = pr;
                        pr = pc;
                        pc = t;                       // swap row and column
                    }
                    result[pr][pc] = template.cells[row][col] ? color : 0;
                }
            }
            return result;
        }

        /**
         * Builds the edge flags for rotation state 'state': an edge appears
         * when the neighbor cell is empty or out of bounds.
         */
        private int[][] buildEdges(int state) {
            int[][] result = new int[SIZE][SIZE];
            final int lastRow = boxRows(state) - 1;
            final int lastCol = boxCols(state) - 1;

            // The mask is the shared contract of Edges: a side is drawn when its
            // neighbour is empty or lies outside the rotated bounding box.
            Edges.Area area = (row, col) -> row >= 0 && col >= 0
                    && row <= lastRow && col <= lastCol
                    && shapes[state][row][col] != 0;

            for (int row = 0; row <= lastRow; row++) {
                for (int col = 0; col <= lastCol; col++) {
                    if (shapes[state][row][col] != 0) {
                        result[row][col] = Edges.mask(row, col, area);
                    }
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Active piece: rotation state and board position.
    // ------------------------------------------------------------------

    static class Active {
        private int state = 0;
        /** Column and row where the 4x4 matrix is anchored, set by the game. */
        private int posX = 0;
        private int posY = 0;
        private final Type type;

        Active(Type type) {
            this.type = type;
        }

        /** The type this piece is: its shape, its colour and its rotations. */
        Type type() {
            return type;
        }

        int getState()      { return state; }
        int getPosX()       { return posX; }
        int getPosY()       { return posY; }
        void setPosX(int x) { posX = x; }
        void setPosY(int y) { posY = y; }

        /**
         * Rows of the bounding box for the current rotation, used to centre
         * the piece in the preview box. Swapped when the state is rotated
         * (90° or 270°).
         */
        int previewRows() {
            return type.rotations.boxRows(state);
        }

        /** Columns of the bounding box for the current rotation. */
        int previewCols() {
            return type.rotations.boxCols(state);
        }

        /** Color matrix [row][column] for the current rotation state. */
        int[][] currentShape() {
            return type.rotations.shape(state);
        }

        /** Edge flags for the current rotation state. */
        int[][] currentEdges() {
            return type.rotations.edgesFor(state);
        }

        void nextState() {
            setState((state + 1) & 3);
        }

        void prevState() {
            setState((state + 3) & 3);
        }

        void setState(int s) {
            state = s & 3;
        }

        /**
         * Returns true if the piece fits at its current position on 'board'.
         * It does NOT check negative row values, only the board bounds and
         * collisions with its cells. The geometry comes from the board itself,
         * so the piece holds no size constants.
         */
        boolean fitsAt(Board board) {
            return forEachCell((row, col, value, edgeBits) -> {
                int boardCol = col + posX;
                int boardRow = row + posY;
                if (boardCol < 0 || boardCol >= board.cols() || boardRow >= board.rows()) {
                    return false;
                }
                return board.valueAt(boardRow, boardCol) == 0;
            });
        }

        /** Visitor of the filled cells of the piece. */
        @FunctionalInterface
        interface CellVisitor {
            /**
             * @param row      row inside the piece matrix
             * @param col      column inside the piece matrix
             * @param value    colour value of the cell (never 0)
             * @param edgeBits 3D edge mask of the cell
             * @return false to stop the traversal
             */
            boolean visit(int row, int col, int value, int edgeBits);
        }

        /**
         * Visits the filled cells of the current rotation, row by row, giving
         * their coordinates inside the piece matrix with their colour value and
         * edge mask. Collision, locking, painting and the preview all walk the
         * matrix through this method, so its shape is described in one place.
         *
         * @param visitor called once per filled cell; false stops the traversal
         * @return true if every filled cell was visited
         */
        boolean forEachCell(CellVisitor visitor) {
            int[][] shape = currentShape();
            int[][] edges = currentEdges();
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    if (shape[row][col] == 0) {
                        continue;
                    }
                    if (!visitor.visit(row, col, shape[row][col], edges[row][col])) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
