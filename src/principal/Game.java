package principal;

import java.util.ArrayList;
import java.util.Random;

/**
 * The game: rules, state and score.
 * <p>
 * It is advanced one tick at a time by the shell's loop ({@code Tetris} +
 * {@link FrameTimer}, sixty ticks per second) and it does not paint anything:
 * the Renderer reads it through accessors.
 * <p>
 * There is no toolkit type in this class, so the rules can be created and
 * tested without a window: the sound arrives as a {@link Sound} and the
 * animations that are only decoration live in {@link Effects}.
 */
class Game {

    // ------------------------------------------------------------------
    // Game states
    // ------------------------------------------------------------------
    private static final int STATE_PLAY  = 0;   // normal: falling pieces
    private static final int STATE_LINE  = 1;   // completed line flashing
    private static final int STATE_TRANS = 2;   // level transition effect
    private static final int STATE_END   = 3;   // game over (darkening)

    /** Probability 1/n that a difficulty block is inserted after a drop. */
    private static final int PROBABLE = 4;
    /** Maximum difficulty blocks per screen. */
    private static final int MAX_DIFFICULTY = 5;
    private static final int NUM_PIECES = 7;  // default, overridden per screen from levels.csv
    /** Points awarded for clearing 1, 2, 3 or 4 lines at once. */
    private static final int[] POINTS_PER_LINES = {5, 15, 30, 60};
    /** Points awarded for each row painted by the screen-clear effect. */
    private static final int POINTS_PER_FILL_ROW = 5;
    /** Brightness of the colours while the game is paused. */
    private static final double PAUSE_DIM = 0.55;
    /** Brightness factor applied at every step of the game-over fade. */
    private static final double FADE_DIM = 0.55;

    /**
     * The playfield: owns the geometry (rows/cols) and the cell state
     * (values, edge flags, row fill and topmost occupied row). Declared before
     * 'levels' because the loader validates the obstacles against its size.
     */
    private final Board board = new Board();

    /** Level definitions loaded from levels.csv + levels/level_N.txt at startup. */
    private final Levels levels = new Levels(board);
    /** Sound effects, injected by the application so the rules stay toolkit-free. */
    private final Sound sound;
    /** Ticks of every event: gravity, line flash, fill and fade. */
    private final Clock clock = new Clock();
    /** The decorations: line flash, screen-clear fill and game-over fade. */
    private final Effects effects;

    private Piece.Active currentPiece = null;
    private Piece.Active nextPiece    = null;

    private int score = 0;
    /** Remaining lines needed to advance to the next screen. */
    private int linesLeft = 0;
    /**
     * How many piece types may appear on this screen: the draw is limited to the
     * first 'piecesInPlay' of the enum, so 7 means the classic set.
     */
    private int piecesInPlay = NUM_PIECES;

    private int  gameState = STATE_PLAY;
    private boolean paused = false;

    // Difficulty: random obstacle blocks inserted on screen 1
    private boolean difficultyActive = false;
    private int     difficultyCount  = MAX_DIFFICULTY;
    /** [row, col] of each inserted difficulty block. */
    private final int[][] difficultyBlocks = new int[MAX_DIFFICULTY][2];
    /** Current screen index 1..8. Starts at count so first increment wraps to 1. */
    private int currentScreen;

    // Completed lines waiting for their flash, processed in arrival order
    private final ArrayList<Integer> pendingLines = new ArrayList<>();

    private boolean fastDropping = false;

    private final Random rng = new Random();

    /**
     * Creates the game and starts it on screen 1.
     *
     * @param sound sound effects; {@link Sound#SILENT} runs the game muted
     */
    Game(Sound sound) {
        this.sound = sound;
        this.effects = new Effects(board, sound, clock);
        levels.load();
        currentScreen = levels.count();
        restart();
    }

    // ==================================================================
    // Accessors for the Renderer
    // ==================================================================

    /** Board height in rows (the game just forwards the board's own size). */
    int boardRows() {
        return board.rows();
    }

    /** Board width in columns. */
    int boardCols() {
        return board.cols();
    }

    /** Value of a board cell (1..10, or 0 if empty); a blinking block reads 0. */
    int cellValue(int row, int col) {
        if (effects.hidden(row, col)) {
            return 0;              // hidden from the picture, still on the board
        }
        return board.valueAt(row, col);
    }

