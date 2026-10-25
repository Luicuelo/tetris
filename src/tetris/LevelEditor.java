package tetris;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

/**
 * Level editor of the Tetris: paints the obstacles of the screens defined in
 * {@code src/tetris/levels.csv} and {@code src/tetris/levels/level_N.txt}.
 * <p>
 * Same pattern as the game ({@link Tetris}): a JavaFX application with a canvas
 * and a renderer that composes the picture. There is no animation loop — nothing
 * moves here — and the controls are ordinary JavaFX buttons beside the canvas,
 * because a level editor gains nothing from painting its own widgets.
 * <p>
 * The canvas is the board, cell for cell as the game draws it. Beside it are the
 * native controls — screen buttons, colour matrix, selected colour — and below
 * them the number of the screen in the bitmap font of the markers. The mouse
 * paints: the selected colour fills the cell under the pointer, and the white
 * swatch erases it (the cell stays empty, so the file keeps no line for it).
 * <p>
 * The editor has a single working board, the one the canvas paints: opening a
 * screen reads its file into that board and leaving it writes the board back,
 * only when something changed. The screen being edited is the only one in
 * memory, as in the game. Closing the window with unsaved cells asks whether to
 * save them. A new screen is appended at the end with the default values of the
 * format; the three values of the master table are not edited here. The editor
 * writes to {@code src}, so the game shows the change on its next build.
 */
public class LevelEditor extends Application {

    /** Board width: ten cells of 32 px, the cell size the game paints. */
    private static final int CANVAS_WIDTH  = 320;
    /** Board height: the twenty rows of the board at 32 px. */
    private static final int CANVAS_HEIGHT = 640;

    /** Height of the buttons, the same bar the game uses. */
    private static final int BUTTON_HEIGHT = 25;
    /** Width of a screen/edit button: half the colour matrix. */
    private static final int BUTTON_WIDTH  = 75;

    /** Side of one colour swatch and gap between swatches. */
    private static final int SWATCH_SIZE = 28;
    private static final int SWATCH_GAP  = 4;
    /** Swatches per row of the colour matrix. */
    private static final int COLOUR_COLUMNS = 5;
    /** Colours offered: the ten colours of the forms (values 1..10). */
    private static final int COLOUR_COUNT = Palette.N_FORMCOLORS;

    /** Selected colour that means "erase": it is not a palette value. */
    private static final int ERASER = 0;

    /** Cells wide of the screen marker: one per digit, as the game's markers. */
    private static final int NUMBER_COLUMNS = DigitFont.MARKER_DIGITS;
    /** Cells high of the screen marker. */
    private static final int NUMBER_ROWS = 2;
    /** Side of one cell of the screen marker, the cell size of the board. */
    private static final int NUMBER_CELL = 32;
    /** Size of the screen marker in pixels. */
    private static final int NUMBER_WIDTH  = NUMBER_COLUMNS * NUMBER_CELL;
    private static final int NUMBER_HEIGHT = NUMBER_ROWS * NUMBER_CELL;

    /** The 20×10 playfield: the screen being edited, and the geometry of the picture. */
    private final Board board = new Board();
    /** Palette the swatches take their colours from. */
    private final Palette palette = new Palette();

    private Canvas canvas;
    private EditorRenderer renderer;
    private LevelFiles files;
    /** Pixel layer of the small canvas that shows the number of the screen. */
    private CellPainter numberPainter;

    /** Screen being edited (0-based: screen 1 is index 0). */
    private int screenIndex;
    /** Colour the mouse paints with: a palette value, or {@link #ERASER}. */
    private int selectedColour = 1;
    /** true when the data changed since the last write. */
    private boolean pendingChanges;

    /** The rectangle that shows the selected colour. */
    private Rectangle selectedColourView;
    private Button removeButton;

    /**
     * Entry point: launches the JavaFX application.
     *
     * @param args command line arguments (none are used)
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Starts the editor: opens the level files, builds the window and paints the
     * first screen.
     *
     * @param primaryStage the primary stage
     */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Tetris FX - Level editor");

        try {
            files = new LevelFiles(board);
            files.loadMaster();
        } catch (IllegalStateException e) {
            showError("Cannot open the levels", e.getMessage());
            Platform.exit();
            return;
        }

        canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        renderer = new EditorRenderer(gc, CANVAS_WIDTH, CANVAS_HEIGHT, board);
        canvas.setOnMousePressed(e -> paintCellAt(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> paintCellAt(e.getX(), e.getY()));

        BorderPane root = new BorderPane();
        root.setCenter(canvas);
        BorderPane.setMargin(canvas, new Insets(12));
        root.setRight(buildTools());

        primaryStage.setScene(new Scene(root));
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(event -> {
            if (!confirmSave()) {
                event.consume();       // keep the window, and the changes, open
            }
        });
        primaryStage.show();

        if (!openScreen(0)) {
            Platform.exit();
        }
    }

    // ==================================================================
    // Controls
    // ==================================================================

    /**
     * Builds the column of controls to the right of the canvas: the screen
     * buttons, the colour matrix, the selected-colour rectangle and, at the
     * bottom, the number of the screen.
     */
    private VBox buildTools() {
        Button previousButton = button("<", () -> changeScreen(-1));
        Button nextButton     = button(">", () -> changeScreen(1));
        Button addButton      = button("+", this::addScreen);
        removeButton          = button("-", this::deleteScreen);
        removeButton.setDisable(files.count() <= 1);

        GridPane colours = new GridPane();
        colours.setHgap(SWATCH_GAP);
        colours.setVgap(SWATCH_GAP);
        for (int colour = 1; colour <= COLOUR_COUNT; colour++) {
            colours.add(swatch(colour), (colour - 1) % COLOUR_COLUMNS,
                                        (colour - 1) / COLOUR_COLUMNS);
        }
        colours.add(swatch(ERASER), COLOUR_COUNT % COLOUR_COLUMNS,
                                    COLOUR_COUNT / COLOUR_COLUMNS);

        int matrixWidth = COLOUR_COLUMNS * SWATCH_SIZE + (COLOUR_COLUMNS - 1) * SWATCH_GAP;
        selectedColourView = new Rectangle(matrixWidth, SWATCH_SIZE);
        selectedColourView.setStroke(Color.BLACK);
        selectedColourView.setFill(colourOf(selectedColour));

        Canvas numberCanvas = new Canvas(NUMBER_WIDTH, NUMBER_HEIGHT);
        numberPainter = new CellPainter(numberCanvas.getGraphicsContext2D(),
                                        NUMBER_WIDTH, NUMBER_HEIGHT,
                                        NUMBER_COLUMNS, NUMBER_ROWS);

        VBox tools = new VBox(12,
                new HBox(6, previousButton, nextButton),
                new HBox(6, addButton, removeButton),
                colours,
                selectedColourView,
                numberCanvas);
        tools.setPadding(new Insets(12));
        return tools;
    }

    /** One button of the tool column. */
    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setPrefSize(BUTTON_WIDTH, BUTTON_HEIGHT);
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    /**
     * One square of the colour matrix: the colour itself when it is a palette
     * value, white when it is the eraser.
     * <p>
     * The background goes through the inline style because the stylesheet of the
     * buttons would win over {@code setBackground}.
     */
    private Button swatch(int colour) {
        Button swatch = new Button();
        swatch.setPrefSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setMinSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setMaxSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setFocusTraversable(false);
        swatch.setStyle("-fx-background-radius: 0; -fx-padding: 0;"
                + " -fx-background-color: " + web(colourOf(colour)) + ";"
                + " -fx-border-color: black;");
        swatch.setOnAction(e -> selectColour(colour));
        if (colour == ERASER) {
            swatch.setTooltip(new Tooltip("Erase"));
        }
        return swatch;
    }

    /** Chooses the colour the mouse paints with and updates the rectangle. */
    private void selectColour(int colour) {
        selectedColour = colour;
        selectedColourView.setFill(colourOf(colour));
    }

    /**
     * Colour of a palette value, read from the same BGRA blocks the renderer
     * paints with. The eraser is plain white: it is a mode, not a colour.
     */
    private Color colourOf(int colour) {
        if (colour == ERASER) {
            return Color.WHITE;
        }
        byte[] bgra = palette.pixel(Palette.indexOfValue(colour), Palette.BASE);
        return Color.rgb(bgra[2] & 0xFF, bgra[1] & 0xFF, bgra[0] & 0xFF);
    }

