package principal;

/**
 * The edge-mask contract of the whole program: which sides of a cell are drawn
 * as a bright or a dark line to fake a 3D block.
 * <p>
 * A mask is a number in which every bit means "this side has no neighbour".
 * Pieces ({@code Rotations.buildEdges}), the board ({@code markLineFlash},
 * {@code fillRow}) and the painter ({@code Renderer.paintCell}) speak this same
 * language, and this class is the only place where the four values appear.
 */
final class Edges {

    static final int LEFT = 1;
    static final int TOP = 2;
    static final int BOTTOM = 4;
    static final int RIGHT = 8;

    /** The four sides: a cell that is alone and therefore outlined everywhere. */
    static final int ALL = LEFT | TOP | BOTTOM | RIGHT;

    private Edges() {
    }

    /** Tells whether a cell belongs to the shape that is being outlined. */
    @FunctionalInterface
    interface Area {
        boolean isFilled(int row, int col);
    }

    /**
     * Mask of a filled cell: a side is marked when its neighbour is not part of
     * the same area. Cells outside the area count as empty, so the caller does
     * not have to check its own limits.
     *
     * @param row  row of the cell inside the area
     * @param col  column of the cell inside the area
     * @param area filled-cell query of the shape the cell belongs to
     * @return the side bits of that cell, to be painted bright (left, top) or
     *         dark (bottom, right)
     */
    static int mask(int row, int col, Area area) {
        int bits = 0;
        if (!area.isFilled(row, col - 1)) bits |= LEFT;
        if (!area.isFilled(row - 1, col)) bits |= TOP;
        if (!area.isFilled(row + 1, col)) bits |= BOTTOM;
        if (!area.isFilled(row, col + 1)) bits |= RIGHT;
        return bits;
    }

    /** True when 'mask' contains 'side'. */
    static boolean has(int mask, int side) {
        return (mask & side) != 0;
    }
}
