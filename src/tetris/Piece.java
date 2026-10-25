package tetris;

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
 * shapes turn exactly) and the edge flags that give the 3D relief when
 * painting. ActivePiece is the piece being played: rotation state (0..3) and
 * board position.
 */
class Piece {

    /** Side length of the square matrix every shape lives in. */
    static final int SIZE = 4;

    // ------------------------------------------------------------------
    // Base shapes: [row][column], max size 4x4.
    // ------------------------------------------------------------------
    static class Shape {
        /**
         * Rows and columns of the box. It normally holds exactly the cells, with
         * no empty row or column on its sides; the long bar is the exception and
         * keeps one empty column on each side of its stem.
         */
        final int maxRow;         // max extent in y (rows)
        final int maxCol;         // max extent in x (columns)
        /**
         * Occupancy of the template, read through {@link #isFilled}.
         */
        private final boolean[][] cells = new boolean[SIZE][SIZE];

        /** @param points occupied cells, each one as {@code {row, column}} */
        Shape(int maxRow, int maxCol, int[][] points) {
            this.maxRow = maxRow;
            this.maxCol = maxCol;
            for (int[] p : points) {
                cells[p[0]][p[1]] = true;
            }
        }

        /** true if the template occupies (row, column). */
        boolean isFilled(int row, int column) {
            return cells[row][column];
        }
    }

    // ------------------------------------------------------------------
    // The ten piece types. Each constant declares its template and owns the
    // four rotation states built from it.
    // ------------------------------------------------------------------
    enum Type {
        /** T: vertical stem, middle top. */
        T(1, new Shape(1, 2, new int[][]{{1, 0}, {1, 1}, {1, 2}, {0, 1}})),
        /** Long bar: 4 cells in one column, with a spare column on each side so it
         *  turns around its own stem instead of jumping sideways. */
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
        /** 2L: tall L, stem 2 + base 3. */
        TWO_L(8, new Shape(2, 2, new int[][]{{0, 1}, {1, 1}, {2, 0}, {2, 1}, {2, 2}})),
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
         * Piece type that occupies position {@code index} in declaration order, so the
         * first N are the classic set. The array is read, never copied.
         *
         * @param index 0..count()-1
         */
        static Type at(int index) {
            return VALUES[index];
        }

        /**
         * true if this type is among the first {@code size} ones in declaration
         * order, that is, one of the pieces a level with that many types may
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

    /**
     * The four states of one type, built once and shared by every piece of that
     * type. Both matrices are private and are read one cell at a time through
     * {@link #valueAt} and {@link #edgeAt}.
     */
    static class Rotations {
        private final int[][][] states = new int[SIZE][SIZE][SIZE];   // [state][row][column]
        private final int[][][] edges = new int[SIZE][SIZE][SIZE];
        private final Shape template;

        Rotations(Shape template, int color) {
            this.template = template;
            // Pre-compute all 4 rotation states at construction time
            for (int state = 0; state < 4; state++) {
                states[state] = buildRotation(state, color);
                edges[state]  = buildEdges(state);
            }
        }

        /** Colour value at (row, column) of rotation state {@code state} (0 = empty). */
        int valueAt(int state, int row, int column) {
            return states[state][row][column];
        }

        /** Edge flags (1=left, 2=top, 4=bottom, 8=right) at (row, column) of {@code state}. */
        int edgeAt(int state, int row, int column) {
            return edges[state][row][column];
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

        /** Rows of the bounding box for rotation state {@code state}. */
        int boxRows(int state) {
            return (isRotated(state) ? template.maxCol : template.maxRow) + 1;
        }

        /** Columns of the bounding box for rotation state {@code state}. */
        int boxCols(int state) {
            return (isRotated(state) ? template.maxRow : template.maxCol) + 1;
        }

        /**
         * Builds the color matrix for rotation state {@code state} using mirrors.
         * State 0 is the template unchanged, so the declared matrix is the shape
         * that appears.
         */
        private int[][] buildRotation(int state, int color) {
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
                    result[pr][pc] = template.isFilled(row, col) ? color : 0;
                }
            }
            return result;
        }

        /**
         * Builds the edge flags for rotation state {@code state}. The walk and the rule
         * live in {@link Edges#calculateEdges}: this method only says which cells
         * are its own and where each cell's edges go.
         */
        private int[][] buildEdges(int state) {
            int[][] result = new int[SIZE][SIZE];
            Edges.Occupancy occupied = (row, col) -> states[state][row][col] != 0;
            Edges.calculateEdges(occupied, boxRows(state), boxCols(state),
                                 (row, col, edges) -> result[row][col] = edges);
            return result;
        }
    }

    // ------------------------------------------------------------------
    // ActivePiece: rotation state and board position.
    // ------------------------------------------------------------------

    static class ActivePiece {
        private int state = 0;
        /** Board column and row of the 4x4 matrix's top-left corner, set by the game. */
        private int posX = 0;
        private int posY = 0;
        private final Type type;

        ActivePiece(Type type) {
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

        /**
         * Row that positions the piece so its box stays completely above the
         * playfield, in the current rotation.
         * <p>
         * Pieces are born in rotation 0, where every box is the real height of
         * the piece ({@code maxRow}, standing up), so the lowest cell lands on
         * the first row above the board and the piece enters with the next fall.
         */
        int spawnPosY() {
            return -type.rotations.boxRows(state);
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
         * Returns true if the piece fits at its current position on {@code board}.
         * Every filled cell is translated to the board and handed to
         * {@link Board#isFree}, which owns the whole rule: a cell above the top
         * row is free (the piece is still entering the playfield), the sides and
         * the bottom of the board are not, and inside the board only the empty
         * cells are. The piece therefore holds no size constants and no bounds
         * arithmetic of its own.
         */
        boolean fitsAt(Board board) {
            return forEachFilledCell((row, col, value, edgeBits) ->
                    board.isFree(row + posY, col + posX));
        }

        /** Visitor of the filled cells of the piece. */
        @FunctionalInterface
        interface CellVisitor {
            /**
             * @param row      row inside the piece matrix
             * @param col      column inside the piece matrix
             * @param value    colour value of the cell (never 0)
             * @param edgeBits edge flags of the cell
             * @return false to stop the traversal
             */
            boolean visit(int row, int col, int value, int edgeBits);
        }

        /**
         * Visits the filled cells of the current rotation, row by row, giving
         * their coordinates inside the piece matrix with their colour value and
         * edge flags. Collision, locking, painting and the preview all walk the
         * matrix through this method, so its shape is described in one place.
         *
         * @param visitor called once per filled cell; false stops the traversal
         * @return true if every filled cell was visited
         */
        boolean forEachFilledCell(CellVisitor visitor) {
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    int value = type.rotations.valueAt(state, row, col);
                    if (value == 0) {
                        continue;
                    }
                    int edges = type.rotations.edgeAt(state, row, col);
                    if (!visitor.visit(row, col, value, edges)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
