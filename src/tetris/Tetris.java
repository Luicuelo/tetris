package tetris;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * Main application of the Tetris: window, canvas and keyboard.
 * <p>
 * It follows the common JavaFX pattern: Application + Canvas + Renderer +
 * WritableImage/PixelWriter + AnimationTimer. The timer pulses at the refresh
 * rate of the monitor, but the game does not follow it:
 * {@link FrameTimer} turns the elapsed time into ticks, so the engine always
 * runs at 60 ticks per second while the picture is composed every
 * {@code TICKS_PER_PAINT} ticks (four of them, ~15 pictures per second).
 * <p>
 * Keys (as in modern Tetris): cursor left/right to move, Z to turn
 * counter-clockwise, X or Up to turn clockwise, Down for a fast drop and P to
 * pause. The Restart button restarts from the screen 1.
 * <p>
 * Canvas size configurable via command line: --width=xx --height=yy. The
 * board keeps its 10x20 cells: the Renderer derives the pixels per cell from
 * the canvas, so a bigger window paints the very same layout bigger. A width
 * below 105 pixels or a height below 140 (7 pixels per logical cell) falls
 * back to its default, because below that the digits no longer fit inside the
 * markers.
 */
public class Tetris extends Application {

    /** Grid columns: board 10 + separator 1 + panel 4. */
    private static final int GRID_COLUMNS = 15;
    /** Grid rows: the twenty rows of the board. */
    private static final int GRID_ROWS = 20;
    /** Smallest canvas that keeps every cell and marker digit intact. */
    private static final int MIN_WIDTH  = GRID_COLUMNS * CellPainter.MIN_PIXEL_SIZE;
    private static final int MIN_HEIGHT = GRID_ROWS * CellPainter.MIN_PIXEL_SIZE;

    /** 15 logical cells wide (board 10 + separator 1 + panel 4) at 32 px. */
    private static final int DEFAULT_WIDTH = 480;
    /** 20 logical cells high (the rows of the board) at 32 px. */
    private static final int DEFAULT_HEIGHT = 640;
    private static final int BUTTON_HEIGHT = 25;
    /**
     * Ticks of engine between two pictures: four of them, so the screen is
     * composed at ~15 pictures per second. The fastest thing the picture can
     * change is the flash of a completed line, a step every six ticks, so a
     * picture every four ticks still shows every step and a faster rate would
     * only repeat the same image.
     */
    private static final int TICKS_PER_PAINT = 4;

    private int canvasWidth;
    private int canvasHeight;
    private Canvas canvas;
    private Engine engine;
    private Renderer renderer;
    private AnimationTimer animationTimer;
    /** Elapsed time of the pulses, turned into ticks of the game. */
    private final FrameTimer frameTimer = new FrameTimer();
    private int ticksSincePaint;
    private boolean finished = false;

