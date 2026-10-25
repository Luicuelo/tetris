package principal;

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
 * It follows the common pattern of the projects of the house: Application +
 * Canvas + Renderer + WritableImage/PixelWriter + AnimationTimer. The timer
 * pulses at the refresh rate of the monitor, but the game does not follow it:
 * {@link FrameTimer} turns the elapsed time into ticks, so the engine always
 * runs at 60 ticks per second and the picture is composed every two ticks.
 * <p>
 * Keys (as in modern Tetris): cursor left/right to move, Z to turn
 * counter-clockwise, X or Up to turn clockwise, Down for a fast drop and P to
 * pause. The Restart button restarts from the screen 1.
 * <p>
 * Canvas size configurable via command line: --width=xx --height=yy. The
 * board keeps its 10x20 cells: the Renderer derives the pixels per cell from
 * the canvas, so a bigger window paints the very same layout bigger.
 */
public class Tetris extends Application {

    /** 15 logical cells wide (board 10 + separator 1 + panel 4) at 32 px. */
    private static final int DEFAULT_WIDTH = 480;
    /** 20 logical cells high (the rows of the board) at 32 px. */
    private static final int DEFAULT_HEIGHT = 640;
    private static final int BUTTON_HEIGHT = 25;
    /** Ticks of engine between two pictures: the screen is composed at ~30 fps. */
    private static final int TICKS_PER_PAINT = 2;

    private int canvasWidth;
    private int canvasHeight;
    private Canvas canvas;
    private Game game;
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
     * the JavaFX SDK on the module path, {@code java principal.Tetris} starts
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

        // Buttons (same bar as the rest of the projects)
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
     * Parses command line arguments for canvas size. Any positive size works:
     * the renderer scales the cells to fit it and letterboxes the leftovers.
     *
     * @param params the parameters to parse
     */
    private void parseSize(Parameters params) {
        try {
            if (params.getNamed().containsKey("width")) {
                canvasWidth = Integer.parseInt(params.getNamed().get("width"));
            } else {
                canvasWidth = DEFAULT_WIDTH;
            }

            if (params.getNamed().containsKey("height")) {
                canvasHeight = Integer.parseInt(params.getNamed().get("height"));
            } else {
                canvasHeight = DEFAULT_HEIGHT;
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid size parameters, using defaults");
            canvasWidth = DEFAULT_WIDTH;
            canvasHeight = DEFAULT_HEIGHT;
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
        // reads the game.
        game = new Game(new JavaFxSound());
        renderer = new Renderer(gc, canvasWidth, canvasHeight, game);

        animationTimer = new AnimationTimer() {
            /**
             * One pulse: advances the engine the ticks that fit in the elapsed
             * time and, every two ticks, paints the screen.
             */
            @Override
            public void handle(long now) {
                if (finished || renderer == null || game == null) {
                    return;
                }
                try {
                    int ticks = frameTimer.ticksFor(now);
                    for (int i = 0; i < ticks; i++) {
                        game.tick();
                    }
                    ticksSincePaint += ticks;
                    if (ticksSincePaint >= TICKS_PER_PAINT) {
                        ticksSincePaint = 0;      // one picture per pulse at most
                        renderer.paint();
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
            if (game == null) {
                return;
            }
            switch (e.getCode()) {
                case LEFT  -> game.moveLateral(-1);
                case RIGHT -> game.moveLateral(1);
                case Z     -> game.moveRotate(-1);   // -1 = counter-clockwise
                case UP, X -> game.moveRotate(1);    // +1 = clockwise
                case DOWN  -> game.fastDrop();
                case P     -> game.togglePause();
                default    -> { }
            }
        });
    }

    /**
     * Restarts the game from screen 1.
     */
    private void restart() {
        System.out.println("Restart ...");
        if (game != null) {
            game.restart();
            canvas.requestFocus();   // the focus returns to the game
        }
    }

    /**
     * Toggles the pause of the game (the graphics dim while paused).
     */
    private void pause() {
        System.out.println("Pause ...");
        if (game != null) {
            game.togglePause();
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
        game = null;
        System.out.println("destroyed");
        Platform.exit();
    }
}
