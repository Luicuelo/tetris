package principal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the level definitions and keeps them in memory.
 * <p>
 * Two plain-text formats, both dependency-free:
 * <ul>
 *   <li>{@code /principal/levels.csv} - master table, one line per screen:
 *       {@code linesRequired,difficultyActive,numPieces}. The screen id is its
 *       1-based line order, so no id column is needed.</li>
 *   <li>{@code /principal/levels/level_&lt;id&gt;.txt} - the obstacles of that
 *       screen, one cell per line as {@code row,col,color}. A missing file
 *       simply means the screen has no obstacles.</li>
 * </ul>
 * In both files blank lines and everything after a {@code '#'} are ignored,
 * fields may be separated by commas, semicolons or spaces, and the first data
 * line of the CSV may be a header (detected by its non-numeric first field).
 * <p>
 * Everything is validated against the real board: coordinates inside the
 * board, colors within the drawable palette range, no duplicated cells and
 * {@code numPieces} within the defined pieces. All problems found are reported
 * together, with file and line number, instead of failing on the first one.
 * <p>
 * Call {@link #load()} once at startup; afterwards use {@link #count()},
 * {@link #linesRequired(int)}, {@link #difficultyActive(int)},
 * {@link #numPieces(int)} and {@link #obstacles(int)} (0-based index).
 */
class Levels {

    private static final String MASTER_PATH   = "/principal/levels.csv";
    private static final String OBSTACLE_PATH = "/principal/levels/level_";
    private static final String OBSTACLE_EXT  = ".txt";

    /** Separator: comma, semicolon or whitespace. */
    private static final String FIELD_SPLIT = "[,;\\s]+";

    /** Obstacle colors accepted: the drawable palette entries of the forms. */
    private static final int MIN_COLOR = 1;
    private static final int MAX_COLOR = Palette.LAST_COLORS;
    /** A screen needs at least this many lines to be clearable. */
    private static final int MIN_LINES = 1;

    /** Maximum number of problems listed in one error message. */
    private static final int MAX_REPORTED = 25;

    private static final int[][] NO_OBSTACLES = new int[0][];

    /** Board that gives the geometry the obstacles are validated against. */
    private final Board board;

    private int[]      linesRequired    = new int[0];
    private boolean[]  difficultyActive = new boolean[0];
    private int[]      numPieces        = new int[0];
    private int[][][]  obstacles        = new int[0][][];

    Levels(Board board) {
        this.board = board;
    }

    /** Number of screens loaded. */
    int count() {
        return linesRequired.length;
    }

    /** Lines required to clear screen {@code n} (0-based). */
    int linesRequired(int n) { return linesRequired[n]; }

    /** Whether difficulty mode is active for screen {@code n} (0-based). */
    boolean difficultyActive(int n) { return difficultyActive[n]; }

    /** Number of piece types for screen {@code n} (0-based). */
    int numPieces(int n) { return numPieces[n]; }

    /**
     * Obstacles for screen {@code n} (0-based).
     * Each entry is {@code [row, col, color]}. Empty when the screen has no
     * obstacle file.
     */
    int[][] obstacles(int n) { return obstacles[n]; }

    // ==================================================================
    // Loading
    // ==================================================================

    /**
     * Reads the master CSV and every screen obstacle file from the classpath.
     *
     * @throws IllegalStateException if a file is missing, unreadable or
     *         contains data outside the board or the palette
     */
    void load() {
        List<String>  errors = new ArrayList<>();
        ArrayList<int[]>     scalars = new ArrayList<>();   // {linesRequired, numPieces}
        ArrayList<Boolean>   harder  = new ArrayList<>();   // difficultyActive

        readMaster(scalars, harder, errors);

        ArrayList<int[][]> boards = new ArrayList<>(scalars.size());
        for (int i = 0; i < scalars.size(); i++) {
            boards.add(readObstacles(i + 1, errors));
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(report(errors));
        }

        int n = scalars.size();
        linesRequired    = new int[n];
        difficultyActive = new boolean[n];
        numPieces        = new int[n];
        obstacles        = new int[n][][];
        for (int k = 0; k < n; k++) {
            linesRequired[k]    = scalars.get(k)[0];
            difficultyActive[k] = harder.get(k);
            numPieces[k]        = scalars.get(k)[1];
            obstacles[k]        = boards.get(k);
        }
    }

    /** screen id (1-based) of the obstacle file that matches index {@code n}. */
    private static String fileOf(int id) {
        return "levels/level_" + id + OBSTACLE_EXT;
    }

    // ------------------------------------------------------------------
    // Master table
    // ------------------------------------------------------------------

    private void readMaster(List<int[]> scalars, List<Boolean> harder, List<String> errors) {
        try (BufferedReader in = open(MASTER_PATH, "levels.csv", true, errors)) {
            if (in == null) return;

            String line;
            int lineNumber = 0;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                String body = stripComment(line).trim();
                if (body.isEmpty()) continue;

                String[] f = body.split(FIELD_SPLIT);
                if (f.length < 3 || (first && !isDigits(f[0]))) {
                    first = false;
                    continue;                       // header or explanatory line
                }
                first = false;

                if (f.length != 3) {
                    errors.add("levels.csv:" + lineNumber + ": expected 3 fields "
                            + "(linesRequired,difficultyActive,numPieces), found " + f.length);
                    continue;
                }
                Integer lines  = number(f[0], "levels.csv", lineNumber, "linesRequired", errors);
                Integer pieces = number(f[2], "levels.csv", lineNumber, "numPieces", errors);
                Boolean diff   = flag(f[1], "levels.csv", lineNumber, errors);

                if (lines != null && lines < MIN_LINES) {
                    errors.add("levels.csv:" + lineNumber + ": linesRequired must be at least "
                            + MIN_LINES + ", found " + lines);
                }
                if (pieces != null && pieces > Piece.Type.count()) {
                    errors.add("levels.csv:" + lineNumber + ": numPieces must be between 1 and "
                            + Piece.Type.count() + " (defined pieces), found " + pieces);
                }
                if (lines == null || pieces == null || diff == null) continue;
                if (pieces == 0) {
                    errors.add("levels.csv:" + lineNumber + ": numPieces must be at least 1");
                    continue;
                }

                scalars.add(new int[]{lines, pieces});
                harder.add(diff);
            }
        } catch (IOException e) {
            errors.add("levels.csv: cannot be read: " + e.getMessage());
        }

        if (scalars.isEmpty() && errors.isEmpty()) {
            errors.add("levels.csv: no screen defined");
        }
    }

    // ------------------------------------------------------------------
    // Obstacles of one screen
    // ------------------------------------------------------------------

    private int[][] readObstacles(int id, List<String> errors) {
        String path = OBSTACLE_PATH + id + OBSTACLE_EXT;
        String name = fileOf(id);

        try (BufferedReader in = open(path, name, false, errors)) {
            if (in == null) {
                return NO_OBSTACLES;               // no file = empty screen
            }

            ArrayList<int[]> cells = new ArrayList<>();
            boolean[] taken = new boolean[board.rows() * board.cols()];
            String line;
            int lineNumber = 0;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                String body = stripComment(line).trim();
                if (body.isEmpty()) continue;

                String[] f = body.split(FIELD_SPLIT);
                if (f.length != 3) {
                    errors.add(name + ":" + lineNumber + ": expected 3 numbers "
                            + "(row,col,color), found " + f.length);
                    continue;
                }
                Integer row   = number(f[0], name, lineNumber, "row", errors);
                Integer col   = number(f[1], name, lineNumber, "col", errors);
                Integer color = number(f[2], name, lineNumber, "color", errors);
                if (row == null || col == null || color == null) continue;

                if (!board.inBounds(row, col)) {
                    errors.add(name + ":" + lineNumber + ": obstacle at (" + row + "," + col
                            + ") is outside the board (rows 0.." + (board.rows() - 1)
                            + ", columns 0.." + (board.cols() - 1) + ")");
                    continue;
                }
                if (color < MIN_COLOR || color > MAX_COLOR) {
                    errors.add(name + ":" + lineNumber + ": color " + color + " is not a palette "
                            + "color (use " + MIN_COLOR + ".." + MAX_COLOR + ") at ("
                            + row + "," + col + ")");
                    continue;
                }
                int cell = row * board.cols() + col;
                if (taken[cell]) {
                    errors.add(name + ":" + lineNumber + ": duplicated obstacle at ("
                            + row + "," + col + ")");
                    continue;
                }
                taken[cell] = true;
                cells.add(new int[]{row, col, color});
            }

            return cells.toArray(NO_OBSTACLES);
        } catch (IOException e) {
            errors.add(name + ": cannot be read: " + e.getMessage());
            return NO_OBSTACLES;
        }
    }

    // ==================================================================
    // Field helpers
    // ==================================================================

    /**
     * Opens a classpath resource. Obstacle files are optional (a missing one
     * just means an empty screen), the master table is not.
     */
    private BufferedReader open(String path, String name, boolean required,
                                List<String> errors) {
        InputStream is = getClass().getResourceAsStream(path);
        if (is == null) {
            if (required) {
                errors.add(name + " not found in the classpath (" + path + ")");
            }
            return null;
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    /** Removes everything from the first '#' (a comment) to the end of the line. */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** True when the text is a run of digits: every field here is non-negative. */
    private static boolean isDigits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    /** Parses a field, recording a typed error and returning null when invalid. */
    private static Integer number(String text, String file, int line, String field,
                                  List<String> errors) {
        if (!isDigits(text)) {
            errors.add(file + ":" + line + ": " + field + " '" + text
                    + "' is not a non-negative integer");
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            errors.add(file + ":" + line + ": " + field + " '" + text + "' is out of range");
            return null;
        }
    }

    /** Accepts true/false, 1/0, yes/no and si/no (case insensitive), null if not. */
    private static Boolean flag(String text, String file, int line, List<String> errors) {
        String v = text.toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "true": case "1": case "yes": case "y": case "si": return Boolean.TRUE;
            case "false": case "0": case "no": case "n": return Boolean.FALSE;
            default:
                errors.add(file + ":" + line + ": difficultyActive '" + text
                        + "' is not a boolean (use true/false)");
                return null;
        }
    }

    /** Builds the single message that lists every problem found. */
    private static String report(List<String> errors) {
        StringBuilder sb = new StringBuilder("Invalid level data (")
                .append(errors.size()).append(" problem").append(errors.size() == 1 ? "" : "s")
                .append("):");
        int shown = Math.min(errors.size(), MAX_REPORTED);
        for (int i = 0; i < shown; i++) {
            sb.append("\n  ").append(errors.get(i));
        }
        if (errors.size() > shown) {
            sb.append("\n  ... ").append(errors.size() - shown).append(" more");
        }
        return sb.toString();
    }
}