    /**
     * Entry point: launches the JavaFX application.
     * <p>
     * A {@code main} inside the {@code Application} subclass is allowed; with
     * the JavaFX SDK on the module path, {@code java tetris.Tetris} starts
     * the game and passes the named arguments ({@code --width}, {@code --height})
     * to {@link #start(Stage)}.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Starts the JavaFX application and initializes the user interface.
     *
     * @param primaryStage the primary stage for this application
     */
    @Override
    public void start(Stage primaryStage) {
        // Parse command line arguments for canvas size
        parseSize(getParameters());

        primaryStage.setTitle("Tetris FX");

        // Create and initialize
        initialize();

        setupKeyListeners();

        // Buttons
        Button restartButton = new Button("Restart");
        restartButton.setOnAction(e -> restart());
        restartButton.setPrefHeight(BUTTON_HEIGHT);

        // Pause button
        Button pauseButton = new Button("Pause");
        pauseButton.setOnAction(e -> pause());
        pauseButton.setPrefHeight(BUTTON_HEIGHT);

        // Layout
        BorderPane root = new BorderPane();
        root.setCenter(canvas);

        HBox buttons = new HBox();
        root.setBottom(buttons);
        buttons.setPadding(new Insets(0, 10, 0, 0));
        buttons.setSpacing(10);
        buttons.getChildren().addAll(restartButton, pauseButton);

        // Create scene and show
        Scene scene = new Scene(root, canvasWidth, canvasHeight + BUTTON_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();

        // The keys go to the canvas: it must be able to take the focus
        canvas.setFocusTraversable(true);
        canvas.requestFocus();
    }

    /**
     * Parses command line arguments for canvas size. Any size of at least
     * 105 × 140 pixels works: the renderer scales the cells to fit it and
     * letterboxes the leftovers. A smaller width or height falls back to its
     * default, because the marker digits would no longer fit.
     *
     * @param params the parameters to parse
     */
    private void parseSize(Parameters params) {
        canvasWidth  = namedSize(params, "width",  DEFAULT_WIDTH,  MIN_WIDTH);
        canvasHeight = namedSize(params, "height", DEFAULT_HEIGHT, MIN_HEIGHT);
    }

    /**
     * One size named on the command line: the default when the argument is
     * absent, not a number or below the minimum. A typo never stops the game.
     *
     * @param params   the parameters to read
     * @param name     name of the argument ({@code width}, {@code height})
     * @param fallback size used when the argument is missing or unusable
     * @param minimum  smallest accepted size in pixels
     */
    private static int namedSize(Parameters params, String name, int fallback, int minimum) {
        String value = params.getNamed().get(name);
        if (value == null) return fallback;
        try {
            int size = Integer.parseInt(value);
            if (size < minimum) {
                System.err.println("--" + name + " must be at least " + minimum
                        + ", using " + fallback);
                return fallback;
            }
            return size;
        } catch (NumberFormatException e) {
            System.err.println("Invalid --" + name + ", using " + fallback);
            return fallback;
        }
    }

    /**
     * Initializes the canvas and starts the animation loop.
     */
    private void initialize() {
        canvas = new Canvas(canvasWidth, canvasHeight);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        finished = false;

        // The application is the root of the composition: it owns the game and
        // the audio implementation, and hands both over. The renderer only
        // reads the game; the game-over darkening is read once per picture, so
        // the renderer never sees the effects.
        engine   = new Engine(new JavaFxSound());
        renderer = new Renderer(gc, canvasWidth, canvasHeight, engine.gameState());

        animationTimer = new AnimationTimer() {
            /**
             * One pulse: advances the engine the ticks that fit in the elapsed
             * time and, every {@code TICKS_PER_PAINT} ticks, paints the screen.
             */
            @Override
            public void handle(long now) {
                if (finished || renderer == null || engine == null) {
                    return;
                }
                try {
                    int ticks = frameTimer.ticksFor(now);
                    for (int i = 0; i < ticks; i++) {
                        engine.tick();
                    }
                    ticksSincePaint += ticks;
                    if (ticksSincePaint >= TICKS_PER_PAINT) {
                        ticksSincePaint = 0;      // one picture per pulse at most
                        renderer.paint(engine.fadeProgress());
                    }
                    if (!canvas.isFocused()) {
                        canvas.requestFocus();
                    }
                } catch (Exception e) {
                    System.err.println("Error updating : " + e.getMessage());
                    stopAnimation();
                }
            }
        };
        animationTimer.start();
    }

    /**
     * Configures the keyboard, with the keys of modern Tetris: cursor
     * left/right, Z to turn counter-clockwise, X or Up to turn clockwise, Down
     * for the fast drop, P to pause.
     * <p>
     * This method translates a key into a game intention; the intention is the
     * engine's business. No game rule lives here.
     */
    private void setupKeyListeners() {
        canvas.setOnKeyPressed(e -> {
            if (engine == null) {
                return;
            }
            switch (e.getCode()) {
                case LEFT  -> engine.moveLateral(-1);
                case RIGHT -> engine.moveLateral(1);
                case Z     -> engine.moveRotate(-1);
                case UP, X -> engine.moveRotate(1);
                case DOWN  -> engine.fastDrop();
                case P     -> engine.togglePause();
                default    -> { }
            }
        });
    }

    /**
     * Restarts the game from screen 1.
     */
    private void restart() {
        if (engine != null) {
            engine.restart();
            canvas.requestFocus();
        }
    }

    /**
     * Toggles the pause of the game (the graphics darken while paused).
     */
    private void pause() {
        if (engine != null) {
            engine.togglePause();
        }
    }

    /**
     * Stops the animation timer and cleans up resources.
     */
    private void stopAnimation() {
        finished = true;
        if (animationTimer != null) {
            animationTimer.stop();
        }
    }

    /**
     * Stops the application and cleans up resources before exiting.
     */
    @Override
    public void stop() {
        stopAnimation();
        renderer = null;
        engine   = null;
        Platform.exit();
    }
}
