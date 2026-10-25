package tetris;

/**
 * 4×5 bitmap font for the markers.
 * <p>
 * Each digit is a 4×5 boolean grid (col 0..3, row 0..4).
 * Layout geometry ({@code digitSlotW}, {@code digitW}, {@code digitH}) is derived
 * from the marker rectangle size and handed to the {@link Renderer}, which does
 * all the actual painting. The font also pre-calculates the 3D edge flags of
 * every digit, so the renderer only has to read them.
 */
final class DigitFont {

    /** Number of digits in each marker. */
    static final int MARKER_DIGITS = 4;

    /** Margin, in pixels, between the marker frame and the digit rows (top side). */
    static final int TOP_MARGIN = 2;

    /** Margin, in pixels, between the digit rows and the marker frame (bottom side). */
    private static final int BOTTOM_MARGIN = 1;

    /** Columns and rows of the matrix of one digit. */
    static final int DIGIT_COLUMNS = 4;
    static final int DIGIT_ROWS    = 5;

    /** Pixels wide of one digit slot (the space reserved per digit, including inter-digit gap). */
    final int digitSlotW;
    /** Pixels wide of one digit column block. */
    final int digitW;
    /** Pixels tall of one digit row block. */
    final int digitH;

    /**
     * Derives the digit metrics from the marker rectangle. Both block sizes round
     * <em>down</em>, so a whole digit always fits inside the frame of the marker.
     */
    DigitFont(int markerW, int markerH) {
        digitSlotW = Math.max(4, (markerW - 4) / MARKER_DIGITS);
        digitW     = Math.max(1, (digitSlotW - 2) / DIGIT_COLUMNS);
        digitH     = Math.max(1, (markerH - TOP_MARGIN - BOTTOM_MARGIN) / DIGIT_ROWS);
    }

    /**
     * true if pixel ({@code row}, {@code column}) of digit {@code n} is lit. The
     * matrices stay private.
     *
     * @param n      0–9
     * @param row    0..DIGIT_ROWS-1
     * @param column 0..DIGIT_COLUMNS-1
     */
    static boolean digitPixel(int n, int row, int column) {
        return DIGITS[n][row][column];
    }

    /** Returns the edge flags of cell ({@code row}, {@code column}) of digit {@code n}. */
    static int edgesOf(int n, int row, int column) { return EDGES[n][row][column]; }

    // ==================================================================
    // 4×5 bitmap font
    // ==================================================================

    private static final boolean T = true, F = false;

    // Each digit is a 5-row × 4-col boolean grid: true = pixel on, row 0 at the
    // top, column 0 on the left. Every digit keeps a one-pixel stroke; 4 and 5
    // stop one column short of the left edge, so the ten digits carry the same
    // weight. The closed digits (0, 8) are mirror-symmetric on both axes, so they
    // read as a box and not as a box with a corner bitten off. The 1 carries a
    // serif and a base that ends on the right edge, so a column of digits stays
    // aligned.
    private static final boolean[][][] DIGITS = {
        // 0
        {{F,T,T,F},{T,F,F,T},{T,F,F,T},{T,F,F,T},{F,T,T,F}},
        // 1
        {{F,F,T,F},{F,T,T,F},{F,F,T,F},{F,F,T,F},{F,T,T,T}},
        // 2
        {{F,T,T,F},{T,F,F,T},{F,F,T,F},{F,T,F,F},{T,T,T,T}},
        // 3
        {{T,T,T,F},{F,F,F,T},{F,T,T,F},{F,F,F,T},{T,T,T,F}},
        // 4
        {{T,F,F,T},{T,F,F,T},{F,T,T,T},{F,F,F,T},{F,F,F,T}},
        // 5
        {{F,T,T,T},{T,F,F,F},{F,T,T,F},{F,F,F,T},{T,T,T,F}},
        // 6
        {{F,T,T,F},{T,F,F,F},{T,T,T,F},{T,F,F,T},{F,T,T,F}},
        // 7
        {{T,T,T,T},{F,F,F,T},{F,F,T,F},{F,T,F,F},{F,T,F,F}},
        // 8
        {{F,T,T,F},{T,F,F,T},{T,T,T,T},{T,F,F,T},{F,T,T,F}},
        // 9
        {{F,T,T,F},{T,F,F,T},{F,T,T,T},{F,F,F,T},{F,T,T,F}},
    };

    /** Edge flags of every digit, pre-calculated once: [digit][row][column]. */
    private static final int[][][] EDGES = buildEdges();

    /**
     * Pre-calculates the edge flags of the ten digits: which pixels are lit and
     * where each cell's edges go.
     */
    private static int[][][] buildEdges() {
        int[][][] allEdges = new int[DIGITS.length][][];
        for (int digit = 0; digit < DIGITS.length; digit++) {
            boolean[][] pixels = DIGITS[digit];
            int rows    = pixels.length;
            int columns = pixels[0].length;
            int[][] digitEdges = new int[rows][columns];
            allEdges[digit] = digitEdges;
            Edges.Occupancy occupied = (row, column) -> pixels[row][column];
            Edges.calculateEdges(occupied, rows, columns, (row, column, edges) -> {
                digitEdges[row][column] = edges;
            });
        }
        return allEdges;
    }
}