    /** CSS colour of a JavaFX colour, for the inline style of a swatch. */
    private static String web(Color colour) {
        return String.format("#%02X%02X%02X",
                Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255),
                Math.round(colour.getBlue() * 255));
    }

    // ==================================================================
    // Painting with the mouse
    // ==================================================================

    /**
     * Paints the cell under a canvas point with the selected colour, or empties
     * it when the eraser is selected. The cell must have the wanted content
     * already for the picture and the files to stay as they are.
     */
    private void paintCellAt(double x, double y) {
        EditorRenderer.Cell cell = renderer.cellAt(x, y);
        if (cell == null) return;

        int current = board.valueAt(cell.row(), cell.column());

        if (selectedColour == ERASER) {
            if (current == 0) return;
            board.clearCell(cell.row(), cell.column());
        } else {
            if (current == selectedColour) return;
            board.place(cell.row(), cell.column(), selectedColour);
        }

        pendingChanges = true;
        refresh();
    }

    // ==================================================================
    // Screens
    // ==================================================================

    /**
     * Moves to another screen, wrapping around the ends, after writing the
     * changes of the current one. The file of the next screen is read into the
     * same board.
     *
     * @param delta -1 = previous, +1 = next
     */
    private void changeScreen(int delta) {
        if (!saveIfNeeded()) return;
        openScreen(Math.floorMod(screenIndex + delta, files.count()));
    }

    /**
     * Appends a screen with the default values of the format and moves to it. The
     * new screen has no file yet, so the board opens empty; the master table gains
     * its line at once, because that line is the only way to see a screen that has
     * no obstacles file.
     */
    private void addScreen() {
        if (!saveIfNeeded()) return;
        screenIndex = files.add();
        updateRemoveButton();
        openScreen(screenIndex);
        saveScreenList();
    }

    /**
     * Asks for confirmation and deletes the screen being edited. The screens
     * that follow keep their content and are renumbered by the write, so the
     * window shows the screen that takes its place.
     */
    private void deleteScreen() {
        if (files.count() <= 1) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete screen " + (screenIndex + 1) + "? The screens that follow are "
                        + "renumbered, their level files included.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Tetris FX - Level editor");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        if (!saveIfNeeded()) return;

        try {
            files.remove(screenIndex);
        } catch (IllegalStateException e) {
            showError("Cannot delete screen " + (screenIndex + 1), e.getMessage());
            return;
        }
        screenIndex = Math.min(screenIndex, files.count() - 1);
        updateRemoveButton();
        openScreen(screenIndex);
        saveScreenList();
    }

    /** The last screen cannot be deleted: the game needs at least one. */
    private void updateRemoveButton() {
        removeButton.setDisable(files.count() <= 1);
    }

    /** Repaints the canvas and the number of the screen. */
    private void refresh() {
        renderer.paint(board);

        numberPainter.paintMarker(screenIndex + 1, 0, 0, NUMBER_WIDTH, NUMBER_HEIGHT,
                                  Palette.MARKER_BG, Palette.DIGIT_BLUE);
        numberPainter.present();
    }

    // ==================================================================
    // The working board and the files
    // ==================================================================

    /**
     * Opens screen {@code index} on the working board: its file is read into the
     * board, which is the only one the editor holds. A file that cannot be read
     * leaves the board as it was, and the current screen stays open.
     *
     * @return false when the screen could not be opened
     */
    private boolean openScreen(int index) {
        try {
            files.loadInto(index);
        } catch (IllegalStateException e) {
            showError("Cannot open screen " + (index + 1), e.getMessage());
            return false;
        }
        screenIndex = index;
        pendingChanges = false;
        refresh();
        return true;
    }

    /**
     * Writes the screen being edited when something changed since it was opened
     * or last written.
     *
     * @return false when the file could not be written
     */
    private boolean saveIfNeeded() {
        if (!pendingChanges) return true;
        try {
            files.saveFrom(screenIndex);
            pendingChanges = false;
            return true;
        } catch (IllegalStateException e) {
            showError("Cannot save the levels", e.getMessage());
            return false;
        }
    }

    /** Writes the master table after a screen is added or removed. */
    private void saveScreenList() {
        try {
            files.saveMaster();
        } catch (IllegalStateException e) {
            showError("Cannot save the levels", e.getMessage());
        }
    }

    /**
     * Asks about the unsaved cells when the window is closing.
     *
     * @return false when the window has to stay open: the answer was 'cancel' or
     *         the file could not be written
     */
    private boolean confirmSave() {
        if (!pendingChanges) return true;

        ButtonType save    = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "Save the changes of screen " + (screenIndex + 1) + "?",
                save, discard, ButtonType.CANCEL);
        ask.setTitle("Tetris FX - Level editor");
        ask.setHeaderText(null);

        ButtonType answer = ask.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == ButtonType.CANCEL) return false;
        return answer != save || saveIfNeeded();
    }

    /** Reports a problem with the files without leaving the editor. */
    private static void showError(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Tetris FX - Level editor");
        alert.setHeaderText(header);
        alert.setContentText(detail);
        alert.getDialogPane().setPrefWidth(640);
        alert.setResizable(true);
        alert.showAndWait();
    }
}
