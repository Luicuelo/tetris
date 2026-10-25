package tetris;

import java.util.ArrayList;
import java.util.List;

/**
 * The state of a game in progress: what the {@link Renderer} reads and what the
 * {@link Engine} keeps between ticks. No rules live here — only the data and the
 * bookkeeping that the engine performs on it.
 * <p>
 * Every field is private and every mutator is package-private: {@link Engine} is
 * the only writer, and the {@link Renderer} reads through the accessors.
 * <p>
 * The state of the machine ({@code FALLING}, {@code COMPLETED_LINE}, etc.) is
 * read by {@link Engine} alone; the renderer only gets the two booleans
 * {@link #isGameOver()} and {@link #isPaused()}.
 * <p>
 * Brightness is not part of the state: the renderer derives it from those two
 * booleans and from {@link Effects#fadeProgress()}.
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
        /** A completed line is blinking; it is removed when the blink ends. */
        COMPLETED_LINE,
        /** The board is filled with the transition colour before the next level. */
        LEVEL_TRANSITION,
        /** The screen darkens and the game waits for Restart. */
        GAME_OVER
    }

    private final Board          board           = new Board();
    private State                currentState    = State.FALLING;
    private boolean              paused          = false;
    private int                  score           = 0;
    private int                  linesLeft       = 0;
    private Piece.ActivePiece    activePiece     = null;
    private Piece.ActivePiece    nextPiece       = null;
    private boolean              difficultyActive = false;
    private int                  difficultyCount  = 0;

    /** Completed rows waiting for their blink, as rows of their piece's box. */
    private final CompletedLines completedLines   = new CompletedLines();

    // ==================================================================
    // Package-private mutators (Engine is the only writer)
    // ==================================================================

    void setCurrentState(State s)              { currentState     = s; }
    void setPaused(boolean p)                  { paused           = p; }
    void setScore(int s)                       { score            = s; }
    void addScore(int delta)                   { score           += delta; }
    void setLinesLeft(int l)                   { linesLeft        = l; }
    void decrementLinesLeft()                  { if (linesLeft > 0) linesLeft--; }
    void setActivePiece(Piece.ActivePiece p)   { activePiece      = p; }
    void setNextPiece(Piece.ActivePiece p)     { nextPiece        = p; }
    void setDifficultyActive(boolean b)        { difficultyActive = b; }
    void setDifficultyCount(int n)             { difficultyCount  = n; }
    void incrementDifficultyCount()            { difficultyCount++; }

    // ----------------------------------------------------------------
    // Completed lines
    // ----------------------------------------------------------------

    /**
     * The rows waiting for their blink, with the board row they are counted
     * from. The engine queues, blinks and collapses through this object, so the
     * state does not repeat one method per operation.
     */
    CompletedLines completedLines() {
        return completedLines;
    }

    // ==================================================================
    // Public read contract (Renderer and Tetris shell)
    // ==================================================================

    /** true when the game has ended and is waiting for Restart. */
    boolean isGameOver() { return currentState == State.GAME_OVER; }

    /** true when the game is paused. */
    boolean isPaused()   { return paused; }

    /** Current score. */
    int score()          { return score; }

    /** Remaining lines needed to clear the current level. */
    int linesLeft()      { return linesLeft; }

    /** The active piece, or {@code null} between spawn and lock. */
    Piece.ActivePiece activePiece() { return activePiece; }

    /** The next piece shown in the preview box. */
    Piece.ActivePiece nextPiece()    { return nextPiece; }

    /** The playfield: cell values, edge flags and the hidden mask. */
    Board board()                    { return board; }

    /** {@code true} when difficulty blocks are active on this level. */
    boolean difficultyActive()       { return difficultyActive; }

    /** Number of difficulty blocks spawned so far on this level. */
    int difficultyCount()            { return difficultyCount; }

    // ==================================================================
    // Package-private state accessor (Engine switches on the state)
    // ==================================================================

    /** State of the machine the game is in now; only {@link Engine} reads it. */
    State currentState() { return currentState; }

    // ==================================================================
    // The queue of completed rows
    // ==================================================================

    /**
     * The rows of the last lock that completed, as rows of the piece's box
     * (0..3) plus the board row that box was placed at.
     * <p>
     * The rows are relative on purpose: {@link Board#removeRow} shifts every row
     * above the cleared one, so an absolute board row stops pointing at its cells
     * after a collapse, while the rows of a box keep their distance. A collapse
     * therefore moves {@code pieceRow} one step instead of rewriting the queue.
     * <p>
     * The blink always takes the lowest queued row, so the rest lie above the
     * removed one and one step keeps them all true. A lock starts with an empty
     * queue, so {@code pieceRow} always belongs to the batch in progress.
     */
    static final class CompletedLines {

        /** Board row the piece's box starts at; it follows the rows as they fall. */
        private int pieceRow;
        /**
         * Rows of that box that completed, in the order they are blinked: the box
         * is scanned from its bottom row up, so the lowest row of the piece comes
         * first.
         */
        private final List<Integer> boxRows = new ArrayList<>();

        /**
         * Queues row {@code boxRow} of the box of a piece that sat at board row
         * {@code boardRow}.
         */
        void queue(int boardRow, int boxRow) {
            pieceRow = boardRow;
            boxRows.add(boxRow);
        }

        /** true while some row is still waiting for its blink. */
        boolean isEmpty() {
            return boxRows.isEmpty();
        }

        /** Board row the next blink works on: the lowest queued one. */
        int nextRow() {
            return pieceRow + boxRows.get(0);
        }

        /** Takes that row out of the queue and returns it. */
        int takeNextRow() {
            return pieceRow + boxRows.remove(0);
        }

        /**
         * One queued row was removed below: everything above it, the piece's row
         * included, sits one step lower.
         */
        void advancePieceRow() {
            pieceRow++;
        }

        /** Drops every queued row (a restart). */
        void clear() {
            boxRows.clear();
        }
    }
}
