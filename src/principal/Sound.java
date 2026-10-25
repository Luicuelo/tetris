package principal;

/**
 * The sound effects of the game, as an interface so that the rules do not
 * depend on any audio toolkit.
 * <p>
 * {@link Game} only knows that "a piece landed" or "a line was cleared"; which
 * clip is played, and whether anything is played at all, is the business of the
 * implementation ({@link JavaFxSound} in the application).
 */
interface Sound {

    /** A piece has landed and has been locked into the board. */
    void drop();

    /** A completed line has been removed. */
    void line();

    /** A difficulty block appeared, or a row of the screen-clear fill. */
    void block();

    /** Implementation that does nothing: runs the game without audio. */
    Sound SILENT = new Sound() {
        @Override
        public void drop() {
        }

        @Override
        public void line() {
        }

        @Override
        public void block() {
        }
    };
}
