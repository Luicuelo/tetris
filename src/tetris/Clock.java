package tetris;

import java.util.ArrayList;
import java.util.List;

/**
 * The clock of the game: the ticks since the start and every period measured in
 * ticks.
 * <p>
 * A tick only increments one number. A counter is the tick at which its period
 * was opened, so "has it been long enough?" is a subtraction between that tick
 * and the current one; nothing is notified and nothing walks the counters.
 * <p>
 * The clock decides no duration: whoever waits for a period creates its counter
 * with {@link #newCounter(long)}. The clock only keeps the list of counters, so
 * a restart drops every period at once.
 * <p>
 * It counts ticks, not time, and the engine never looks at a system clock: the
 * shell turns the pulses of its loop into ticks at a fixed 60 per second
 * ({@link FrameTimer}). A paused game freezes the clock because
 * {@link Engine#tick()} returns before ticking it.
 * <p>
 * {@link FrameTimer}, at the bottom of the file, is the other half: it turns the
 * pulses of the shell into ticks.
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
     * Creates a counter of {@code limit} ticks and registers it, so that a restart can
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
     * current tick. From there {@link #trigger()} answers true once, closing the
     * period, when the limit is reached; a counter nobody started never fires and
     * a period can never fire twice.
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

    /**
     * Turns the timestamps of the pulses into whole ticks of the game.
     * <p>
     * The engine must run at a fixed 60 ticks per second whatever the screen
     * manages: the animation timer pulses at the refresh rate of the monitor and
     * stretches when a frame is expensive. Here the pulses only supply elapsed
     * time; the ticks that fit in it are handed out and the remainder waits for
     * the next pulse.
     * <p>
     * The delay a single pulse may make up is capped: after a stall (a window
     * being dragged, a garbage collection) the game continues from where it was
     * instead of fast-forwarding the whole pause.
     */
    static final class FrameTimer {

        /** Length of one tick of the game: sixty per second. */
        private static final long TICK_NANOS = 1_000_000_000L / 60;
        /**
         * Longest delay one pulse makes up: four ticks, so down to ~15 frames per
         * second.
         */
        private static final int MAX_TICKS_PER_PULSE = 4;

        /** Timestamp of the previous pulse, in nanoseconds; -1 before the first one. */
        private long last = -1;
        /** Time left over from the previous pulses, in nanoseconds. */
        private long pending;

        /**
         * Answers how many ticks the game has to advance to keep its speed.
         *
         * @param now timestamp of the pulse, in nanoseconds
         * @return ticks to advance now: 0, 1, or more if the machine is falling behind
         */
        int ticksFor(long now) {
            if (last < 0) {
                last = now;
                return 0;                      // the first pulse only opens the count
            }
            long elapsed = Math.max(0,
                    Math.min(now - last, TICK_NANOS * MAX_TICKS_PER_PULSE));
            last = now;
            pending += elapsed;

            int ticks = 0;
            while (pending >= TICK_NANOS) {
                pending -= TICK_NANOS;
                ticks++;
            }
            return ticks;
        }
    }
}
