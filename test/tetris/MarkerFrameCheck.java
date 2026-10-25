package tetris;

/**
 * Checks that the font metrics keep every digit inside the frame of the marker,
 * for every marker size the game can produce. The frame is painted first (it is
 * one cell, see {@code CellPainter.paintMarker}), so a digit that reached it
 * would rub a hole in the relief.
 * <p>
 * Run it after touching {@code DigitFont} metrics or the marker layout:
 *
 * <pre>
 * javac --release 17 -sourcepath src -d out/test test/tetris/MarkerFrameCheck.java
 * java -cp out/test tetris.MarkerFrameCheck
 * </pre>
 *
 * Prints one line on success and throws {@link AssertionError} naming the
 * offending size on failure, so the process exits with a non-zero status.
 */
class MarkerFrameCheck {

    /**
     * Smallest cell worth checking. Below it the minimums of {@code digitSlotW}
     * (4) and {@code digitW} (1) cannot fit four digit pixels plus the two-pixel
     * gap, so the margin is no longer guaranteed; the canvas would be 105x140.
     */
    private static final int FIRST_PIXEL_SIZE = 7;

    /** Largest cell considered: a canvas of 1800x2400 pixels. */
    private static final int LAST_PIXEL_SIZE = 120;

    public static void main(String[] args) {
        int sizes = 0;
        for (int pixelSize = FIRST_PIXEL_SIZE; pixelSize <= LAST_PIXEL_SIZE; pixelSize++) {
            check(pixelSize);
            sizes++;
        }
        System.out.println("OK: " + sizes + " marker sizes keep the digit inside the frame");
    }

    private static void check(int pixelSize) {
        int markerW = 4 * pixelSize;
        int markerH = 2 * pixelSize;
        DigitFont font = new DigitFont(markerW, markerH);

        // The digit size comes from the font itself, not from a repeated 4x5.
        int digitPixelsW = DigitFont.DIGIT_COLUMNS * font.digitW;
        int digitPixelsH = DigitFont.DIGIT_ROWS * font.digitH;

        if (DigitFont.TOP_MARGIN + digitPixelsH > markerH - 1) {
            throw new AssertionError("pixelSize " + pixelSize + ": a " + digitPixelsH
                    + "-pixel digit starts at row " + DigitFont.TOP_MARGIN
                    + " and reaches the bottom frame of the " + markerH
                    + "-pixel marker");
        }

        int rightOfPreviousSlot = markerW;
        for (int position = 0; position < DigitFont.MARKER_DIGITS; position++) {
            int left = markerW - (position + 1) * font.digitSlotW + 1;
            int right = left + digitPixelsW - 1;
            if (left < 1 || right > markerW - 2) {
                throw new AssertionError("pixelSize " + pixelSize + ", slot " + position
                        + ": digit spans " + left + ".." + right
                        + " but the interior is 1.." + (markerW - 2));
            }
            if (right >= rightOfPreviousSlot) {
                throw new AssertionError("pixelSize " + pixelSize + ", slot " + position
                        + ": digit reaches " + right
                        + " and the slot on its right starts at " + rightOfPreviousSlot);
            }
            rightOfPreviousSlot = left;
        }
    }
}
