package tetris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The level data of the editor, read from and written to {@code src/tetris}.
 * <p>
 * The format itself lives in {@link Levels}: this class opens the files on disk
 * and hands the text lines over there, so both read the same data. What it adds
 * is the file side — the source directory as the source of truth — and the
 * writing.
 * <p>
 * Both formats are the ones {@link Levels} documents: {@code levels.csv} holds
 * one level per data line ({@code linesRequired,difficultyActive,numPieces},
 * the id being the 1-based order of the data lines) and
 * {@code levels/level_<id>.txt} holds one {@code row,col,color} per obstacle.
 * <p>
 * The editor has a single working board, the same one the game uses to play its
 * levels, and this class reads and writes it one level at a time: the file of
 * the level being edited is the store, and the obstacles are read into the
 * board when the level opens and written back when it is left. The list kept
 * here is only the three values of the table, which are not edited.
 * <p>
 * The comment and header lines of the master table are kept and written back at
 * the top, so a saved table is the file the game expects plus whatever was
 * painted.
 */
final class EditorLevelFiles {

    /** Directory the editor edits: the source data the game is built from. */
    private static final Path DIRECTORY = Path.of("src", "tetris");

    private static final String MASTER_NAME        = "levels.csv";
    private static final String OBSTACLE_DIRECTORY = "levels";
    private static final String OBSTACLE_PREFIX    = "level_";
    private static final String OBSTACLE_EXT       = ".txt";

    /** Values of a new level: the lines of a typical one, no difficulty, the classic seven pieces. */
    private static final int     NEW_LINES      = 10;
    private static final boolean NEW_DIFFICULTY = false;
    private static final int     NEW_PIECES     = 7;

    /**
     * One row of the master table: the three values of a level. The obstacles are
     * not here — they are the file, and the working board while the level is open.
     */
    private record TableRow(int linesRequired, boolean difficultyActive, int numPieces) {
    }

    /** Working board: the level being edited, and the geometry the cells are checked against. */
    private final Board board;
    private final Path  masterFile;
    private final Path  obstacleDirectory;

    /** Comment and header lines of the master table, written back as they came. */
    private final List<String> masterPrefix = new ArrayList<>();

    /** Line terminator of the data files, so a save keeps them as they were. */
    private String lineTerminator = "\n";

    /** The rows of the table in file order: index 0 is level 1. */
    private final List<TableRow> tableRows = new ArrayList<>();

    /**
     * Opens the level data the editor edits: {@code levels.csv} and the
     * {@code levels/} folder, both under {@link #DIRECTORY}.
     *
     * @param board working board, the size the cells are read and written with
     */
    EditorLevelFiles(Board board) {
        if (board == null) throw new IllegalArgumentException("board cannot be null");

        this.board        = board;
        masterFile        = DIRECTORY.resolve(MASTER_NAME);
        obstacleDirectory = DIRECTORY.resolve(OBSTACLE_DIRECTORY);
    }

    /** Number of levels held. */
    int count() {
        return tableRows.size();
    }

    // ==================================================================
    // Reading
    // ==================================================================

    /**
     * Reads the master table from disk and has {@link Levels} parse and validate
     * it, so the editor accepts the same values as the game.
     * <p>
     * Only the table is read here. The obstacles of a level are read by
     * {@link #loadInto(int)} when the level is opened, so a problem in an obstacle
     * file stops that level from opening instead of the whole editor.
     *
     * @throws IllegalStateException if the table is missing or not usable
     */
    void loadMaster() {
        if (!Files.isRegularFile(masterFile)) {
            throw new IllegalStateException(masterFile + " not found: run the editor from the "
                    + "project root (it edits " + DIRECTORY + ")");
        }

        String text = readText(masterFile);
        lineTerminator = text.contains("\r\n") ? "\r\n" : "\n";

        Levels levels = new Levels(board);
        levels.load(Levels.textLines(text), id -> null);
        levels.validate();

        tableRows.clear();
        for (int i = 0; i < levels.size(); i++) {
            Levels.Level level = levels.get(i);
            tableRows.add(new TableRow(level.linesRequired(), level.difficultyActive(),
                                       level.numPieces()));
        }

        masterPrefix.clear();
        for (String textLine : text.lines().toList()) {
            if (!Levels.isLevelTextLine(textLine)) {
                masterPrefix.add(textLine);        // comment, blank line or header
            }
        }
    }

