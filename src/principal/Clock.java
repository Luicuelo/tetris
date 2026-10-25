package principal;

/**
 * The clock of the game: the ticks since the start and every counter measured
 * in ticks.
 * <p>
 * A tick only increments one number. A counter is the tick at which its period
 * was opened, so "has it been long enough?" is a subtraction between that tick
 * and the current one. Nothing is notified and nothing walks the counters: every
 * "how long has it been?" in the project is the same call,
 * {@code clock.drop.trigger()}, and the durations live next to the counter that
 * uses them.
 * <p>
 * It counts ticks, not time, and the engine never looks at a system clock: the
 * shell turns the timestamps of the pulses into ticks at a fixed rate of 60 per
 * second ({@link FrameTimer}), so 18 ticks are 0.3 s on any machine, a slow one
 * drops frames instead of slowing the game down, and a paused game freezes the
 * clock because {@code Game.tick()} returns before ticking it.
 */
class Clock {

    /** Ticks between two automatic drops (0.3 s at 60 ticks per second). */
    private static final long TICKS_DROP = 18;
    /** Ticks a completed line stays flashing (~0.5 s). */
    private static final long TICKS_LINE = 30;
    /** Ticks between two rows of the screen-clear fill (~0.4 s). */
    private static final long TICKS_FILL = 24;
    /** Ticks between two steps of the game-over fade (~0.35 s). */
    private static final long TICKS_FADE = 21;
    /** Ticks between two changes of a block that has just appeared (~0.13 s). */
    private static final long TICKS_BLINK = 8;

    /** Gravity: fires every 18 ticks and the piece falls one row. */
    final Counter drop = new Counter(TICKS_DROP);
    /** The flash of a completed line. */
    final Counter line = new Counter(TICKS_LINE);
    /** One row of the screen-clear fill. */
    final Counter fill = new Counter(TICKS_FILL);
    /** One step of the game-over fade. */
    final Counter fade = new Counter(TICKS_FADE);
    /** One change of state of a block that has just appeared. */
    final Counter blink = new Counter(TICKS_BLINK);

    private long ticks;

    /** Ticks elapsed since the start of the game. */
    long ticks() {
        return ticks;
    }

    /** Advances the clock one tick; the counters read it when they are asked. */
    void tick() {
        ticks++;
    }

    /** Starts the game again: the clock goes back to zero and every period is dropped. */
    void reset() {
        ticks = 0;
        drop.stop();
        line.stop();
        fill.stop();
        fade.stop();
        blink.stop();
    }

    /**
     * A period of ticks: the tick at which it was opened and the ticks it lasts.
     * <p>
     * A counter is idle — no period open — until {@link #start()} records the
     * current tick. From there {@link #value()} is how many ticks have passed
     * since then, clipped to the limit, and {@link #trigger()} answers true once
     * — closing the period — when the limit is reached. So a counter that nobody
     * started never fires, and the same period can never fire twice.
     */
    class Counter {

        private final long limit;
        /** Tick of the start of the period; -1 means idle. */
        private long startedAt = -1;

        Counter(long limit) {
            this.limit = limit;
        }

        /** Ticks elapsed since start(), up to the limit; 0 while it is idle. */
        long value() {
            if (startedAt < 0) {
                return 0;
            }
            return Math.min(ticks - startedAt, limit);
        }

        /** Opens a period: it begins at the current tick. */
        void start() {
            startedAt = ticks;
        }

        /** true when the limit has been reached; the period is closed there. */
        boolean trigger() {
            if (startedAt < 0 || ticks - startedAt < limit) {
                return false;
            }
            startedAt = -1;
            return true;
        }

        /** Closes the period without firing it. */
        void stop() {
            startedAt = -1;
        }
    }
}
