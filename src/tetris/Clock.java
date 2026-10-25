package tetris;

import java.util.ArrayList;
import java.util.List;

/**
 * The clock of the game: the ticks since the start and every period measured in
 * ticks.
 * <p>
 * A tick only increments one number. A counter is the tick at which its period
 * was opened, so "has it been long enough?" is a subtraction between that tick
 * and the current one. Nothing is notified and nothing walks the counters: every
 * "how long has it been?" in the project is the same call,
 * {@code gravity.trigger()}.
 * <p>
 * The clock decides no duration: whoever waits for a period creates its counter
 * with {@link #newCounter(long)}, so the number lives next to the thing that
 * lasts that long. What the clock keeps is the list of the counters it has
 * created, for one reason: a restart drops every period at once.
 * <p>
 * It counts ticks, not time, and the engine never looks at a system clock: the
 * shell turns the timestamps of the pulses into ticks at a fixed rate of 60 per
 * second ({@link FrameTimer}), so 18 ticks are 0.3 s on any machine, a slow one
 * drops frames instead of slowing the game down, and a paused game freezes the
 * clock because {@link Engine#tick()} returns before ticking it.
 */
final class Clock {

    /** Every counter created here: a reset stops them all. */
    private final List<Counter> counters = new ArrayList<>();

    private long ticks;

    /** Advances the clock one tick; the counters read it when they are asked. */
    void tick() {
        ticks++;
    }

    /**
     * Creates a counter of 'limit' ticks and registers it, so that a restart can
     * stop it. It is idle until its owner opens the first period.
     *
     * @param limit ticks each period of this counter lasts
     * @return the new counter
     */
    Counter newCounter(long limit) {
        Counter counter = new Counter(limit);
        counters.add(counter);
        return counter;
    }

    /** Starts the game again: the clock goes back to zero and every period is dropped. */
    void reset() {
        ticks = 0;
        for (Counter counter : counters) {
            counter.stop();
        }
    }

    /**
     * A period of ticks: the tick at which it was opened and the ticks it lasts.
     * <p>
     * A counter is idle — no period open — until {@link #start()} records the
     * current tick. From there {@link #trigger()} answers true once — closing the
     * period — when the limit is reached. So a counter that nobody started never
     * fires, the same period can never fire twice, and whoever wants several
     * equal periods (the blink and the flash of a line) opens the next one where
     * the previous one ended.
     */
    class Counter {

        private final long limit;
        /** Tick of the start of the period; -1 means idle. */
        private long startedAt = -1;

        /** Only {@link Clock#newCounter(long)} creates counters, so none escapes the list. */
        private Counter(long limit) {
            this.limit = limit;
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