    /**
     * Reads the obstacles of level {@code index} into the working board, leaving
     * it empty when the level has no file. The board is only cleared once the
     * file has been read and checked, so a broken file leaves it as it was.
     *
     * @param index level to open (0-based)
     * @throws IllegalStateException when its file has a problem
     */
    void loadInto(int index) {
        Path file = obstacleFile(index + 1);
        List<Levels.TextLine> textLines =
                Files.isRegularFile(file) ? Levels.textLines(readText(file)) : null;
        new Levels(board).fillBoard(index + 1, textLines);
    }

    // ==================================================================
    // Writing
    // ==================================================================

    /**
     * Writes the obstacles of level {@code index} from the working board. A
     * level without obstacles leaves no file, so the contract "missing file =
     * no obstacles" is what a cleared level leaves on disk.
     *
     * @param index level to write (0-based)
     * @throws IllegalStateException if the file cannot be written
     */
    void saveFrom(int index) {
        createDirectory(obstacleDirectory);
        writeObstacles(index + 1, board);
    }

    /**
     * Writes the master table: the prefix lines and the three values of every
     * level. The id of a level is its position, so adding or removing one is
     * what changes this file.
     *
     * @throws IllegalStateException if the file cannot be written
     */
    void saveMaster() {
        StringBuilder master = new StringBuilder();
        for (String textLine : masterPrefix) {
            master.append(textLine).append(lineTerminator);
        }
        for (TableRow row : tableRows) {
            master.append(row.linesRequired()).append(',')
                  .append(row.difficultyActive()).append(',')
                  .append(row.numPieces()).append(lineTerminator);
        }
        write(masterFile, master.toString());
    }

    // ==================================================================
    // Adding and removing levels
    // ==================================================================

    /**
     * Appends a level with the values of a new one and returns its index. Nothing
     * is written here: an empty level has no obstacle file yet, and
     * {@link #saveMaster()} is what makes it exist in the table.
     */
    int add() {
        tableRows.add(new TableRow(NEW_LINES, NEW_DIFFICULTY, NEW_PIECES));
        return tableRows.size() - 1;
    }

    /**
     * Removes the level at {@code index} and moves the level files that follow
     * down one id, because the id of a level is its position in the table. The
     * file left over at the end is deleted with it.
     * <p>
     * The files are moved as text, from the gap upwards, so nothing is normalised
     * on the way. Call it after writing the level being edited: the level
     * disappears whether or not it had unsaved cells.
     *
     * @param index level to remove (0-based)
     * @throws IllegalStateException if a file cannot be moved
     */
    void remove(int index) {
        int oldCount = tableRows.size();
        tableRows.remove(index);
        renumber(index + 1, oldCount);
    }

    /**
     * Moves {@code level_<id>} to {@code level_<id-1>} for every id after the
     * removed one, then deletes the last file. Going up means a file is read
     * before its own slot is overwritten by the one before it.
     */
    private void renumber(int removedId, int oldCount) {
        createDirectory(obstacleDirectory);
        for (int id = removedId + 1; id <= oldCount; id++) {
            Path from = obstacleFile(id);
            Path to   = obstacleFile(id - 1);
            if (Files.isRegularFile(from)) {
                write(to, readText(from));
            } else {
                delete(to);         // a level without obstacles leaves no file
            }
        }
        delete(obstacleFile(oldCount));
    }

    /** Writes the obstacles of level {@code id}; an empty level leaves no file. */
    private void writeObstacles(int id, Board board) {
        int rows = board.rows();
        int cols = board.cols();

        StringBuilder text = new StringBuilder();
        text.append("# Obstacles of level ").append(id)
            .append(": one cell per line as row,col,color").append(lineTerminator);
        text.append("# row 0..").append(rows - 1).append(" (top of the board is 0), col 0..")
            .append(cols - 1).append(", color ").append(Levels.MIN_COLOR).append("..")
            .append(Levels.MAX_COLOR).append(lineTerminator);

        int filled = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < cols; column++) {
                int value = board.valueAt(row, column);
                if (value == 0) continue;
                text.append(row).append(',').append(column).append(',').append(value)
                    .append(lineTerminator);
                filled++;
            }
        }

        Path file = obstacleFile(id);
        if (filled == 0) {
            delete(file);
            return;
        }
        write(file, text.toString());
    }

    private Path obstacleFile(int id) {
        return obstacleDirectory.resolve(OBSTACLE_PREFIX + id + OBSTACLE_EXT);
    }

    // ==================================================================
    // Files
    // ==================================================================

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(file + ": cannot be read: " + e.getMessage(), e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(file + ": cannot be written: " + e.getMessage(), e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException(file + ": cannot be deleted: " + e.getMessage(), e);
        }
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException(directory + ": cannot be created: " + e.getMessage(),
                    e);
        }
    }
}
