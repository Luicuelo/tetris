package tetris;

import java.util.ArrayList;
import java.util.List;

/**
 * The state of a game in progress: what the {@link Renderer} reads and what the
 * {@link Engine} keeps between ticks. No rules live here — only the data and the
 * bookkeeping that the engine performs on it.
 * <p>
 * Fields are private and every mutator is package-private, so only classes in
 * the same package can change the state. The {@link Renderer}, which only reads,
 * goes through the accessors and never sees the raw fields.
 * <p>
 * The internal state of the machine ({@code FALLING}, {@code COMPLETED_LINE},
 * etc.) is an implementation detail: the renderer only needs to know whether
 * the game is over or paused, so those two booleans are the public contract
 * and the enum stays private to this class.
 * <p>
 * Brightness is not part of the state: the renderer derives it from these two
 * booleans and from the game-over fade of {@link Effects}, which is the only
 * owner of that curve.
 */
final class GameState {

    /**
     * The state of the engine's machine. Only {@link Engine} drives the
     * transitions; the renderer reads the derived booleans {@link #isGameOver()}
     * and {@link #isPaused()} instead.
     */
    enum State {
        /** Gravity and the player: the piece falls and can be moved. */
        FALLING,
        /** A completed line is flashing; it is removed when the flash ends. */
        COMPLETED_LINE,
        /** The board is filled with the transition colour before the next screen. */
        SCREEN_TRANSITION,
        /** The screen darkens and the game waits for Restart. */
        GAME_OVER
    }

    private final Board          board           = new Board();
    private State                currentState    = State.FALLING;
    private boolean              paused          = false;
    private int                  score           = 0;
    private int                  linesLeft       = 0;
    private Piece.ActivePiece    currentPiece    = null;
    private Piece.ActivePiece    nextPiece       = null;
    private boolean              difficultyActive = false;
    private int                  difficultyCount  = 0;

    /** Completed rows waiting for their flash, processed in arrival order. */
    private final List<Integer>  completedLines   = new ArrayList<>();
    /** Rows of the screen-clear fill already painted, from the top down. */
    private int                  paintedRows      = 0;

    // ==================================================================
    // Package-private mutators (Engine is the only writer)
    // ==================================================================

    void setCurrentState(State s)             { currentState    = s; }
    void setPaused(boolean p)                 { paused          = p; }
    void setScore(int s)                      { score           = s; }
    void addScore(int delta)                  { score          += delta; }
    void setLinesLeft(int l)                  { linesLeft       = l; }
    void decrementLinesLeft()                 { if (linesLeft > 0) linesLeft--; }
    void setCurrentPiece(Piece.ActivePiece p) { currentPiece    = p; }
    void setNextPiece(Piece.ActivePiece p)    { nextPiece       = p; }
    void setDifficultyActive(boolean b)       { difficultyActive = b; }
    void setDifficultyCount(int n)            { difficultyCount  = n; }
    void incrementDifficultyCount()           { difficultyCount++; }

    // ----------------------------------------------------------------
    // Completed lines and the screen-clear fill: the state the engine used
    // to keep in fields of its own, now with the rest of the game in progress.
    // ----------------------------------------------------------------

    /** Adds a row to the queue of completed rows, in arrival order. */
    void queueCompletedLine(int row) {
        completedLines.add(row);
    }

    /** true while some completed row is still waiting for its flash. */
    boolean hasCompletedLines() {
        return !completedLines.isEmpty();
    }

    /** The row whose flash is running: the one at the front of the queue. */
    int firstCompletedLine() {
        return completedLines.get(0);
    }

    /** Removes and returns the row at the front: its flash is over. */
    int takeFirstCompletedLine() {
        return completedLines.remove(0);
    }

    /** Drops every queued row (a restart). */
    void clearCompletedLines() {
        completedLines.clear();
    }

    /**
     * Moves the queued rows that were above 'removedRow' one row down, so a
     * multi-line clear keeps flashing the rows where they are now. A row below
     * the removed one does not move.
     */
    void shiftCompletedLinesAfterRemoval(int removedRow) {
        for (int i = 0; i < completedLines.size(); i++) {
            int row = completedLines.get(i);
            if (row < removedRow) completedLines.set(i, row + 1);
        }
    }

    /** Rows of the screen-clear fill already painted, from the top down. */
    int paintedRows() {
        return paintedRows;
    }

    /** Counts one more painted row of the screen-clear fill. */
    void advancePaintedRows() {
        paintedRows++;
    }

    /** Puts the screen-clear fill back at the top (a restart or a new screen). */
    void resetPaintedRows() {
        paintedRows = 0;
    }

    // ==================================================================
    // Public read contract (Renderer and Tetris shell)
    // ==================================================================

    /** true when the game has ended and is waiting for Restart. */
    boolean isGameOver() { return currentState == State.GAME_OVER; }

    /** true when the game is paused. */
    boolean isPaused()   { return paused; }

    /** Current score. */
    int points()         { return score; }

    /** Remaining lines needed to clear the current screen. */
    int linesLeft()      { return linesLeft; }

    /** The currently falling piece, or {@code null} between spawn and lock. */
    Piece.ActivePiece currentPiece() { return currentPiece; }

    /** The next piece shown in the preview box. */
    Piece.ActivePiece nextPiece()    { return nextPiece; }

    /** The playfield: cell values, edge flags and the hidden mask. */
    Board board()                    { return board; }

    /** {@code true} when difficulty blocks are active on this screen. */
    boolean difficultyActive()       { return difficultyActive; }

    /** Number of difficulty blocks spawned so far on this screen. */
    int difficultyCount()            { return difficultyCount; }

    // ==================================================================
    // Package-private state accessor (Engine switches on the state)
    // ==================================================================

    /** State of the machine the game is in now; only {@link Engine} reads it. */
    State currentState() { return currentState; }
}
