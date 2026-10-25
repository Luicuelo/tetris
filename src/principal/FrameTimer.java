package principal;

/**
 * Turns the timestamps of the pulses into whole ticks of the game.
 * <p>
 * The engine must run at a fixed rate — 60 ticks per second — whatever the
 * screen manages: the animation timer pulses at the refresh rate of the monitor
 * and stretches when a frame is expensive, so an engine that advances one tick
 * per pulse runs faster on a fast machine and slower on a slow one. Here the
 * pulses only supply elapsed time; the ticks that fit in it are handed out and
 * the remainder waits for the next pulse.
 * <p>
 * The delay a single pulse may make up is capped: after a stall (a window being
 * dragged, a garbage collection) the game continues from where it was instead of
 * fast-forwarding the whole pause.
 */
class FrameTimer {

    /** Length of one tick of the game: sixty per second. */
    private static final long TICK_NANOS = 1_000_000_000L / 60;
    /** Longest delay one pulse makes up: four ticks, so down to ~15 frames per second. */
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
        long elapsed = Math.max(0, Math.min(now - last, TICK_NANOS * MAX_TICKS_PER_PULSE));
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
