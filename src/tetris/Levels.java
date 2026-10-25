package tetris;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * The levels of the game, read from the two level files.
 * <p>
 * A level is one text line of {@code /tetris/levels.csv}
 * ({@code linesRequired,difficultyActive,numPieces}) plus the optional file
 * {@code /tetris/levels/level_<id>.txt} with its obstacles, one
 * {@code row,col,color} per text line. The id is the 1-based order of the data
 * lines, so the table needs no id column.
 * <p>
 * In both files blank lines and everything after a {@code #} are ignored,
 * fields may be separated by commas, semicolons or spaces, and the first line of
 * the table may be a header.
 * <p>
 * {@link #load()} fills the list and notes the text lines it could not read. A
 * line that is only a comment is not data, and every line read keeps the number
 * it occupies in its file, so a problem can name it (`levels/level_3.txt:7`).
 * {@link #validate()} then applies the rules to the list — usable values,
 * coordinates inside the board, colors within the palette, no repeated cells —
 * and throws with every problem found.
 * <p>
 * The level editor reads the same files from disk and uses the same
 * {@link #load(List, IntFunction)} and {@link #validate()}, and opens one level
 * at a time with {@link #fillBoard(int, List)}.
 */
final class Levels {

    private static final String MASTER_PATH   = "/tetris/levels.csv";
    private static final String MASTER_NAME   = "levels.csv";
    private static final String OBSTACLE_PATH = "/tetris/levels/level_";
    private static final String OBSTACLE_EXT  = ".txt";

    /** Separator: comma, semicolon or whitespace. */
    private static final String FIELD_SPLIT = "[,;\\s]+";

    /**
     * Colors the forms can be painted with; the editor writes its file header
     * with the same bounds. These are cell values (1..N_FORMCOLORS), not palette
     * indices: a bigger value would land on a reserved palette index such as a
     * digit colour.
     */
    static final int MIN_COLOR = 1;
    static final int MAX_COLOR = Renderer.Palette.N_FORMCOLORS;

    /** A level needs at least this many lines to be clearable. */
    private static final int MIN_LINES = 1;

    /**
     * One line of a text file, with the number it occupies there. Not a board
     * row: the rows of a level are its {@code linesRequired} and its cells.
     */
    record TextLine(int number, String body) {
    }

    /** Board that gives the geometry the cells are checked against. */
    private final Board board;

    /** The levels in file order: index 0 is the first one. */
    private final List<Level> levels = new ArrayList<>();

    /** What the text could not say; validate() adds the rules on top of it. */
    private final List<String> problems = new ArrayList<>();

    Levels(Board board) {
        this.board = board;
    }

    /** Number of levels. */
    int size() {
        return levels.size();
    }

    /** Level at {@code index} (0-based: index 0 is the first one). */
    Level get(int index) {
        return levels.get(index);
    }

    // ==================================================================
    // Loading
    // ==================================================================

    /**
     * Reads the two files from the classpath.
     *
     * @throws IllegalStateException if the table is missing or a file cannot be
     *         read
     */
    void load() {
        load(openTextLines(MASTER_PATH, MASTER_NAME, true),
             id -> openTextLines(OBSTACLE_PATH + id + OBSTACLE_EXT, fileOf(id), false));
    }

    /**
     * Reads the levels from text lines the caller has already read: the table,
     * one level per data line, and for every level id the text lines of its
     * obstacle file ({@code null} when it has none).
     * <p>
     * Each text line carries the number it occupies in its file, so a problem can
     * name it. A text line that is not three values is noted here; everything else
     * is left to {@link #validate()}.
     *
     * @param table         text lines of the master table, in file order; not null
     * @param obstacleLines text lines of the obstacle file of a level id, or null;
     *                      called once per data line with its id
     */
    void load(List<TextLine> table, IntFunction<List<TextLine>> obstacleLines) {
        if (table == null) {
            throw new IllegalArgumentException("table cannot be null");
        }

        levels.clear();
        problems.clear();

        List<TextLine> levelLines = dataLines(table);

        // The id of a level is its position among the data lines, so the header
        // of the table does not count: level 1 takes levels/level_1.txt.
        int first = !levelLines.isEmpty() && isHeader(levelLines.get(0)) ? 1 : 0;
        for (int i = first; i < levelLines.size(); i++) {
            int id = i - first + 1;
            Level level = level(id, levelLines.get(i), obstacleLines.apply(id));
            if (level != null) {
                levels.add(level);
            }
        }
    }

    /**
     * The level of one text line of the table and the text lines of its obstacle
     * file, or null (and a problem) when the line is not three usable values.
     *
     * @param id        id of the level, the one its obstacle file carries
     * @param tableLine data line of the table
     * @param cellLines text lines of the obstacle file, or null when it has none
     */
    private Level level(int id, TextLine tableLine, List<TextLine> cellLines) {
        String[] fields = tableLine.body().split(FIELD_SPLIT);
        if (fields.length != 3) {
            return badTableLine(tableLine);
        }

        Integer linesRequired = toInt(fields[0]);
        Boolean difficulty    = toFlag(fields[1]);
        Integer numPieces     = toInt(fields[2]);
        if (linesRequired == null || difficulty == null || numPieces == null) {
            return badTableLine(tableLine);
        }
        return new Level(linesRequired, difficulty, numPieces, cells(id, cellLines));
    }

    /** Notes the text line and returns null: without three values there is no level. */
    private Level badTableLine(TextLine tableLine) {
        problems.add(MASTER_NAME + ":" + tableLine.number() + ": expected "
                + "linesRequired,difficultyActive,numPieces, found: " + tableLine.body());
        return null;
    }

    /**
     * The cells of one level: three numbers per text line. A null list means the
     * level has no obstacle file, which is an empty level.
     */
    private List<int[]> cells(int id, List<TextLine> cellLines) {
        if (cellLines == null) {
            return List.of();
        }

        String name = fileOf(id);
        List<int[]> cells = new ArrayList<>();
        for (TextLine cellLine : dataLines(cellLines)) {
            String[] fields = cellLine.body().split(FIELD_SPLIT);
            int[] cell = fields.length == 3 ? cell(fields) : null;
            if (cell == null) {
                problems.add(name + ":" + cellLine.number() + ": expected row,col,color, found: "
                        + cellLine.body());
                continue;
            }
            cells.add(cell);
        }
        return cells;
    }

    /** The row, column and color of a data line, or null when one is not a number. */
    private static int[] cell(String[] fields) {
        Integer row   = toInt(fields[0]);
        Integer col   = toInt(fields[1]);
        Integer color = toInt(fields[2]);
        if (row == null || col == null || color == null) {
            return null;
        }
        return new int[]{row, col, color};
    }

    // ==================================================================
    // Validation
    // ==================================================================

    /**
     * Checks that there is at least one level, then the values and the cells of
     * every one, and throws with every problem found, so the caller only has to
     * show the message.
     *
     * @throws IllegalStateException when the data is not usable
     */
    void validate() {
        List<String> all = new ArrayList<>(problems);
        if (levels.isEmpty()) {
            all.add(MASTER_NAME + ": at least one level is required");
        }
        for (int i = 0; i < levels.size(); i++) {
            check("level " + (i + 1), levels.get(i), all);
        }
        if (!all.isEmpty()) {
            throw invalid(all);
        }
    }

    /**
     * Reads the obstacles of one level and places them on the board, leaving the
     * board empty when the level has no obstacle file.
     * <p>
     * The cells are checked against the geometry before the board is touched, so
     * an unusable file leaves the board as it was. This is what the editor uses to
     * open a level on its single working board, one level at a time: the three
     * values of the master table are already known, only the cells are read here.
     *
     * @param id        id of the level, the one its obstacle file carries
     * @param cellLines text lines of the obstacle file, or null when it has none
     * @throws IllegalStateException when the file has a problem
     */
    void fillBoard(int id, List<TextLine> cellLines) {
        problems.clear();
        List<int[]> cells = cells(id, cellLines);
        List<String> all = new ArrayList<>(problems);
        checkCells("level " + id, cells, all);
        if (!all.isEmpty()) {
            throw invalid(all);
        }

        board.clear();
        for (int[] cell : cells) {
            board.place(cell[0], cell[1], cell[2]);
        }
    }

    /** The error of a set of problems, with the count in the header. */
    private static IllegalStateException invalid(List<String> problems) {
        return new IllegalStateException("Invalid level data (" + problems.size()
                + (problems.size() == 1 ? " problem" : " problems") + "):\n  "
                + String.join("\n  ", problems));
    }

    /** The rules of one level: usable values, and cells the board accepts. */
    private void check(String at, Level level, List<String> problems) {
        if (level.linesRequired() < MIN_LINES) {
            problems.add(at + ": linesRequired must be at least " + MIN_LINES + ", found "
                    + level.linesRequired());
        }
        if (level.numPieces() < 1 || level.numPieces() > Piece.Type.count()) {
            problems.add(at + ": numPieces must be between 1 and " + Piece.Type.count()
                    + " (defined pieces), found " + level.numPieces());
        }
        checkCells(at, level.cells(), problems);
    }

    /** The rules of the cells: inside the board, a palette colour and no repeats. */
    private void checkCells(String at, List<int[]> cells, List<String> problems) {
        boolean[] taken = new boolean[board.rows() * board.cols()];
        for (int[] cell : cells) {
            int row   = cell[0];
            int col   = cell[1];
            int color = cell[2];

            if (!board.inBounds(row, col)) {
                problems.add(at + ": obstacle at (" + row + "," + col + ") is outside the board"
                        + " (rows 0.." + (board.rows() - 1) + ", columns 0.."
                        + (board.cols() - 1) + ")");
            } else if (color < MIN_COLOR || color > MAX_COLOR) {
                problems.add(at + ": color " + color + " is not a palette color (use "
                        + MIN_COLOR + ".." + MAX_COLOR + ") at (" + row + "," + col + ")");
            } else if (taken[row * board.cols() + col]) {
                problems.add(at + ": duplicated obstacle at (" + row + "," + col + ")");
            } else {
                taken[row * board.cols() + col] = true;
            }
        }
    }

    // ==================================================================
    // Text lines and fields
    // ==================================================================

    /**
     * The text lines of a file, each one with the number it occupies there. A
     * line that is only a comment (it starts with {@code #}) is not data and is
     * left out; the ones that follow keep their real number.
     */
    static List<TextLine> textLines(String text) {
        List<TextLine> textLines = new ArrayList<>();
        int number = 1;
        for (String textLine : text.lines().toList()) {
            if (!textLine.stripLeading().startsWith("#")) {
                textLines.add(new TextLine(number, textLine));
            }
            number++;
        }
        return textLines;
    }

    /**
     * The data lines of a text file: blank lines and trailing comments (everything
     * from a {@code #}) are left out, the number of each line is kept.
     */
    private static List<TextLine> dataLines(List<TextLine> textLines) {
        List<TextLine> data = new ArrayList<>();
        for (TextLine textLine : textLines) {
            String body = stripComment(textLine.body()).trim();
            if (!body.isEmpty()) {
                data.add(new TextLine(textLine.number(), body));
            }
        }
        return data;
    }

    /** true when the first data line of the table is a header: it does not start with a number. */
    private static boolean isHeader(TextLine tableLine) {
        return toInt(tableLine.body().split(FIELD_SPLIT)[0]) == null;
    }

    /**
     * true when the text line carries the three values of a level: a blank line,
     * a comment or the header of the table does not. The editor uses it to tell
     * apart the text lines it writes back at the top of the file.
     */
    static boolean isLevelTextLine(String textLine) {
        String body = stripComment(textLine).trim();
        if (body.isEmpty()) {
            return false;
        }
        String[] fields = body.split(FIELD_SPLIT);
        return fields.length >= 3 && toInt(fields[0]) != null;
    }

    /** Removes everything from the first {@code #} (a comment) to the end of the line. */
    private static String stripComment(String textLine) {
        int hash = textLine.indexOf('#');
        return hash < 0 ? textLine : textLine.substring(0, hash);
    }

    /** The value of a field, or null when it is not a non-negative integer. */
    private static Integer toInt(String field) {
        try {
            int value = Integer.parseInt(field);
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts true/false, 1/0 and yes/no (case insensitive), null if not. */
    private static Boolean toFlag(String field) {
        switch (field.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y": return Boolean.TRUE;
            case "false", "0", "no", "n": return Boolean.FALSE;
            default:                      return null;
        }
    }

    /** Name of the obstacle file of level {@code id}, as the messages show it. */
    private static String fileOf(int id) {
        return "levels/level_" + id + OBSTACLE_EXT;
    }

    // ==================================================================
    // Access to the classpath
    // ==================================================================

    /**
     * Reads a classpath resource into its text lines: null when it does not exist
     * and the file is optional (a level without obstacles).
     */
    private List<TextLine> openTextLines(String path, String name, boolean required) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException(name + " not found in the classpath (" + path
                            + ")");
                }
                return null;
            }
            return textLines(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(name + ": cannot be read: " + e.getMessage(), e);
        }
    }

    // ==================================================================
    // The level
    // ==================================================================

    /**
     * One level of the game: the four values that define it. Three of them come
     * from one text line of {@code levels.csv} and the fourth from the optional
     * obstacle file of that line, so they travel together.
     *
     * @param linesRequired    completed lines needed to clear the level
     * @param difficultyActive whether extra blocks appear after each lock
     * @param numPieces        how many piece types may appear (the first N of
     *                         {@link Piece.Type})
     * @param cells            obstacles, each one {@code {row, col, color}}; empty
     *                         when the level has no obstacle file
     */
    record Level(int linesRequired, boolean difficultyActive, int numPieces, List<int[]> cells) {

        /** Places the obstacles on the board. */
        void fill(Board board) {
            for (int[] cell : cells) {
                board.place(cell[0], cell[1], cell[2]);
            }
        }
    }
}
