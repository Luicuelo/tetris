package tetris;

import java.util.Random;

/**
 * The game engine: rules, physics and scoring.
 * <p>
 * It is advanced one tick at a time by the shell's loop ({@code Tetris} +
 * {@link FrameTimer}, sixty ticks per second) and it does not paint anything.
 * The observable state is split across one object that the {@link Renderer}
 * reads directly:
 * <ul>
 *   <li>{@link GameState} — the playfield ({@link Board}), score, lines left,
 *       the two live pieces, the two flags the renderer needs
 *       ({@code isGameOver}, {@code isPaused}), the difficulty counters and the
 *       bookkeeping of the running phase (the queue of completed rows and the
 *       rows of the screen-clear fill already painted).</li>
 * </ul>
 * The engine is the only writer of {@link GameState}; the renderer only reads
 * it. {@link Effects} writes the hidden mask of the board as a decoration
 * detail, and it is created and driven from here.
 * <p>
 * There is no toolkit type in this class, so the rules can be created and tested
 * without a window: sound arrives as a {@link Sound} and the decorations live in
 * {@link Effects}.
 */
final class Engine {

    /**
     * Outcome of dropping the active piece one row. Returned by
     * {@link #dropPiece()} so {@link #applyGravity()} can read the result
     * without inspecting the state again.
     */
    private enum Drop {
        /** The piece moved down one row. */
        FELL,
        /** The piece locked and no line was completed. */
        LOCKED,
        /** The piece locked and completed at least one line. */
        LOCKED_CLEARING_LINES,
        /** The piece locked but there is no room left: game over. */
        GAME_OVER
    }

    /** Ticks between two automatic falls (0.3 s at 60 ticks per second). */
    private static final long TICKS_GRAVITY   = 18;
    /** Probability 1/n that a difficulty block is inserted after a drop. */
    private static final int  PROBABLE        = 4;
    /** Most difficulty blocks a screen may add. */
    private static final int  MAX_BLOCKS      = 5;
    /** Colours a difficulty block may take: 1..8, the first eight piece colours. */
    private static final int  BLOCK_COLORS    = 8;
    /** Default piece-set size (the classic 7); overridden per screen from levels.csv. */
    private static final int  NUM_PIECES      = 7;
    /** Points awarded for clearing 1, 2, 3 or 4 lines at once. */
    private static final int[] POINTS_PER_LINES    = {5, 15, 30, 60};
    /** Points awarded for each row painted by the screen-clear fill effect. */
    private static final int   POINTS_PER_FILL_ROW = 5;

    private final GameState     gameState = new GameState();
    private final Board         board     = gameState.board();
    private final Levels        levels;
    private final Sound         sound;
    private final Clock         clock     = new Clock();
    private final Clock.Counter gravity   = clock.newCounter(TICKS_GRAVITY);
    private final Effects       effects;

    private int     piecesInPlay = NUM_PIECES;
    /** Index (0-based) of the screen being played; -1 before the first advance. */
    private int     screenIndex  = -1;
    private boolean fastDropping = false;
    private final Random rng = new Random();

    /**
     * Creates the engine and starts it on screen 1.
     *
     * @param sound sound effects; {@link Sound#SILENT} runs the game muted
     */
    Engine(Sound sound) {
        this.sound   = sound;
        this.levels  = new Levels(board);
        this.effects = new Effects(board, sound, clock);
        levels.load();
        levels.validate();             // unusable data stops the game before it starts
        restart();
    }

    // ==================================================================
    // Observable state (read by the Tetris shell and the Renderer)
    // ==================================================================

    /**
     * The observable game state: the board, score, lines left, pieces and the
     * two flags the renderer needs. The renderer reads this; the engine writes it.
     */
    GameState gameState() { return gameState; }

    // ==================================================================
    // Entry points (player intentions and loop)
    // ==================================================================

    /**
     * Down key: enables fast-drop mode until the piece lands. Gravity
     * accelerates while the flag is on; the flag clears itself when the piece
     * locks.
     */
    void fastDrop() {
        if (!gameState.isPaused()) fastDropping = true;
    }

    /** P key: toggles pause (the colours darken while paused). */
    void togglePause() {
        gameState.setPaused(!gameState.isPaused());
    }

    /** Restarts the game from screen 1. */
    void restart() {
        gameState.setCurrentPiece(null);
        gameState.setNextPiece(null);
        gameState.setPaused(false);
        gameState.setCurrentState(GameState.State.FALLING);
        gameState.setScore(0);
        gameState.clearCompletedLines();
        gameState.resetPaintedRows();
        fastDropping = false;
        screenIndex  = -1;              // advanceScreen() moves on to the first one
        clock.reset();
        effects.reset();

        advanceScreen();                // the first screen: obstacles, lines, piece set
        gameState.setNextPiece(createPiece(randomType()));
        spawnPiece();
    }

