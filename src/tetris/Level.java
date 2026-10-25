package tetris;

import java.util.List;

/**
 * One level of the game: the four values that define it.
 * <p>
 * Three of them come from one line of {@code levels.csv} and the fourth from the
 * optional obstacle file of that line, so they travel together: the engine reads
 * them together when it moves on to the level and the editor writes them
 * together.
 *
 * @param linesRequired    completed lines needed to clear the screen
 * @param difficultyActive whether extra blocks appear after each lock
 * @param numPieces        how many piece types may appear (the first N of
 *                         {@link Piece.Type})
 * @param cells            obstacles, each one {@code {row, col, color}}; empty
 *                         when the level has no obstacle file
 */
record Level(int linesRequired, boolean difficultyActive, int numPieces, List<int[]> cells) {

    /** Places the obstacles on the board. */
    void fill(Board board) {
        for (int[] cell : cells) {
            board.place(cell[0], cell[1], cell[2]);
        }
    }
}
