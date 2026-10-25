package principal;

/**
 * The decorations of the game: the four animations that are not part of the
 * rules and exist only to make the screen look alive.
 * <ul>
 *   <li>the flash of a completed line before it disappears,</li>
 *   <li>the pink fill of the whole board when a screen is cleared,</li>
 *   <li>the progressive darkening of the game-over screen,</li>
 *   <li>the blink of a block that has just been added by the difficulty.</li>
 * </ul>
 * Each one keeps its board decoration here and its ticks in {@link Clock}, so
 * {@link Game} only has to ask "has it finished yet?" and keep the
 * consequences. Keeping them out of the engine leaves the engine with the
 * rules and nothing else.
 */
class Effects {

    /** Steps of the game-over fade: 0.55, 0.30 and 0.17 of the brightness. */
    private static final int FADE_STEPS = 3;
    /** Changes of state of the blink: hidden, visible, hidden and stay visible. */
    private static final int BLINK_TOGGLES = 3;
    /** Board value of the fill: the pink used by the level transition. */
    private static final int FILL_VALUE = Palette.valueOfIndex(Palette.TRANS_FILL);

    private final Board board;
    private final Sound sound;
    private final Clock clock;

    /** First row not filled yet. */
    private int fillRow;
    /** Step of the game-over fade. */
    private int fadeStep;
    /** Cell of the block that is blinking: row -1 = none. */
    private int blinkRow = -1;
    private int blinkCol;
    /** true while the blinking block is in its hidden phase, and toggles left. */
    private boolean blinkHidden;
    private int blinkLeft;

    /**
     * @param board playfield the effects decorate
     * @param sound clips played by the fill
     * @param clock source of the ticks every effect waits for
     */
    Effects(Board board, Sound sound, Clock clock) {
        this.board = board;
        this.sound = sound;
        this.clock = clock;
    }

    /**
     * Returns the decorations to rest. Only the blink can be running when a new
     * game starts; the fill and the fade are opened by their own start method.
     */
    void reset() {
        blinkRow    = -1;
        blinkHidden = false;
        blinkLeft   = 0;
    }

    // ------------------------------------------------------------------
    // Completed line: brighten its edges and let it flash
    // ------------------------------------------------------------------

    /** Starts the flash of a completed row. */
    void flashLine(int row) {
        board.markLineFlash(row);
        clock.line.start();
    }

    /** @return true when the current line has finished flashing */
    boolean flashFinished() {
        return clock.line.trigger();
    }

    // ------------------------------------------------------------------
    // Screen cleared: fill the board with the transition colour
    // ------------------------------------------------------------------

    /** Starts the fill from the top row. */
    void startFill() {
        fillRow = 0;
        clock.fill.start();
    }

    /**
     * Advances the fill one tick.
     *
     * @return true if a row has been painted during this tick
     */
    boolean paintRow() {
        if (!clock.fill.trigger()) {
            return false;
        }
        if (fillRow >= board.topRow()) {
            return false;                  // nothing left: the counter idles
        }
        clock.fill.start();                // one row painted: the wait begins again
        board.fillRow(fillRow++, FILL_VALUE);
        sound.block();
        return true;
    }

    /** true when every row down to the highest occupied one has been filled. */
    boolean fillComplete() {
        return fillRow >= board.topRow();
    }

    // ------------------------------------------------------------------
    // Game over: darken the screen step by step
    // ------------------------------------------------------------------

    /** Starts the darkening from its first step. */
    void startFade() {
        fadeStep = 0;
        clock.fade.start();
    }

    /** Advances the darkening; it stops after the last step. */
    void tickFade() {
        if (!clock.fade.trigger()) {
            return;
        }
        fadeStep++;
        if (fadeStep < FADE_STEPS) {
            clock.fade.start();            // one step done: the wait for the next begins
        }
    }

    /** Current step of the darkening; 0 means "not darkened". */
    int fadeStep() {
        return fadeStep;
    }

    // ------------------------------------------------------------------
    // A difficulty block has appeared: blink it in
    // ------------------------------------------------------------------

    /**
     * Announces a block that has just been placed: it blinks a few ticks before
     * it stays. The board never changes; only the picture hides it (see
     * {@link Game#cellValue(int, int)}).
     */
    void appearBlock(int row, int col) {
        blinkRow    = row;
        blinkCol    = col;
        blinkLeft   = BLINK_TOGGLES;
        blinkHidden = true;              // it flickers in: hidden first
        clock.blink.start();
    }

    /** Advances the blink; it ends with the block visible. */
    void tickBlink() {
        if (blinkRow < 0 || !clock.blink.trigger()) {
            return;
        }
        blinkHidden = !blinkHidden;
        if (--blinkLeft <= 0) {
            blinkRow = -1;               // done: the block belongs to the board now
        } else {
            clock.blink.start();
        }
    }

    /** true while a blinking block must not be drawn. */
    boolean hidden(int row, int col) {
        return blinkHidden && row == blinkRow && col == blinkCol;
    }
}
