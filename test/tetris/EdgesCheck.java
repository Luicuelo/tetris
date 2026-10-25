package tetris;

/**
 * Checks the edge flags of every piece state and every digit against the rule
 * written the long way, with the four bit values spelled out. The point is to
 * catch a change in {@code Edges.edgeCalculation} or
 * {@code Edges.calculateEdges} that still compiles and still draws something,
 * but no longer draws the same relief.
 * <p>
 * Run it after touching {@code Edges}, {@code Piece} or {@code DigitFont}:
 *
 * <pre>
 * javac --release 17 -sourcepath src -d out/test test/tetris/EdgesCheck.java
 * java -cp out/test tetris.EdgesCheck
 * </pre>
 *
 * Prints one line on success and throws {@link AssertionError} naming the
 * offending cell on failure, so the process exits with a non-zero status.
 */
class EdgesCheck {

    /** The four sides, as numbers, so this class does not borrow the contract. */
    private static final int LEFT = 1, TOP = 2, BOTTOM = 4, RIGHT = 8;

    public static void main(String[] args) {
        int cells = checkPieces() + checkDigits();
        checkWorkedExample();
        System.out.println("OK: " + cells + " cells match the reference rule");
    }

    /**
     * Every cell of the 10 types x 4 states: occupied cells against the rule,
     * empty cells against zero, and no occupied cell outside its bounding box.
     */
    private static int checkPieces() {
        int cells = 0;
        for (int index = 0; index < Piece.Type.count(); index++) {
            Piece.Type type = Piece.Type.at(index);
            for (int state = 0; state < 4; state++) {
                boolean[][] filled = new boolean[Piece.SIZE][Piece.SIZE];
                for (int row = 0; row < Piece.SIZE; row++) {
                    for (int col = 0; col < Piece.SIZE; col++) {
                        filled[row][col] = type.rotations.valueAt(state, row, col) != 0;
                    }
                }

                int boxRows = type.rotations.boxRows(state);
                int boxCols = type.rotations.boxCols(state);
                for (int row = 0; row < Piece.SIZE; row++) {
                    for (int col = 0; col < Piece.SIZE; col++) {
                        if (filled[row][col] && (row >= boxRows || col >= boxCols)) {
                            throw new AssertionError(type + " state " + state + ": cell "
                                    + row + "," + col + " outside the "
                                    + boxRows + "x" + boxCols + " bounding box");
                        }
                        int expected = filled[row][col]
                                ? reference(filled, row, col, boxRows, boxCols) : 0;
                        int actual = type.rotations.edgeAt(state, row, col);
                        if (actual != expected) {
                            throw new AssertionError(type + " state " + state + " at "
                                    + row + "," + col + ": " + actual
                                    + " != " + expected);
                        }
                        cells++;
                    }
                }
            }
        }
        return cells;
    }

    /** Every cell of the ten digits, empty ones included. */
    private static int checkDigits() {
        int cells = 0;
        int boxRows = DigitFont.DIGIT_ROWS;
        int boxCols = DigitFont.DIGIT_COLUMNS;
        for (int digit = 0; digit < 10; digit++) {
            // A local copy of the bitmap: this class reads the font through its
            // boolean accessor and keeps the rule of the edges written by hand.
            boolean[][] pixels = new boolean[boxRows][boxCols];
            for (int row = 0; row < boxRows; row++) {
                for (int col = 0; col < boxCols; col++) {
                    pixels[row][col] = DigitFont.digitPixel(digit, row, col);
                }
            }
            for (int row = 0; row < boxRows; row++) {
                for (int col = 0; col < boxCols; col++) {
                    int expected = pixels[row][col]
                            ? reference(pixels, row, col, boxRows, boxCols) : 0;
                    if (DigitFont.edgesOf(digit, row, col) != expected) {
                        throw new AssertionError("digit " + digit + " at " + row + ","
                                + col + ": " + DigitFont.edgesOf(digit, row, col)
                                + " != " + expected);
                    }
                    cells++;
                }
            }
        }
        return cells;
    }

    /**
     * The worked example of PIECES-EXPLAINED: the T in state 0. It ties the
     * document to the code, so a change in one is noticed in the other.
     */
    private static void checkWorkedExample() {
        expect(Piece.Type.T, 0, 0, 1, LEFT | TOP | RIGHT);    // 11
        expect(Piece.Type.T, 0, 1, 0, LEFT | TOP | BOTTOM);   // 7
        expect(Piece.Type.T, 0, 1, 1, BOTTOM);                // 4
        expect(Piece.Type.T, 0, 1, 2, TOP | BOTTOM | RIGHT);  // 14
    }

    private static void expect(Piece.Type type, int state, int row, int col, int expected) {
        int actual = type.rotations.edgeAt(state, row, col);
        if (actual != expected) {
            throw new AssertionError("worked example of " + type + " state " + state
                    + " at " + row + "," + col + ": " + actual + " != " + expected);
        }
    }

    /**
     * The rule written the long way, on a bitmap and with the bits spelled out:
     * a side is a border when the neighbour is off or falls outside the box.
     */
    private static int reference(boolean[][] filled, int row, int col,
                                 int boxRows, int boxCols) {
        int bits = 0;
        if (col == 0           || !filled[row][col - 1]) bits |= LEFT;
        if (row == 0           || !filled[row - 1][col]) bits |= TOP;
        if (row == boxRows - 1 || !filled[row + 1][col]) bits |= BOTTOM;
        if (col == boxCols - 1 || !filled[row][col + 1]) bits |= RIGHT;
        return bits;
    }
}
