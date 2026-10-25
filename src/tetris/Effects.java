package tetris;

/**
 * The visual decorations of the game: the four animations that are not part of
 * the rules and exist only to make the screen look alive.
 * <ul>
 *   <li>the blink of a completed line before it disappears,</li>
 *   <li>the pink fill of the board when a level is cleared,</li>
 *   <li>the progressive darkening of the game-over screen,</li>
 *   <li>the blink of the cell the difficulty has just added.</li>
 * </ul>
 * Each decoration is one {@link Sequence}, created in the constructor with its
 * period, its number of repetitions and its actions; a run only changes the
 * target (the row to blink, the cell to blink in, the row to fill).
 * <p>
 * The engine drives every decoration through its own pair of methods,
 * {@code start...} and {@code tick...}, and the sequences stay private. The
 * ticks the engine waits for return true on the one that closes the decoration.
 * <p>
 * The two blinks show and hide cells through the hidden mask of the
 * {@link Board}: the cell keeps its value and its collision, but the picture
 * skips it.
 * <p>
 * The class hands out one value, the game-over darkening curve
 * ({@link #fadeProgress()}), which the shell passes to the {@link Renderer}
 * once per picture. The fixed pause factor is not a curve and belongs to the
 * renderer.
 */
final class Effects {

    /**
     * Brightness is multiplied by this value once per step of the game-over fade
     * (0..1), so the picture approaches black step by step.
     */
    private static final double FADE_DECAY = 0.55;
    /** Palette value of the fill colour used by the transition fill. */
    private static final int FILL_VALUE =
            Renderer.Palette.valueOfIndex(Renderer.Palette.TRANS_FILL);

    private final Board board;

    // ==================================================================
    // The four sequences, one per decoration
    // ==================================================================

    /** Target: the board row that is blinking. */
    private final Sequence<Integer> lineBlink;
    /** Target: {row, col} of the cell that is blinking in. */
    private final Sequence<int[]>   cellBlink;
    /** Target: the board row being filled by the transition fill. */
    private final Sequence<Integer> transitionFill;
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
        this.lineBlink = new Sequence<>(clock, 6, 6,
                (row, repetition) -> {
                    if (repetition % 2 == 0) board.showRow(row);
                    else                     board.hideRow(row);
                },
                (row, ignored) -> board.showRow(row));
        this.cellBlink = new Sequence<>(clock, 8, 4,
                (cell, repetition) -> {
                    if (repetition % 2 == 0) board.show(cell[0], cell[1]);
                    else                     board.hide(cell[0], cell[1]);
                },
                (cell, ignored) -> board.show(cell[0], cell[1]));
        this.transitionFill = new Sequence<>(clock, 24, 1,
                (row, ignored) -> { board.fillRow(row, FILL_VALUE);
                                    sound.fill(); },
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
        lineBlink.stop();
        cellBlink.stop();
        transitionFill.stop();
        gameOverFade.stop();
        board.clearHidden();
    }

    // ==================================================================
    // Ticks: one method per decoration, as the starts are
    // ==================================================================

    /**
     * Advances the blink of a completed line one tick.
     *
     * @return true on the tick that closes the blink, when the row is visible
     *         again and ready to be removed; false on every other tick
     */
    boolean tickLineBlink() {
        return lineBlink.tick() && lineBlink.hasFinished();   // true on the last step
    }

    /** Advances the blink of the cell the difficulty has just placed. */
    void tickCellBlink() {
        cellBlink.tick();
    }

    /**
     * Advances the transition fill one tick. A run fills one row, so the tick
     * that spends its only repetition is the one that closes it.
     *
     * @return true on the tick that closes the fill, when the row is filled;
     *         false while the wait goes on
     */
    boolean tickTransitionFill() {
        return transitionFill.tick() && transitionFill.hasFinished();
    }

    /** Starts the darkening of the game-over screen. */
    void startGameOverFade() {
        gameOverFade.start(null);       // the fade only counts: it has no target
    }

    /** Advances the darkening of the game-over screen one tick. */
    void tickGameOverFade() {
        gameOverFade.tick();
    }

    // ==================================================================
    // Completed line: outline it and let it blink
    // ==================================================================

    /**
     * Starts the blink of a completed row: its edges are marked once, the row
     * goes dark and every step alternates between showing and hiding it. The end
     * action brings it back visible, so the engine removes a visible row.
     * <p>
     * If a blink is still running it is settled first, so the previous row is
     * left in a clean state before the new target is set.
     *
     * @param row board row to blink (0-based)
     */
    void startLineBlink(int row) {
        lineBlink.finish();
        board.markCompletedLineEdges(row);
        board.hideRow(row);
        lineBlink.start(row);
    }

    // ==================================================================
    // Level cleared: one row of the transition fill
    // ==================================================================

