package tetris;

/**
 * The visual decorations of the game: the four animations that are not part of
 * the rules and exist only to make the screen look alive.
 * <ul>
 *   <li>the flash of a completed line before it disappears,</li>
 *   <li>the pink fill of the whole board when a screen is cleared,</li>
 *   <li>the progressive darkening of the game-over screen,</li>
 *   <li>the blink of a block that has just been added by the difficulty.</li>
 * </ul>
 * Each decoration is a {@link Sequence} of its own — its period, its number of
 * repetitions and what to do on each one and at the end — created once in the
 * constructor. The sequence carries its own typed target ({@code T}): the row
 * to flash, the cell to blink, the row to fill. Starting the sequence on a
 * target is the only thing that changes between runs; the actions are fixed at
 * construction time and receive that target when each repetition fires.
 * <p>
 * The fields are final but not constant: {@code final} freezes the reference,
 * not the sequence behind it, and every sequence keeps its own counter, target
 * and start tick. Together with the board, the clock and the sound of the
 * running game, that makes this class state rather than a bag of helpers, so a
 * static version would be global state shared by every engine in the process.
 * <p>
 * The sequences stay private: the engine drives every decoration through one
 * pair of methods of its own — {@code start...} and {@code tick...} — exactly as
 * it starts them, and the counting, the targets and the clock periods are an
 * implementation detail of this class. The tick of a decoration that has a
 * visible end reports it with {@link Step}, so nobody here has to ask a
 * sequence whether it is over.
 * <p>
 * The blink and the line flash show and hide cells by writing the hidden mask
 * of the {@link Board}: the cell keeps its value and its collision, but the
 * picture skips it.
 * <p>
 * The only visual output this class exposes is the game-over darkening curve
 * ({@link #fadeProgress()}); the engine forwards it and the shell hands the
 * value to the {@link Renderer} once per picture, which applies it to the
 * palette while the game is over. The fixed pause factor is not a curve and
 * belongs to the renderer.
 */
final class Effects {

    /**
     * Answer of the tick of a decoration that has an end the engine waits for:
     * nothing has happened yet, one repetition has run, or the run is over.
     */
    enum Step {
        /** The period is still open: the decoration has not moved. */
        WAITING,
        /** One repetition has just run and the run goes on. */
        ADVANCED,
        /** The last repetition has just run: the decoration is over. */
        FINISHED
    }

    /**
     * Brightness is multiplied by this value once per step of the game-over fade
     * (0..1), so the picture approaches black step by step.
     */
    private static final double FADE_DECAY = 0.55;
    /** Palette value of the fill colour used by the level-transition effect. */
    private static final int FILL_VALUE = Palette.valueOfIndex(Palette.TRANS_FILL);

    private final Board board;

    // ==================================================================
    // The four sequences, one per decoration. Each one is created once and
    // reused for every run; only the target changes between runs. They are
    // private: the engine drives them through the start / tick pair below.
    // ==================================================================

    /** Target: the board row that is flashing. */
    private final Sequence<Integer> lineFlash;
    /** Target: {row, col} of the block that is blinking in. */
    private final Sequence<int[]>   blockBlink;
    /** Target: the board row being painted by the transition fill. */
    private final Sequence<Integer> screenFill;
    /** No target: the sequence only counts steps to drive the fade curve. */
    private final Sequence<Void>    gameOverFade;

