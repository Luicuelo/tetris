package tetris;

import java.util.Random;

/**
 * The game engine: rules, physics and scoring.
 * <p>
 * It is advanced one tick at a time by the shell's loop ({@code Tetris} +
 * {@link Clock.FrameTimer}, sixty ticks per second) and it does not paint
 * anything. The observable state lives in one {@link GameState}: the playfield
 * ({@link Board}), score, lines left, the active piece and the waiting one, the
 * two flags the renderer needs ({@code isGameOver}, {@code isPaused}), the
 * difficulty counters and the queue of completed rows.
 * <p>
 * The engine is the only writer of {@link GameState}; the renderer only reads
 * it. {@link Effects} writes the hidden mask of the board as a decoration
 * detail, and it is created and driven from here.
 * <p>
 * No JavaFX type appears in this class: sound arrives as a {@link Sound} and
 * the decorations live in {@link Effects}.
 */
final class Engine {

    /**
     * Outcome of dropping the active piece one row, as read by
     * {@link #applyGravity()}.
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
    private static final long TICKS_GRAVITY         = 18;
    /** One landed piece in DIFFICULTY_ONE_IN gets a difficulty block (1 in 4). */
    private static final int  DIFFICULTY_ONE_IN     = 4;
    /** Most difficulty blocks a level may add. */
    private static final int  MAX_DIFFICULTY_BLOCKS = 5;
    /** Colours a difficulty block may take: 1..8, the first eight piece colours. */
    private static final int  DIFFICULTY_COLORS     = 8;
    /** Default piece-set size (the classic 7); overridden per level from levels.csv. */
    private static final int  NUM_PIECES            = 7;
    /** Score awarded for clearing 1, 2, 3 or 4 lines at once. */
    private static final int[] SCORE_PER_LINE_SET  = {5, 15, 30, 60};
    /** Score awarded for each row of the transition fill. */
    private static final int   SCORE_PER_FILL_ROW  = 5;

    private final GameState     gameState = new GameState();
    private final Board         board     = gameState.board();
    private final Levels        levels;
    private final Sound         sound;
    private final Clock         clock     = new Clock();
    private final Clock.Counter gravity   = clock.newCounter(TICKS_GRAVITY);
    private final Effects       effects;

    private int     piecesInPlay = NUM_PIECES;
    /** Index (0-based) of the level being played; -1 before the first advance. */
    private int     levelIndex   = -1;
    /** Board row of the transition fill in progress: the next one to fill. */
    private int     fillRow      = 0;
    private boolean fastDropping = false;
    private final Random rng = new Random();

    /**
     * Creates the engine and starts it on level 1.
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

    /** Restarts the game from level 1. */
    void restart() {
        gameState.setActivePiece(null);
        gameState.setNextPiece(null);
        gameState.setPaused(false);
        gameState.setCurrentState(GameState.State.FALLING);
        gameState.setScore(0);
        gameState.completedLines().clear();
        fastDropping = false;
        levelIndex   = -1;              // advanceLevel() moves on to the first one
        clock.reset();
        effects.reset();

        advanceLevel();                 // the first level: obstacles, lines, piece set
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
        // The cell blink outlives the fall that started it, so it ticks in every state.
        effects.tickCellBlink();
        switch (gameState.currentState()) {
            case FALLING            -> applyGravity();
            case COMPLETED_LINE     -> tickCompletedLines();
            case LEVEL_TRANSITION   -> tickLevelTransition();
            case GAME_OVER          -> effects.tickGameOverFade();
        }
    }

    /**
     * Brightness of the game-over darkening, from full light to black; the curve
     * lives in {@link Effects}. The shell reads it once per picture and hands the
     * value to the renderer.
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
        Piece.ActivePiece p = gameState.activePiece();
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
        Piece.ActivePiece p = gameState.activePiece();
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
            while (gameState.activePiece() != null && dropPiece() == Drop.FELL) { }
            fastDropping = false;
        } else if (gameState.activePiece() != null) {
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
        Piece.ActivePiece p = gameState.activePiece();
        int pieceRow    = p.getPosY();  // it is both the undo target and the lock row
        int newPieceRow = pieceRow + 1;
        p.setPosY(newPieceRow);

        // Fast-path: the whole piece is above the first occupied row, skip collision
        if (newPieceRow + Piece.SIZE < board.firstOccupiedRow() || p.fitsAt(board)) {
            gameState.nextPiece().nextState();  // preview piece spins while waiting
            return Drop.FELL;
        }

        // Piece cannot move down: back to its row and lock it there
        p.setPosY(pieceRow);
        lockPiece(p);
        fastDropping = false;

        // The game ends only when the lock reaches row 0: pieces enter above
        // the board, so the stack has to climb to the last row and the
        // playfield is never left with an empty row on top.
        if (board.firstOccupiedRow() < 1) {
            gameState.setActivePiece(null);
            gameState.setCurrentState(GameState.State.GAME_OVER);
            effects.startGameOverFade();
            return Drop.GAME_OVER;
        }

        sound.drop();

        int numLines = 0;
        for (int r = Piece.SIZE - 1; r >= 0; r--) {
            if (board.isLineComplete(pieceRow + r)) {
                gameState.completedLines().queue(pieceRow, r);
                numLines++;
            }
        }

        if (numLines == 0) {
            gameState.addScore(1);
            spawnPiece();
            return Drop.LOCKED;
        }

        gameState.addScore(SCORE_PER_LINE_SET[numLines - 1]);
        gameState.setActivePiece(null);
        gameState.setCurrentState(GameState.State.COMPLETED_LINE);
        effects.startLineBlink(gameState.completedLines().nextRow());
        return Drop.LOCKED_CLEARING_LINES;
    }

    // ==================================================================
    // Lines and pieces
    // ==================================================================

    /**
     * Locks the piece into the board matrix and spawns a difficulty block.
     * A cell above row 0 is not stored: the board has no cell there, and
     * {@link Board#inBounds} is the only gate that decides what can be written.
     */
    private void lockPiece(Piece.ActivePiece p) {
        p.forEachFilledCell((row, col, value, edgeBits) -> {
            int boardRow = row + p.getPosY();
            int boardCol = col + p.getPosX();
            if (!board.inBounds(boardRow, boardCol)) return true;
            board.setCell(boardRow, boardCol, value, edgeBits);
            return true;
        });
        spawnDifficulty();
    }

