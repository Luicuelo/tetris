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
 * The format itself lives in {@link Levels}: the editor opens the files on disk
 * and hands the lines over there, so both read exactly the same data. What this
 * class adds is the file side — the source directory as the source of truth, an
 * edit is meant to end up in the repository — and the writing.
 * <p>
 * Both formats are the ones {@link Levels} documents: {@code levels.csv} holds
 * one screen per data line ({@code linesRequired,difficultyActive,numPieces},
 * the id being the 1-based order of the data lines) and
 * {@code levels/level_<id>.txt} holds one {@code row,col,color} per obstacle.
 * <p>
 * The editor has a single working board, the same one the game uses to play its
 * screens, and this class reads and writes it one screen at a time: the file of
 * the screen being edited is the store, and the obstacles are read into the
 * board when the screen opens and written back when it is left. The list kept
 * here is only the three values of the table, which are not edited.
 * <p>
 * The comment and header lines of the master table are kept and written back at
 * the top, so a saved table is the file the game expects plus whatever was
 * painted. A new screen gets the defaults of a new level, and the three values of
 * an existing one are not edited here.
 */
final class LevelFiles {

    /** Directory the editor edits: the source data the game is built from. */
    static final Path DEFAULT_DIRECTORY = Path.of("src", "tetris");

    private static final String MASTER_NAME        = "levels.csv";
    private static final String OBSTACLE_DIRECTORY = "levels";
    private static final String OBSTACLE_PREFIX    = "level_";
    private static final String OBSTACLE_EXT       = ".txt";

    /** Values of a new screen: the lines of a typical level, no difficulty, the classic seven pieces. */
    private static final int     NEW_LINES      = 10;
    private static final boolean NEW_DIFFICULTY = false;
    private static final int     NEW_PIECES     = 7;

    /**
     * One row of the master table: the three values of a screen. The obstacles are
     * not here — they are the file, and the working board while the screen is open.
     */
    private record TableRow(int linesRequired, boolean difficultyActive, int numPieces) {
    }

    /** Working board: the screen being edited, and the geometry the cells are checked against. */
    private final Board board;
    private final Path  masterFile;
    private final Path  obstacleDirectory;

    /** Comment and header lines of the master table, written back as they came. */
    private final List<String> masterPrefix = new ArrayList<>();

    /** Line terminator of the data files, so a save keeps them as they were. */
    private String lineTerminator = "\n";

    /** The rows of the table in file order: index 0 is screen 1. */
    private final List<TableRow> tableRows = new ArrayList<>();

    /** Opens the level data of the default directory. */
    LevelFiles(Board board) {
        this(board, DEFAULT_DIRECTORY);
    }

    /**
     * Opens the level data of one directory.
     *
     * @param board     working board, the size the cells are read and written with
     * @param directory folder holding {@code levels.csv} and {@code levels/}
     */
    LevelFiles(Board board, Path directory) {
        if (board == null)     throw new IllegalArgumentException("board cannot be null");
        if (directory == null) throw new IllegalArgumentException("directory cannot be null");

        this.board        = board;
        masterFile        = directory.resolve(MASTER_NAME);
        obstacleDirectory = directory.resolve(OBSTACLE_DIRECTORY);
    }

    /** Number of screens held. */
    int count() {
        return tableRows.size();
    }

    // ==================================================================
    // Reading
    // ==================================================================

    /**
     * Reads the master table from disk and has {@link Levels} parse and validate
     * it, so the editor accepts exactly the same values as the game.
     * <p>
     * Only the table is read here. The obstacles of a screen are read by
     * {@link #loadInto(int)} when the screen is opened, so a problem in a level
     * file stops that screen from opening instead of the whole editor.
     *
     * @throws IllegalStateException if the table is missing or not usable
     */
    void loadMaster() {
        if (!Files.isRegularFile(masterFile)) {
            throw new IllegalStateException(masterFile + " not found: run the editor from the "
                    + "project root (it edits " + DEFAULT_DIRECTORY + ")");
        }

        String text = readText(masterFile);
        lineTerminator = text.contains("\r\n") ? "\r\n" : "\n";

        Levels levels = new Levels(board);
        levels.load(Levels.lines(text), id -> null);
        levels.validate();

        tableRows.clear();
        for (int i = 0; i < levels.size(); i++) {
            Level level = levels.get(i);
            tableRows.add(new TableRow(level.linesRequired(), level.difficultyActive(),
                                       level.numPieces()));
        }

        masterPrefix.clear();
        for (String line : text.lines().toList()) {
            if (!Levels.isLevelLine(line)) {
                masterPrefix.add(line);            // comment, blank line or header
            }
        }
    }

    /**
     * Reads the obstacles of screen {@code index} into the working board, leaving
     * it empty when the screen has no file. The board is only cleared once the
     * file has been read and checked, so a broken file leaves it as it was.
     *
     * @param index screen to open (0-based)
     * @throws IllegalStateException when its file has a problem
     */
    void loadInto(int index) {
        Path file = obstacleFile(index + 1);
        List<Levels.Line> lines = Files.isRegularFile(file) ? Levels.lines(readText(file)) : null;
        new Levels(board).fillBoard(index + 1, lines);
    }

    // ==================================================================
    // Writing
    // ==================================================================

    /**
     * Writes the obstacles of screen {@code index} from the working board. A
     * screen without obstacles leaves no file, so the contract "missing file =
     * no obstacles" is what a cleared screen leaves on disk.
     *
     * @param index screen to write (0-based)
     * @throws IllegalStateException if the file cannot be written
     */
    void saveFrom(int index) {
        createDirectory(obstacleDirectory);
        writeObstacles(index + 1, board);
    }

    /**
     * Writes the master table: the prefix lines and the three values of every
     * screen. The id of a level is its position, so adding or removing a screen
     * is what changes this file.
     *
     * @throws IllegalStateException if the file cannot be written
     */
    void saveMaster() {
        StringBuilder master = new StringBuilder();
        for (String line : masterPrefix) {
            master.append(line).append(lineTerminator);
        }
        for (TableRow row : tableRows) {
            master.append(row.linesRequired()).append(',')
                  .append(row.difficultyActive()).append(',')
                  .append(row.numPieces()).append(lineTerminator);
        }
        write(masterFile, master.toString());
    }

    // ==================================================================
    // Adding and removing screens
    // ==================================================================

    /**
     * Appends a screen with the values of a new one and returns its index. Nothing
     * is written here: an empty screen has no obstacle file yet, and
     * {@link #saveMaster()} is what makes it exist in the table.
     */
    int add() {
        tableRows.add(new TableRow(NEW_LINES, NEW_DIFFICULTY, NEW_PIECES));
        return tableRows.size() - 1;
    }

    /**
     * Removes the screen at 'index' and moves the level files that follow down one
     * id, because the id of a level is its position in the table. The file left
     * over at the end is deleted with it.
     * <p>
     * The files are read and written as text, one by one from the gap upwards, so
     * the content never passes through a board and nothing is normalised on the
     * way. Call it after writing the screen being edited: the screen disappears
     * whether or not it had unsaved cells.
     *
     * @param index screen to remove (0-based)
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
                delete(to);         // a screen without obstacles leaves no file
            }
        }
        delete(obstacleFile(oldCount));
    }

    /** Writes the obstacles of screen 'id'; an empty screen leaves no file. */
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