    /**
     * Starts the fill of one row: after one period of 24 ticks the row is
     * filled with the transition colour. The engine walks the rows from the
     * top down and calls this once per row.
     *
     * @param row board row to fill (0-based)
     */
    void startTransitionFill(int row) {
        transitionFill.start(row);
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
     * Announces a cell that the difficulty has just placed: it blinks a few
     * ticks before it stays. The board keeps the cell; only the hidden mask
     * covers it during the blink, so collisions are unaffected.
     * <p>
     * If a blink is still running it is settled first.
     *
     * @param row board row of the new cell (0-based)
     * @param col board column of the new cell (0-based)
     */
    void startCellBlink(int row, int col) {
        cellBlink.finish();
        board.hide(row, col);
        cellBlink.start(new int[]{row, col});
    }

    // ==================================================================
    // The counting behind every decoration
    // ==================================================================

    /**
     * A run of equal periods over one decoration: how long a period lasts, how
     * many repetitions the run has, what each repetition does and what to do
     * when the last one is over.
     * <p>
     * Period, repetitions and actions are given at construction because they are
     * the same in every run; {@link #start(Object)} sets the target of the run
     * and opens the first period, and the repetitions are spent one by one as
     * the periods end.
     * <p>
     * The type parameter {@code T} is the target of the decoration: an
     * {@code Integer} row for the line blink and the transition fill, an
     * {@code int[]} cell for the cell blink, and {@code Void} for the game-over
     * fade, which only counts. Every action fired by a run receives that target.
     */
    static final class Sequence<T> {

        /** What a repetition, or the end of the run, does with the target of the run. */
        interface Action<T> {
            /**
             * @param target     target of the run: the row to blink, the cell to
             *                   blink in, the row to fill
             * @param repetition in a step action, the index of the repetition that
             *                   just fired (0-based); in the end action, the total
             *                   count run
             */
            void run(T target, int repetition);
        }

        /** The period of the run; the clock registers it, so a restart stops it. */
        private final Clock.Counter counter;
        /**
         * Total repetitions of one run: what one {@link #start(Object)} spends,
         * one period each.
         */
        private final int repetitions;
        /** What each repetition does, or {@code null} if the run only counts. */
        private final Action<T> step;
        /** What to do when the last repetition is over, or {@code null}. */
        private final Action<T> end;
        /**
         * Target of the run in progress: the row, cell or any data its actions
         * work on. Written only by {@link #start(Object)}.
         */
        private T target;

        private int repetitionsLeft;
        private int repetition;

        /**
         * A run of {@code repetitions} steps of {@code period} ticks. The target
         * is not part of the construction: it arrives with each
         * {@link #start(Object)}.
         *
         * @param clock       clock that counts the ticks, and where the period is registered
         * @param period      ticks of one repetition
         * @param repetitions how many repetitions the run has
         * @param step        what each repetition does, or {@code null} if the run only counts
         * @param end         what to do when the last repetition is over, or {@code null}
         */
        Sequence(Clock clock, long period, int repetitions,
                 Action<T> step, Action<T> end) {
            this.counter     = clock.newCounter(period);
            this.repetitions = repetitions;
            this.step        = step;
            this.end         = end;
        }

        // ==================================================================
        // Lifecycle
        // ==================================================================

        /**
         * Starts the run on a target: the whole count again and the first period
         * open. Every action the run fires receives that target.
         *
         * @param target target of this run; {@code null} for a run that only counts
         */
        void start(T target) {
            this.target     = target;
            repetitionsLeft = repetitions;
            repetition      = 0;
            counter.start();
        }

        /**
         * Spends one repetition if the period has ended.
         *
         * @return {@code true} if a step has run
         */
        boolean tick() {
            if (hasFinished() || !counter.trigger()) return false;
            if (step != null) step.run(target, repetition);
            repetition++;
            repetitionsLeft--;
            if (repetitionsLeft > 0) {
                counter.start();        // the next period begins where this one ended
            } else if (end != null) {
                end.run(target, repetition);    // the last step is over: the run settles
            }
            return true;
        }

        /**
         * Ends the run now with its end action, leaving the decoration as if it had
         * finished naturally. Used to settle a still-running sequence before
         * starting a new one on a different target.
         */
        void finish() {
            if (!hasFinished()) {
                if (end != null) end.run(target, repetition);
                stop();
            }
        }

        /** Stops the run where it is: the decoration keeps whatever it shows. */
        void stop() {
            repetitionsLeft = 0;
            counter.stop();
        }

        /** {@code true} when every repetition has been spent. */
        boolean hasFinished() {
            return repetitionsLeft <= 0;
        }

        /**
         * Repetitions already spent. The game-over fade, which only counts,
         * exposes its progress through this value.
         */
        int repetitionsDone() {
            return repetition;
        }
    }
}