    /**
     * Creates a new piece of {@code type}, centred on the board and positioned so
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

    /** Draws one of the piece types allowed on this level. */
    private Piece.Type randomType() {
        return Piece.Type.at(rng.nextInt(piecesInPlay));
    }

    /** {@code true} if {@code p} is allowed on the current level. */
    private boolean allowed(Piece.ActivePiece p) {
        return p == null || p.type().allowedIn(piecesInPlay);
    }

    /**
     * A new level may allow fewer piece types. If the preview was drawn with
     * the previous set, a forbidden piece is replaced with one from the new set.
     */
    private void replaceForbiddenPieces() {
        if (!allowed(gameState.nextPiece())) {
            gameState.setNextPiece(createPiece(randomType()));
        }
    }

    /** Promotes the waiting piece to the active one and generates a new waiting piece. */
    private void spawnPiece() {
        gameState.setActivePiece(gameState.nextPiece());
        gameState.setNextPiece(createPiece(randomType()));
        gravity.start();    // a new piece gets a full period before falling
    }

    // ==================================================================
    // Completed lines: the blink is in Effects, the removal is here
    // ==================================================================

    private void tickCompletedLines() {
        if (!effects.tickLineBlink()) return;

        GameState.CompletedLines completedLines = gameState.completedLines();
        int row = completedLines.takeNextRow();
        board.removeRow(row);
        completedLines.advancePieceRow();   // the rows above fell one: the queue follows
        sound.line();
        gameState.decrementLinesLeft();

        if (completedLines.isEmpty()) {
            if (gameState.linesLeft() == 0) {
                gameState.setCurrentState(GameState.State.LEVEL_TRANSITION);
                fillRow = 0;
                effects.startTransitionFill(fillRow);
            } else {
                gameState.setCurrentState(GameState.State.FALLING);
                fastDropping = false;
                spawnPiece();
            }
        } else {
            effects.startLineBlink(completedLines.nextRow());
        }
    }

    // ==================================================================
    // Level transition: one row per run in Effects, the walk here
    // ==================================================================

    private void tickLevelTransition() {
        if (!effects.tickTransitionFill()) return;
        gameState.addScore(SCORE_PER_FILL_ROW);

        fillRow++;
        if (fillRow < board.firstOccupiedRow()) {
            effects.startTransitionFill(fillRow);
            return;
        }
        advanceLevel();
        gameState.setCurrentState(GameState.State.FALLING);
        fastDropping = false;
        spawnPiece();
    }

    // ==================================================================
    // Levels
    // ==================================================================

    /**
     * Moves on to the next level (wrapping around after the last one) and
     * applies what that level asks for: its obstacles, the lines to clear, the
     * difficulty and the piece set.
     * <p>
     * The level arrives as one object, so the method reads it by name instead
     * of asking the repository four times for the same index.
     */
    private void advanceLevel() {
        levelIndex = (levelIndex + 1) % levels.size();
        Levels.Level level = levels.get(levelIndex);

        loadLevel(level);
        gameState.setLinesLeft(level.linesRequired());
        gameState.setDifficultyActive(level.difficultyActive());
        gameState.setDifficultyCount(0);        // reset the per-level cap
        piecesInPlay = level.numPieces();
        replaceForbiddenPieces();               // the preview may belong to the old set
    }

    /** Resets the board and places the obstacles of {@code level}. */
    private void loadLevel(Levels.Level level) {
        board.clear();
        level.fill(board);
    }

    // ==================================================================
    // Difficulty blocks
    // ==================================================================

    /**
     * After locking a piece, randomly inserts an extra obstacle block on the
     * first occupied row (difficulty levels only) and blinks it in.
     * <p>
     * The column is drawn among the empty cells instead of drawn and retried,
     * so a crowded row costs one walk. A row with a single hole left gets
     * nothing: filling it would seal the row, and no piece can complete it
     * again, so the block waits for the next lock.
     */
    private void spawnDifficulty() {
        if (!gameState.difficultyActive()) return;
        if (gameState.difficultyCount() >= MAX_DIFFICULTY_BLOCKS) return;
        if (rng.nextInt(DIFFICULTY_ONE_IN) != 0) return;

        int row  = board.firstOccupiedRow();
        int free = board.cols() - board.occupiedCount(row);
        if (free < 2) return;

        int pick = rng.nextInt(free);      // which empty cell, counted from the left
        int col  = 0;
        while (true) {
            if (board.valueAt(row, col) == 0 && pick-- == 0) break;
            col++;
        }

        board.place(row, col, rng.nextInt(DIFFICULTY_COLORS) + 1);
        gameState.incrementDifficultyCount();
        effects.startCellBlink(row, col);
        sound.block();
    }
}
