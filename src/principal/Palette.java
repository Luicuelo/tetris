package principal;

/**
 * Palette of colors of the game.
 * Indices:
 * 0     : reserved, never drawn.
 * 1, 2  : alternating background of the board (mosaic).
 * 3     : background of the separator column.
 * 4     : background of the info panel.
 * 5..14 : the 10 colors of the pieces:
 *         index = BASE_COLORS + (v - 1), being v the value of the matrix.
 *         Index 12 (pink) is also the fill of the level transition.
 * 15    : spare color, reserved and never painted.
 * 16,17 : digits of the markers (red score, blue marker).
 * 18    : background of the markers.
 * <p>
 * Every color keeps the base RGB and two variants built from it: brighter for
 * the left/top edges and darker for the right/bottom ones. The colors are
 * delivered in a buffer BGRA for the PixelWriter.
 */
class Palette {

    /** Index of the first palette color of the form colors. */
    static final int BASE_COLORS = 5;
    /** Number of colors of forms (the forms carry 1..10). */
    static final int N_FORMCOLORS = 10;
    /** Index of the last color of forms. */
    static final int LAST_COLORS = BASE_COLORS + N_FORMCOLORS - 1;

    static final int BG_A = 1;
    static final int BG_B = 2;
    static final int GAP = 3;
    static final int INFO_BG = 4;
    static final int TRANS_FILL = 12;
    static final int DIGIT_RED = 16;
    static final int DIGIT_BLUE = 17;
    static final int MARKER_BG = 18;

    /** Variants of a color. */
    static final int BASE = 0;
    static final int BRIGHT = 1;
    static final int DARK = 2;

    /** Number of indices of the palette. */
    private static final int COLOR_ENTRIES = 19;

    /**
     * Palette index of a matrix value: pieces carry 1..10 and obstacles 1..14,
     * so the colour is BASE_COLORS + value - 1.
     */
    static int indexOfValue(int v) {
        return BASE_COLORS + v - 1;
    }

    /** Matrix value that paints as palette index 'index' (inverse of the above). */
    static int valueOfIndex(int index) {
        return index - BASE_COLORS + 1;
    }

    // Colors as {r, g, b} (0..255). Index 0 reserved (black).
    private static final int[][] RGB = {
            {0, 0, 0},                      // 0 reserved (box / black bg)
            {24, 26, 44},                   // 1 background alternated A of the board
            {38, 40, 66},                   // 2 background alternated B of the board
            {70, 70, 110},                  // 3 separator column
            {128, 128, 128},                // 4 info panel (gray)
            {70, 190, 70},                  // 5  piece color 1: green
            {185, 75, 75},                  // 6  piece color 2: red
            {40, 185, 185},                 // 7  piece color 3: cyan
            {215, 200, 65},                 // 8  piece color 4: yellow
            {180, 75, 180},                 // 9  piece color 5: magenta
            {220, 135, 30},                 // 10 piece color 6: orange
            {75, 85, 200},                  // 11 piece color 7: blue
            {210, 130, 165},                // 12 piece color 8: pink (fill of transition)
            {110, 110, 110},                // 13 piece color 9: gray
            {120, 30, 60},                  // 14 piece color 10: dark crimson
            {200, 40, 40},                  // 15 spare color, never painted
            {255, 0, 0},                    // 16 digits of the score (red)
            {0, 0, 178},                    // 17 digits of the marker (dark blue)
            {220, 220, 220},                // 18 background of the marker (light gray)
    };

    /** Factor applied to build the dark variant of a color. */
    private static final double DARKFACTOR = 0.6;
    /** Amount added to build the bright variant of a color. */
    private static final double BRIGHTADD = 45.0;
    /** First and last index that darken with the pause and the end of the game;
     * the backgrounds and the digits keep their brightness. */
    private static final int DIM_FROM = BASE_COLORS;
    private static final int DIM_TO = BASE_COLORS + N_FORMCOLORS;   // last piece color

    /** LUT of BGRA blocks that is recalculated when the palette changes. */
    private final byte[][][] lut = new byte[COLOR_ENTRIES][3][4];
    private boolean dirty = true;
    /** Factor 0..1 applied to the colors in play (pause / end). */
    private double dim = 1.0;

    /** If dirty rebuilds the LUT from the current RGB and dim. */
    private void rebuild() {
        if (!dirty) {
            return;
        }
        for (int c = 0; c < COLOR_ENTRIES; c++) {
            // the dim only applies to the colors of the game;
            // backgrounds and digits stay intact
            double f = (c >= DIM_FROM && c <= DIM_TO) ? dim : 1.0;
            double r = RGB[c][0] * f;
            double g = RGB[c][1] * f;
            double b = RGB[c][2] * f;
            fillVariant(lut[c][BASE], r, g, b);
            fillVariant(lut[c][BRIGHT], cap(r + BRIGHTADD), cap(g + BRIGHTADD), cap(b + BRIGHTADD));
            fillVariant(lut[c][DARK], r * DARKFACTOR, g * DARKFACTOR, b * DARKFACTOR);
        }
        dirty = false;
    }

    /** Copies the RGB calculation into a block BGRA. */
    private void fillVariant(byte[] block, double r, double g, double b) {
        block[0] = (byte) b;    // blue
        block[1] = (byte) g;    // green
        block[2] = (byte) r;    // red
        block[3] = (byte) 255;  // alpha
    }

    /** Caps a value in the range 0..255. */
    private double cap(double v) {
        return (v > 255.0) ? 255.0 : v;
    }

    /**
     * Delivers the BGRA block of a color and variant for the PixelWriter.
     *
     * @param index   palette index
     * @param variant BASE, BRIGHT or DARK
     * @return block of 4 bytes {b, g, r, a}
     */
    byte[] pixel(int index, int variant) {
        rebuild();
        return lut[index][variant];
    }

    /** Applies a darkening factor 0..1 to the colors of the game (pause/end). */
    void setDim(double factor) {
        if (Math.abs(factor - dim) > 1e-9) {
            dim = Math.max(0.0, Math.min(1.0, factor));
            dirty = true;
        }
    }
}