    /** Edge mask of a board cell (see {@link Edges}). */
    int edgeValue(int row, int col) {
        return board.edgeAt(row, col);
    }

    /** The currently falling piece (may be null). */
    Piece.Active currentPiece() {
        return currentPiece;
    }

    /** The next piece shown in the preview box. */
    Piece.Active nextPiece() {
        return nextPiece;
    }

    /** Current score. */
    int points() {
        return score;
    }

    /** Remaining lines needed to clear the current screen. */
    int linesLeft() {
        return linesLeft;
    }

    /** Palette darkening factor: 0..1 (pause = 0.55, game over darkens further). */
    double dimFactor() {
        if (gameState == STATE_END) {
            return Math.pow(FADE_DIM, effects.fadeStep());
        }
        if (paused) {
            return PAUSE_DIM;
        }
        return 1.0;
    }

    /** true when the game has ended and is waiting for Restart. */
    boolean finished() {
        return gameState == STATE_END;
    }

    /** true when the game is paused. */
    boolean isPaused() {
        return paused;
    }

    // ==================================================================
    // Entry points (player intentions and loop)
    // ==================================================================

    /**
     * Down key: enables fast-drop mode until the piece lands. This is the
     * soft drop of Tetris: gravity accelerates while the flag is on, and the
     * flag clears itself the moment the piece locks.
     */
    void fastDrop() {
        if (!paused) fastDropping = true;
    }

    /** P key: toggles pause (the colours dim while paused). */
    void togglePause() {
        paused = !paused;
    }

    /** Restarts the game from screen 1. */
    void restart() {
        currentPiece  = null;
        nextPiece     = null;
        paused        = false;
        gameState     = STATE_PLAY;
        score         = 0;
        pendingLines.clear();
        fastDropping  = false;
        clock.reset();
        effects.reset();
        currentScreen = levels.count();  // incremented to 1 on first call

        // Screen 1 first: it sets the piece set the two pieces come from
        linesLeft = advanceScreen();
        nextPiece = createPiece(randomType());
        spawnPiece();

        difficultyCount  = MAX_DIFFICULTY;
    }

    /** Advances the game one tick (called sixty times per second by the shell's loop). */
    void tick() {
        if (paused) return;
        clock.tick();
        effects.tickBlink();   // the blink outlives the state that started it
        switch (gameState) {
            case STATE_PLAY:  countDown();      break;
            case STATE_LINE:  tickLines();      break;
            case STATE_TRANS: tickTransition(); break;
            case STATE_END:   effects.tickFade(); break;
            default: break;
        }
    }

    // ==================================================================
    // Mechanics of movement
    // ==================================================================

    /**
     * Moves the active piece one column to the side.
     * <p>
     * Try / validate / undo: the position is changed, checked against the
     * board and restored if the move is not possible. A paused game ignores
     * the movement instead of queueing it.
     *
     * @param d -1 = left, +1 = right
     * @return true if the piece moved
     */
    boolean moveLateral(int d) {
        if (paused || d == 0 || currentPiece == null) return false;
        int saved = currentPiece.getPosX();
        currentPiece.setPosX(saved + (d > 0 ? 1 : -1));
        if (!currentPiece.fitsAt(board)) {
            currentPiece.setPosX(saved);
            return false;
        }
        return true;
    }

    /**
     * Rotates the active piece one quarter turn.
     * <p>
     * Same try / validate / undo pattern as {@link #moveLateral(int)}: the
     * rotation is applied, checked and rolled back if the piece does not fit.
     * The four states are generated clockwise, so {@code +1} turns the piece to
     * the right and {@code -1} to the left, like {@code moveLateral}.
     *
     * @param r +1 = right turn (clockwise), -1 = left turn (counter-clockwise)
     * @return true if the piece rotated
     */
    boolean moveRotate(int r) {
        if (paused || r == 0 || currentPiece == null) return false;
        int saved = currentPiece.getState();
        if (r > 0) currentPiece.nextState();
        else       currentPiece.prevState();
        if (!currentPiece.fitsAt(board)) {
            currentPiece.setState(saved);
            return false;
        }
        return true;
    }

