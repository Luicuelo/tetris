package tetris;

/**
 * The edge-flag contract of the whole program: which sides of a cell are drawn
 * as a bright or a dark line to fake a 3D block.
 * <p>
 * The edges of a cell are a number in which every bit means "this side has no
 * neighbour". Pieces ({@code Rotations.buildEdges}), the digit font
 * ({@code DigitFont.buildEdges}), the board ({@code markCompletedLineEdges},
 * {@code fillRow}) and the painter
 * ({@link CellPainter#paintCell(int, int, int, int)}) speak this same language,
 * and this class is the only place where the four values appear. The rule that
 * turns neighbours into those edges lives here too, in {@link #edgeCalculation}.
 */
final class Edges {

    static final int LEFT = 1;
    static final int TOP = 2;
    static final int BOTTOM = 4;
    static final int RIGHT = 8;

    /** The four sides: a cell that is alone and therefore outlined everywhere. */
    static final int ALL = LEFT | TOP | BOTTOM | RIGHT;

    /**
     * Says whether a cell belongs to the figure. Two callers answer it in two
     * ways: a piece tests {@code value != 0} on its colour matrix, a digit reads
     * its bitmap.
     */
    @FunctionalInterface
    interface Occupancy {
        boolean isOccupied(int row, int column);
    }

    /**
     * Receives the edges of one occupied cell. The caller decides where they
     * land: a piece writes into its 4×4 matrix, a digit into its own.
     */
    @FunctionalInterface
    interface EdgeWriter {
        void write(int row, int column, int edges);
    }

    /**
     * Edge calculation of one cell of a figure: a side is set when the
     * neighbour in that direction is empty or lies outside the bounding box.
     * <p>
     * This is the single implementation of the rule; {@link #calculateEdges}
     * runs it over a whole figure.
     *
     * @param occupied   says which cells belong to the figure
     * @param row        row of the cell whose edges are calculated
     * @param column     column of the cell whose edges are calculated
     * @param boxRows    rows of the bounding box (exclusive)
     * @param boxColumns columns of the bounding box (exclusive)
     */
    static int edgeCalculation(Occupancy occupied, int row, int column,
                               int boxRows, int boxColumns) {
        int bits = 0;
        boolean onBottom = row == boxRows - 1;
        boolean onRight  = column == boxColumns - 1;
        if (column == 0 || !occupied.isOccupied(row, column - 1)) bits = setLeft(bits);
        if (row == 0    || !occupied.isOccupied(row - 1, column)) bits = setTop(bits);
        if (onBottom    || !occupied.isOccupied(row + 1, column)) bits = setBottom(bits);
        if (onRight     || !occupied.isOccupied(row, column + 1)) bits = setRight(bits);
        return bits;
    }

    /**
     * Calculates the edges of every occupied cell of a figure.
     * <p>
     * The walk is the same for pieces and for digits, so it lives here beside the
     * rule: the caller only says which cells are its own ({@link Occupancy}) and
     * where each cell's edges go ({@link EdgeWriter}). Empty cells never reach
     * the writer, so the target matrix keeps its zeros.
     *
     * @param occupied   says which cells belong to the figure
     * @param boxRows    rows of the bounding box (exclusive)
     * @param boxColumns columns of the bounding box (exclusive)
     * @param writer     receives (row, column, edges) for every occupied cell
     */
    static void calculateEdges(Occupancy occupied, int boxRows, int boxColumns,
                               EdgeWriter writer) {
        for (int row = 0; row < boxRows; row++) {
            for (int column = 0; column < boxColumns; column++) {
                if (!occupied.isOccupied(row, column)) continue;
                int edges = edgeCalculation(occupied, row, column,
                                            boxRows, boxColumns);
                writer.write(row, column, edges);
            }
        }
    }

    private Edges() {
    }

    static int setLeft(int edges)   { return edges | LEFT; }
    static int setTop(int edges)    { return edges | TOP; }
    static int setBottom(int edges) { return edges | BOTTOM; }
    static int setRight(int edges)  { return edges | RIGHT; }

    static int clearLeft(int edges)   { return edges & ~LEFT; }
    static int clearTop(int edges)    { return edges & ~TOP; }
    static int clearBottom(int edges) { return edges & ~BOTTOM; }
    static int clearRight(int edges)  { return edges & ~RIGHT; }

    static boolean hasLeft(int edges)   { return (edges & LEFT)   != 0; }
    static boolean hasTop(int edges)    { return (edges & TOP)    != 0; }
    static boolean hasBottom(int edges) { return (edges & BOTTOM) != 0; }
    static boolean hasRight(int edges)  { return (edges & RIGHT)  != 0; }
}