    /**
     * Creates the four sequences and wires their actions to the board.
     *
     * @param board playfield the effects decorate
     * @param sound clips played by the fill
     * @param clock clock that counts the ticks, and where each period is registered
     */
    Effects(Board board, Sound sound, Clock clock) {
        this.board = board;
        this.lineFlash = new Sequence<>(clock, 6, 6,
                (row, repetition) -> {
                    if (repetition % 2 == 0) board.showRow(row);
                    else                     board.hideRow(row);
                },
                (row, ignored) -> board.showRow(row));
        this.blockBlink = new Sequence<>(clock, 8, 4,
                (cell, repetition) -> {
                    if (repetition % 2 == 0) board.show(cell[0], cell[1]);
                    else                     board.hide(cell[0], cell[1]);
                },
                (cell, ignored) -> board.show(cell[0], cell[1]));
        this.screenFill = new Sequence<>(clock, 24, 1,
                (row, ignored) -> { board.fillRow(row, FILL_VALUE);
                                    sound.block(); },
                null);
        this.gameOverFade = new Sequence<>(clock, 21, 3, null, null);
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    /**
     * Returns all decorations to rest: sequences are stopped (their periods are
     * closed by {@link Clock#reset()}) and the hidden mask is cleared.
     */
    void reset() {
        lineFlash.stop();
        blockBlink.stop();
        screenFill.stop();
        gameOverFade.stop();
        board.clearHidden();
    }

    // ==================================================================
    // Ticks: one method per decoration, as the starts are
    // ==================================================================

    /**
     * Advances the flash of a completed line one tick.
     *
     * @return {@link Step#FINISHED} when the row has flashed for the last time
     *         and is ready to be removed
     */
    Step tickLineFlash() {
        return advance(lineFlash);
    }

    /** Advances the blink of the block that the difficulty has just placed. */
    void tickBlockBlink() {
        blockBlink.tick();
    }

    /**
     * Advances the fill of the level transition one tick.
     *
     * @return {@link Step#WAITING} while the row is not painted yet; anything
     *         else means the row is painted (the run has a single repetition,
     *         so it reports {@link Step#FINISHED})
     */
    Step tickScreenFill() {
        return advance(screenFill);
    }

    /** Starts the darkening of the game-over screen. */
    void startGameOverFade() {
        gameOverFade.start(null);       // the fade only counts: it has no target
    }

    /** Advances the darkening of the game-over screen one tick. */
    void tickGameOverFade() {
        gameOverFade.tick();
    }

    /**
     * Spends one repetition of a sequence when its period has ended and says
     * where the run stands, so the caller never has to ask the sequence.
     */
    private static Step advance(Sequence<?> sequence) {
        if (!sequence.tick()) {
            return Step.WAITING;
        }
        return sequence.hasFinished() ? Step.FINISHED : Step.ADVANCED;
    }

    // ==================================================================
    // Completed line: outline it and let it flash
    // ==================================================================

    /**
     * Starts the flash of a completed row: its edges are marked once, the row
     * goes dark and every step alternates between showing and hiding it. The end
     * action brings it back visible, so the engine removes a visible row.
     * <p>
     * If a flash is still running it is settled first, so the previous row is
     * left in a clean state before the new target is set.
     *
     * @param row board row to flash (0-based)
     */
    void startLineFlash(int row) {
        lineFlash.finish();
        board.markCompletedLineEdges(row);
        board.hideRow(row);
        lineFlash.start(row);
    }

    // ==================================================================
    // Screen cleared: paint one row of the transition fill
    // ==================================================================

    /**
     * Starts the fill of one row: after one period of 24 ticks the row is
     * painted with the transition colour. The engine walks the rows from the
     * top down and calls this once per row.
     *
     * @param row board row to fill (0-based)
     */
    void startFill(int row) {
        screenFill.start(row);
    }

    // ==================================================================
    // Brightness the shell hands to the Renderer, once per picture
    // ==================================================================

    /**
     * Brightness factor for the game-over darkening: starts at 1.0 (full
     * brightness) and is multiplied by {@code FADE_DECAY} with each repetition
     * of the fade sequence, approaching black. The renderer applies it to the
     * palette while the game is over.
     *
     * @return a value in (0, 1]
     */
    double fadeProgress() {
        return Math.pow(FADE_DECAY, gameOverFade.repetitionsDone());
    }

    // ==================================================================
    // Difficulty block: blink it in
    // ==================================================================

    /**
     * Announces a block that has just been placed by the difficulty: it blinks
     * a few ticks before it stays. The board keeps the block; only the hidden
     * mask covers it during the blink, so collisions are unaffected.
     * <p>
     * If a blink is still running it is settled first.
     *
     * @param row board row of the new block (0-based)
     * @param col board column of the new block (0-based)
     */
    void appearBlock(int row, int col) {
        blockBlink.finish();
        board.hide(row, col);
        blockBlink.start(new int[]{row, col});
    }
}