    /** Drops the active piece when the clock says it is time. */
    private void countDown() {
        if (!clock.drop.trigger()) {
            return;
        }
        if (fastDropping) {
            while (currentPiece != null && dropPiece() == 1) {}
            fastDropping = false;
        } else if (currentPiece != null) {
            dropPiece();
        }
        clock.drop.start();   // this drop closes the period: the next one begins here
    }

    /**
     * Drops the active piece one row.
     * Returns: 1=dropped ok, 0=locked (no lines), -1=locked (lines cleared),
     * -2=game over (piece locked above the board).
     */
    private int dropPiece() {
        currentPiece.setPosY(currentPiece.getPosY() + 1);
        int posY = currentPiece.getPosY();

        // Fast-path: if the piece is still far from the top row, skip collision
        if (posY + Piece.SIZE < board.topRow() || currentPiece.fitsAt(board)) {
            nextPiece.nextState();  // preview piece spins while waiting
            return 1;
        }

        // Piece cannot move down: lock it into the board
        currentPiece.setPosY(posY - 1);
        copyPiece(currentPiece);
        fastDropping = false;

        if (board.topRow() <= 1) {
            // No space left: game over
            currentPiece = null;
            System.out.println("GAME OVER");
            gameState = STATE_END;
            effects.startFade();
            return -2;
        }

        sound.drop();

        // Check the rows the piece occupies for completed lines
        int baseRow  = currentPiece.getPosY();
        int numLines = 0;
        for (int a = Piece.SIZE - 1; a >= 0; a--) {
            if (board.isLineComplete(baseRow + a)) {
                pendingLines.add(baseRow + a);
                numLines++;
            }
        }

        if (numLines == 0) {
            score++;
            spawnPiece();
            return 0;
        }

        score += POINTS_PER_LINES[numLines - 1];
        // Start the flash, one line at a time
        currentPiece = null;
        gameState    = STATE_LINE;
        effects.flashLine(pendingLines.get(0));
        return -1;
    }

    // ==================================================================
    // Lines and pieces
    // ==================================================================

    /** Locks the piece into the board matrix. */
    private void copyPiece(Piece.Active p) {
        p.forEachCell((row, col, value, edgeBits) -> {
            int boardRow = row + p.getPosY();
            int boardCol = col + p.getPosX();
            if (outOfBounds(boardRow, boardCol)) {
                return true;                    // outside: skip, keep visiting
            }
            board.setCell(boardRow, boardCol, value, edgeBits);
            return true;
        });
        spawnDifficulty();
    }

    /**
     * true if the absolute board cell (row, col) falls outside the board.
     * NOTE: negative row values are not checked.
     */
    private boolean outOfBounds(int row, int col) {
        return (col < 0 || col >= board.cols() || row >= board.rows());
    }

    /** Creates a new piece of 'type', centred on the board. */
    private Piece.Active createPiece(Piece.Type type) {
        Piece.Active p = new Piece.Active(type);
        p.setPosX(board.cols() / 2);   // spawn column derived from the board width
        return p;
    }

    /** Draws one of the piece types allowed on this screen. */
    private Piece.Type randomType() {
        return Piece.Type.at(rng.nextInt(piecesInPlay));
    }

    /** true if 'p' can stay: there is no piece or this screen allows its type. */
    private boolean allowed(Piece.Active p) {
        return p == null || p.type().allowedIn(piecesInPlay);
    }

    /**
     * A new screen may allow fewer piece types, while the preview was drawn
     * with the set of the screen just finished: a piece that is no longer
     * allowed is discarded and another one is drawn from the new set.
     * <p>
     * The falling piece needs no check: a screen only ends after its last
     * piece has been locked into the board, and a new game draws its pieces
     * once the first screen has been loaded.
     */
    private void replaceForbiddenPieces() {
        if (!allowed(nextPiece)) {
            nextPiece = createPiece(randomType());
        }
    }

    /** Promotes nextPiece to currentPiece and generates a new nextPiece. */
    private void spawnPiece() {
        currentPiece = nextPiece;
        nextPiece    = createPiece(randomType());
        clock.drop.start();  // a new piece gets a full period before falling
    }

    // ==================================================================
    // Lines done: the flash is in Effects, this is what the game does when
    // it ends
    // ==================================================================

