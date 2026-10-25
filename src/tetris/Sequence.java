package tetris;

/**
 * A run of equal periods over one decoration: how long a period lasts, how many
 * repetitions the run has, what each repetition does and what to do when the
 * last one is over.
 * <p>
 * The line flash, the blink of a block, the screen-clear fill and the game-over
 * darkening are the same counting over different targets, so the counting lives
 * here and the target lives alongside it. All of it — the period, the number of
 * repetitions and the two actions — is given when the sequence is created,
 * because it is the same in every run; {@link #start(Object)} puts the count back
 * to the total, takes the target of the run and opens the first period, and the
 * repetitions are spent one by one as the periods end.
 * <p>
 * The type parameter {@code T} is the target of the decoration: an {@code Integer}
 * row for the line flash and the screen fill, an {@code int[]} cell for the block
 * blink, and {@code Void} for the game-over fade that only counts. The target is
 * given to {@link #start(Object)} and kept for the whole run: the sequence hands
 * it to every action it fires, so an action never has to ask the sequence for it.
 * <p>
 * One sequence per decoration, created once with the effects: the engine ticks it
 * and reads whether it is over, and nothing is allocated while the game runs.
 */
final class Sequence<T> {

    /** What a repetition, or the end of the run, does with the target of the run. */
    interface Action<T> {
        /**
         * @param target     target of the run: the row to flash, the cell to
         *                   blink, the row to fill
         * @param repetition in a step action, the index of the repetition that just
         *                   fired (0-based); in the end action, the total count run
         */
        void run(T target, int repetition);
    }

    /** The period of the run; the clock registers it, so a restart stops it. */
    private final Clock.Counter counter;
    /** Total repetitions of one run: what one {@link #start(Object)} spends, one period each. */
    private final int repetitions;
    /** What each repetition does, or {@code null} if the run only counts. */
    private final Action<T> step;
    /** What to do when the last repetition is over, or {@code null}. */
    private final Action<T> end;
    /**
     * Target of the run in progress: the row, cell or any data its actions work
     * on. Written only by {@link #start(Object)}, so every action of a run works
     * on the target that run started with.
     */
    private T target;

    private int repetitionsLeft;
    private int repetition;

    /**
     * A run of {@code repetitions} steps of {@code period} ticks. The target is
     * not part of the construction: it arrives with each {@link #start(Object)}.
     *
     * @param clock       clock that counts the ticks, and where the period is registered
     * @param period      ticks of one repetition
     * @param repetitions how many repetitions the run has
     * @param step        what each repetition does, or {@code null} if the run only counts
     * @param end         what to do when the last repetition is over, or {@code null}
     */
    Sequence(Clock clock, long period, int repetitions, Action<T> step, Action<T> end) {
        this.counter     = clock.newCounter(period);
        this.repetitions = repetitions;
        this.step        = step;
        this.end         = end;
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    /**
     * Starts the run on a target: the whole count again, this target, and the
     * first period open. Every action the run fires receives it, so a run never
     * works on the target of the previous one.
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
