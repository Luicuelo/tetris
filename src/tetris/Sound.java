package tetris;

/**
 * The sound effects of the game, as an interface so that the rules do not
 * depend on any audio toolkit.
 * <p>
 * {@link Engine} only knows that "a piece landed" or "a line was cleared"; which
 * clip is played, at what volume, and whether anything is played at all, is the
 * business of the implementation ({@link Tetris.JavaFxSound}, tucked inside the
 * shell that chooses it).
 */
interface Sound {

    /** A piece has landed and has been locked into the board. */
    void drop();

    /** A completed line has been removed. */
    void line();

    /** A difficulty block appeared. */
    void block();

    /** A row of the transition fill has been filled. */
    void fill();

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

        @Override
        public void fill() {
        }
    };
}