    private void tickLines() {
        if (!effects.flashFinished()) {
            return;
        }

        // Flash finished: remove the row and shift everything above down
        int row = pendingLines.remove(0);
        clearLine(row);
        sound.line();

        if (linesLeft > 0) linesLeft--;

        if (pendingLines.isEmpty()) {
            if (linesLeft == 0) {
                // Screen cleared: start the fill effect
                gameState  = STATE_TRANS;
                effects.startFill();
            } else {
                gameState    = STATE_PLAY;
                fastDropping = false;
                spawnPiece();
            }
        } else {
            // More lines to clear: start the next flash
            effects.flashLine(pendingLines.get(0));
        }
    }

    /**
     * Removes completed row 'row' by shifting all rows above it down by one.
     * Also adjusts pending-line indices so multi-line clears work correctly.
     */
    private void clearLine(int row) {
        adjustDifficultyForClearedLine(row);
        board.removeRow(row);

        // Shift pending-line indices that were above the cleared row
        for (int i = 0; i < pendingLines.size(); i++) {
            int r = pendingLines.get(i);
            if (r < row) pendingLines.set(i, r + 1);
        }
    }

    // ==================================================================
    // Transition between screens: the fill is in Effects, this is the score
    // and the screen change
    // ==================================================================

    private void tickTransition() {
        // The decoration paints one row every few frames; the game scores it
        if (effects.paintRow()) {
            score += POINTS_PER_FILL_ROW;
        }

        if (effects.fillComplete()) {
            // Transition complete: load next screen
            difficultyCount = MAX_DIFFICULTY;
            linesLeft       = advanceScreen();
            gameState       = STATE_PLAY;
            fastDropping    = false;
            spawnPiece();
        }
    }

    // ------------------------------------------------------------------
    // Screens of level
    // ------------------------------------------------------------------

    /**
     * Advances to the next screen and returns the number of lines required.
     * Wraps around after screen 8 back to screen 1.
     */
    private int advanceScreen() {
        if (++currentScreen > levels.count()) currentScreen = 1;
        loadScreen(currentScreen - 1);
        difficultyActive = levels.difficultyActive(currentScreen - 1);
        piecesInPlay     = levels.numPieces(currentScreen - 1);
        replaceForbiddenPieces();
        return levels.linesRequired(currentScreen - 1);
    }

    /**
     * Loads screen 'n' (0..7): resets the board and places its obstacles.
     */
    private void loadScreen(int n) {
        board.clear();

        for (int[] p : levels.obstacles(n)) {
            board.place(p[0], p[1], p[2]);
        }
    }

    // ==================================================================
    // Difficulty blocks
    // ==================================================================

    /**
     * After locking a piece, randomly inserts an extra obstacle block on
     * the topmost row (screen 1 only) and blinks it in. Guards against
     * inserting when the row is nearly full or the budget is exhausted.
     */
    private void spawnDifficulty() {
        if (!difficultyActive || difficultyCount <= 0) return;
        int row = board.topRow();
        if (board.fillCount(row) >= board.cols() - 1) return;
        if (difficultyCount != MAX_DIFFICULTY
                && difficultyBlocks[difficultyCount][0] < row) return;
        if (rng.nextInt(PROBABLE) != 0) return;

        int col = rng.nextInt(board.cols());
        while (board.valueAt(row, col) != 0) col = rng.nextInt(board.cols());

        difficultyBlocks[--difficultyCount][0] = row;
        difficultyBlocks[difficultyCount][1]   = col;
        board.place(row, col, rng.nextInt(8) + 1);
        effects.appearBlock(row, col);
        sound.block();
    }

    /**
     * When a line is cleared, recovers the difficulty budget for any block
     * that was on that row, and shifts row indices for blocks above it.
     */
    private void adjustDifficultyForClearedLine(int row) {
        for (int a = difficultyCount; a < MAX_DIFFICULTY; a++) {
            boolean removed = (row == difficultyBlocks[a][0]);
            if (row > difficultyBlocks[a][0]) difficultyBlocks[a][0]++;
            removed = (difficultyBlocks[a][0] >= board.rows()) || removed;
            if (removed) {
                difficultyBlocks[a][0] = difficultyBlocks[difficultyCount][0];
                difficultyBlocks[a][1] = difficultyBlocks[difficultyCount][1];
                difficultyCount++;
            }
        }
    }
}