    /**
     * Advances the game one tick (called sixty times per second by the shell).
     * Does nothing while paused.
     */
    void tick() {
        if (gameState.isPaused()) return;
        clock.tick();
        // The blink outlives the fall that started it, so it ticks in every state.
        effects.tickBlockBlink();
        switch (gameState.currentState()) {
            case FALLING            -> applyGravity();
            case COMPLETED_LINE     -> tickCompletedLines();
            case SCREEN_TRANSITION  -> tickScreenTransition();
            case GAME_OVER          -> effects.tickGameOverFade();
        }
    }

    /**
     * Brightness of the game-over darkening, from full light to black.
     * <p>
     * The curve belongs to {@link Effects}; this body is the only place that
     * knows it. The shell reads it once per picture and hands the value to the
     * renderer, so moving the curve to another owner changes one line here and
     * neither the shell nor the renderer.
     */
    double fadeProgress() {
        return effects.fadeProgress();
    }

    // ==================================================================
    // Movement
    // ==================================================================

    /**
     * Moves the active piece one column to the side.
     * Try / validate / undo: the position is changed, checked against the board
     * and restored if the move is not possible. A paused game ignores the move.
     *
     * @param d -1 = left, +1 = right
     * @return {@code true} if the piece moved
     */
    boolean moveLateral(int d) {
        Piece.ActivePiece p = gameState.currentPiece();
        if (p == null || d == 0 || gameState.isPaused()) return false;
        int saved = p.getPosX();
        p.setPosX(saved + Integer.signum(d));
        if (p.fitsAt(board)) return true;
        p.setPosX(saved);               // the move is not possible: undo it
        return false;
    }

    /**
     * Rotates the active piece one quarter turn.
     * Same try / validate / undo pattern as {@link #moveLateral(int)}.
     *
     * @param r +1 = clockwise, -1 = counter-clockwise
     * @return {@code true} if the piece rotated
     */
    boolean moveRotate(int r) {
        Piece.ActivePiece p = gameState.currentPiece();
        if (p == null || r == 0 || gameState.isPaused()) return false;
        int saved = p.getState();
        if (r > 0) p.nextState();
        else       p.prevState();
        if (p.fitsAt(board)) return true;
        p.setState(saved);              // the turn is not possible: undo it
        return false;
    }

    // ==================================================================
    // Gravity
    // ==================================================================

    /**
     * Applies gravity: drops the active piece one row when its period ends, or
     * all the way down while fast-drop is on.
     */
    private void applyGravity() {
        if (!gravity.trigger()) return;
        if (fastDropping) {
            while (gameState.currentPiece() != null && dropPiece() == Drop.FELL) { }
            fastDropping = false;
        } else if (gameState.currentPiece() != null) {
            dropPiece();
        }
        gravity.start();    // this fall closes the period: the next one begins here
    }

    /**
     * Drops the active piece one row.
     *
     * @return the outcome: {@link Drop#FELL}, {@link Drop#LOCKED},
     *         {@link Drop#LOCKED_CLEARING_LINES} or {@link Drop#GAME_OVER}
     */
    private Drop dropPiece() {
        Piece.ActivePiece p = gameState.currentPiece();
        p.setPosY(p.getPosY() + 1);
        int posY = p.getPosY();

        // Fast-path: if the piece is still far from the top row, skip collision
        if (posY + Piece.SIZE < board.topRow() || p.fitsAt(board)) {
            gameState.nextPiece().nextState();  // preview piece spins while waiting
            return Drop.FELL;
        }

        // Piece cannot move down: lock it into the board
        p.setPosY(posY - 1);
        lockPiece(p);
        fastDropping = false;

        // The game ends only when the lock reaches the top row: pieces enter
        // above the board, so the stack has to climb to the last row and the
        // playfield is never left with an empty row on top.
        if (board.topRow() < 1) {
            gameState.setCurrentPiece(null);
            gameState.setCurrentState(GameState.State.GAME_OVER);
            effects.startGameOverFade();
            return Drop.GAME_OVER;
        }

        sound.drop();

        int baseRow  = p.getPosY();
        int numLines = 0;
        for (int r = Piece.SIZE - 1; r >= 0; r--) {
            if (board.isLineComplete(baseRow + r)) {
                gameState.queueCompletedLine(baseRow + r);
                numLines++;
            }
        }

        if (numLines == 0) {
            gameState.addScore(1);
            spawnPiece();
            return Drop.LOCKED;
        }

        gameState.addScore(POINTS_PER_LINES[numLines - 1]);
        gameState.setCurrentPiece(null);
        gameState.setCurrentState(GameState.State.COMPLETED_LINE);
        effects.startLineFlash(gameState.firstCompletedLine());
        return Drop.LOCKED_CLEARING_LINES;
    }

    // ==================================================================
    // Lines and pieces
    // ==================================================================

    /**
     * Locks the piece into the board matrix and spawns a difficulty block.
     * A cell above the top row is not stored: the board has no cell there, and
     * {@link Board#inBounds} is the only gate that decides what can be written.
     */
    private void lockPiece(Piece.ActivePiece p) {
        p.forEachCell((row, col, value, edgeBits) -> {
            int boardRow = row + p.getPosY();
            int boardCol = col + p.getPosX();
            if (!board.inBounds(boardRow, boardCol)) return true;
            board.setCell(boardRow, boardCol, value, edgeBits);
            return true;
        });
        spawnDifficulty();
    }

    /**
     * Creates a new piece of {@code type}, centred on the board and anchored so
     * its lowest filled cell is the first row above the playfield: every piece
     * enters the board with the next fall, whatever its height or rotation.
     * <p>
     * Appearing inside the board would let the piece start over a cell that is
     * already occupied: the first fall would undo the step and lock it right
     * there, and the lock would write over the cell it landed on, inflating the
     * row counter too. From here the piece only ever reaches positions that
     * {@link Piece.ActivePiece#fitsAt(Board)} accepted.
     */
    private Piece.ActivePiece createPiece(Piece.Type type) {
        Piece.ActivePiece p = new Piece.ActivePiece(type);
        p.setPosX(board.cols() / 2);
        p.setPosY(p.spawnPosY());
        return p;
    }

    /** Draws one of the piece types allowed on this screen. */
    private Piece.Type randomType() {
        return Piece.Type.at(rng.nextInt(piecesInPlay));
    }

    /** {@code true} if {@code p} is allowed on the current screen. */
    private boolean allowed(Piece.ActivePiece p) {
        return p == null || p.type().allowedIn(piecesInPlay);
    }

    /**
     * A new screen may allow fewer piece types. If the preview was drawn with
     * the previous set, a forbidden piece is replaced with one from the new set.
     */
    private void replaceForbiddenPieces() {
        if (!allowed(gameState.nextPiece())) {
            gameState.setNextPiece(createPiece(randomType()));
        }
    }

    /** Promotes nextPiece to currentPiece and generates a new nextPiece. */
    private void spawnPiece() {
        gameState.setCurrentPiece(gameState.nextPiece());
        gameState.setNextPiece(createPiece(randomType()));
        gravity.start();    // a new piece gets a full period before falling
    }

    // ==================================================================
    // Completed lines: the flash is in Effects, the removal is here
    // ==================================================================

    private void tickCompletedLines() {
        if (effects.tickLineFlash() != Effects.Step.FINISHED) return;

        int row = gameState.takeFirstCompletedLine();
        board.removeRow(row);
        gameState.shiftCompletedLinesAfterRemoval(row);
        sound.line();
        gameState.decrementLinesLeft();

        if (!gameState.hasCompletedLines()) {
            if (gameState.linesLeft() == 0) {
                gameState.setCurrentState(GameState.State.SCREEN_TRANSITION);
                gameState.resetPaintedRows();
                effects.startFill(gameState.paintedRows());
            } else {
                gameState.setCurrentState(GameState.State.FALLING);
                fastDropping = false;
                spawnPiece();
            }
        } else {
            effects.startLineFlash(gameState.firstCompletedLine());
        }
    }

    // ==================================================================
    // Screen transition: one row per run in Effects, the walk here
    // ==================================================================

    private void tickScreenTransition() {
        if (effects.tickScreenFill() == Effects.Step.WAITING) return;
        gameState.addScore(POINTS_PER_FILL_ROW);

        gameState.advancePaintedRows();
        if (gameState.paintedRows() < board.topRow()) {
            effects.startFill(gameState.paintedRows());
            return;
        }
        advanceScreen();
        gameState.setCurrentState(GameState.State.FALLING);
        fastDropping = false;
        spawnPiece();
    }

    // ==================================================================
    // Screens
    // ==================================================================

    /**
     * Moves on to the next screen (wrapping around after the last one) and
     * applies what that screen asks for: its obstacles, the lines to clear, the
     * difficulty and the piece set.
     * <p>
     * The level arrives as one object, so the method reads it by name instead
     * of asking the repository four times for the same index.
     */
    private void advanceScreen() {
        screenIndex = (screenIndex + 1) % levels.size();
        Level level = levels.get(screenIndex);

        loadScreen(level);
        gameState.setLinesLeft(level.linesRequired());
        gameState.setDifficultyActive(level.difficultyActive());
        gameState.setDifficultyCount(0);        // reset the per-screen cap
        piecesInPlay = level.numPieces();
        replaceForbiddenPieces();               // the preview may belong to the old set
    }

    /** Resets the board and places the obstacles of 'level'. */
    private void loadScreen(Level level) {
        board.clear();
        level.fill(board);
    }

    // ==================================================================
    // Difficulty blocks
    // ==================================================================

    /**
     * After locking a piece, randomly inserts an extra obstacle block on the
     * topmost row (difficulty screens only) and blinks it in.
     */
    private void spawnDifficulty() {
        if (!gameState.difficultyActive()) return;
        if (gameState.difficultyCount() >= MAX_BLOCKS) return;
        if (rng.nextInt(PROBABLE) != 0) return;
        int row = board.topRow();
        if (board.fillCount(row) >= board.cols() - 1) return;

        int col = rng.nextInt(board.cols());
        while (board.valueAt(row, col) != 0) col = rng.nextInt(board.cols());

        board.place(row, col, rng.nextInt(BLOCK_COLORS) + 1);
        gameState.incrementDifficultyCount();
        effects.appearBlock(row, col);
        sound.block();
    }
}
