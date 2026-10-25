# Tetris FX

A Tetris game built with JavaFX: an engine ticked 60 times per second — whatever the monitor and however expensive the drawing gets — and a screen composed at 15 frames per second through a `WritableImage` / `PixelWriter` rendering pipeline. It features a progression of 9 screens with hand-crafted obstacle layouts, a set of 10 piece shapes, 3 exotic beyond the classic 7, and level configuration driven by plain text files.

## Features

- 9 screens with unique obstacle layouts, defined in `levels.csv` + `levels/level_N.txt`
- 10 piece shapes: the classic 7 plus 3 exotic ones (2L, 6, 9) configurable per screen
- 3D-relief cell rendering with bright/dark edge flags
- Animated line-clear flash, level-transition fill effect and game-over darkening
- Sound effects (drop, line clear, difficulty block)
- Pause, fast-drop and Restart support
- Proportional scaling: the board keeps its 10x20 cells and the pixels per
  cell are derived from the canvas, so any `--width` / `--height` with a width
  of at least 105 and a height of at least 140 renders the same layout bigger
  (leftovers are letterboxed in black)

## Requirements

| Dependency | Version |
|---|---|
| Java | 17 or later |
| JavaFX | 17 or later |

## Building

From the repository root, with the JavaFX SDK on the module path:

```bash
javac --release 17 \
      --module-path /path/to/javafx-sdk/lib \
      --add-modules javafx.controls,javafx.graphics,javafx.media \
      -d bin \
      src/tetris/*.java
```

## Running

From the repository root (the classpath paths are relative to it):

Linux / macOS:

```bash
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.graphics,javafx.media \
     -cp "bin:src" \
     tetris.Tetris
```

Windows (note the `;` separator, one line to avoid the caret):

```powershell
java --module-path "C:\path\to\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.graphics,javafx.media -cp "bin;src" tetris.Tetris
```

- `src` is on the classpath because it holds the level data and the sounds.
- `javafx.media` is required; without it the game runs muted.

The window size only chooses the pixels per cell (default 480 × 640, i.e. 32 px
per cell for the fixed 15 × 20 grid). The smallest accepted size is 105 × 140
(7 px per cell); a smaller width or height falls back to its default, because
below that the digits no longer fit inside the markers:

```bash
java ... tetris.Tetris --width=750 --height=1000   # 50 px per cell
```

### Running from IntelliJ IDEA

In *Run → Edit Configurations*, the module flags must go in **VM options**
(hidden by default: enable it with *Modify options → Add VM options*), not in
*Program arguments*:

```
--module-path "C:\path\to\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.graphics,javafx.media
```

with **Main class** = `tetris.Tetris`.

JavaFX has to arrive through `--module-path`. If the SDK jars end up on the
classpath instead (adding the SDK as a library is the usual way to get there),
the launcher of the JVM stops before reaching the game with a misleading
`Error: Main method must return a value of type void`: `main` is fine, what
fails is the start of JavaFX.

### Running the level editor

`tetris.LevelEditor` is a second executable of the same package: it paints
the obstacles of a screen with the mouse and writes the level files of `src`.
Run it exactly like the game, changing the main class — same `--module-path`
and `--add-modules`, and the same VM options in the IDE run configuration:

```powershell
java --module-path "C:\path\to\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.graphics,javafx.media -cp "bin;src" tetris.LevelEditor
```

- Left: the board of the current screen, in the grid of the game.
- Right: `<` and `>` change screen (they wrap around at the ends), `+` appends
  a screen with the default values (`10,false,7`), `-` deletes the current one
  after confirmation and the following level files are renumbered. Below them
  are the colour matrix, the rectangle of the selected colour and the number of
  the screen in the bitmap font.
- The colour matrix chooses what the mouse paints; the white square erases
  (the cell is left empty, so its file keeps no line for it).
- The screen being edited is the only one in memory: a single board is filled
  from `level_N.txt` when the screen opens and written back when it is left, and
  only if something changed. Closing the window with unsaved changes asks
  whether to save them. `levels.csv` is rewritten when a screen is added or
  removed, because the id of a level is its position in the table.
- It edits `src/tetris`, the source data: the game shows the change after
  the next build (in IntelliJ, *Build → Rebuild Project*; from the command
  line, recompile or refresh the copies under `bin`).

## Controls

| Key | Action |
|---|---|
| ← / → | Move piece left / right |
| ↑ or X | Rotate right (clockwise) |
| Z | Rotate left (counter-clockwise) |
| ↓ | Fast drop |
| P | Pause / resume |
| Restart button | Restart from screen 1 |

## Customising levels (`levels.csv` + `levels/level_N.txt`)

The definition of a level lives in two plain text files.

**`levels.csv`** — one line per screen. The screen id is its 1-based line
order, so there is no id column:

```csv
linesRequired,difficultyActive,numPieces
5,true,10
10,false,7
```

**`levels/level_<id>.txt`** — the pre-placed cells of that screen, one per
line as `row,col,color`. A missing file simply means the screen has no
obstacles (`level_1.txt` does not exist in this project).

```text
15,0,7
15,9,7
```

| Field | Type | Description |
|---|---|---|
| `linesRequired` | int | Lines to clear before advancing to the next screen (minimum 1) |
| `difficultyActive` | boolean | When `true`, random obstacle blocks are inserted after each piece lands (`true`/`false`, `1`/`0`, `yes`/`no`, `y`/`n`) |
| `numPieces` | int | How many piece types may appear: `7` for the classic set, up to `10` to include the exotic shapes |
| `row`, `col` | int | Cell coordinates, 0-based: rows `0..19` (0 is the top), columns `0..9` |
| `color` | int | Palette value of the cell, `1..10` |

At least one screen is required: add or remove lines in `levels.csv` and the
game wraps around after the last one. Blank lines, `#` comments (whole line or
after the data) and a leading header line are ignored, and fields may be
separated by commas, semicolons or spaces.

Loaded, a screen is one [`Level`](src/tetris/Level.java) —
`linesRequired`, `difficultyActive`, `numPieces` and `cells`, where `cells` is
just a list of `{row, col, color}`. Loading reads and converts the numbers;
validating applies the rules; filling a board is a loop of `board.place`.

Both files can also be edited with the mouse: `tetris.LevelEditor` paints
the obstacles of a screen and writes the files back (see *Running the level
editor*). It does not edit the three values of `levels.csv`; a new screen gets
the defaults and its values are changed by hand.

Data is validated at startup. If something is wrong the game refuses to start
and lists every problem. A line that cannot be read is reported with its file
and line; a level that breaks a rule, by its number:

```
Invalid level data (3 problems):
  levels/level_3.txt:7: expected row,col,color, found: 20,3
  level 3: duplicated obstacle at (6,6)
  level 4: numPieces must be between 1 and 10 (defined pieces), found: 12
```

The same check catches an empty `levels.csv`, colors outside the palette,
non-numeric or negative fields, a wrong number of fields, coordinates outside
the board, `numPieces` larger than the defined pieces and `linesRequired` below
1.

## Project structure

```
src/tetris/
├── Tetris.java       # Entry point: JavaFX Application, keyboard wiring, animation loop
├── LevelEditor.java  # Second entry point: paints the levels of src/ with the mouse
├── FrameTimer.java   # Turns the pulses of the timer into a fixed 60 ticks per second
├── Renderer.java     # Game screen composition and pixel-level painting
├── CellPainter.java  # Cells, rectangles and digits: the pixel layer both screens share
├── EditorRenderer.java # Editor canvas: the obstacles being painted, and point → cell
├── DigitFont.java    # 4×5 bitmap font and digit metrics for the score/lines markers
├── Engine.java       # Game engine: rules, physics, scoring
├── GameState.java    # Observable state: pieces, score, lines left, state, paused flag
├── Effects.java      # Decorations: flash, fill, fade, blink (each a private Sequence<T>)
├── Sequence.java     # A typed run of periods: duration, repetitions, target, step and end actions
├── Clock.java        # The ticks since the start and the counters created in it
├── Sound.java        # Sound effects as an interface
├── JavaFxSound.java  # The AudioClip implementation of Sound
├── Edges.java        # The four edge bits (1/2/4/8) shared by piece, board and renderer
├── Board.java        # Playfield: geometry (rows/cols) and cell state
├── Piece.java        # Shape definitions, rotation states, active piece
├── Levels.java       # The screens: load() reads them, validate() checks them, get() returns one
├── Level.java        # One level: linesRequired, difficultyActive, numPieces, cells
├── LevelFiles.java   # The editor's disk access: reads and writes levels.csv and one level file
├── Palette.java      # Color palette: base/bright/dark shades and a brightness factor
├── levels.csv        # One line per screen: lines, difficulty, piece types
├── levels/
│   ├── level_2.txt   # Obstacles of screen 2 as row,col,color (…)
│   └── level_9.txt   # Screen 1 has no file: no obstacles
└── sounds/
    ├── drop.wav
    ├── line.wav
    └── block.wav
```

## How it is built

Three documents explain the design:

| Document | What it explains |
|---|---|
| [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) | `Piece.java`: the shape templates, the four rotations, the 3D edge flags and the falling piece |
| [GAME-EXPLAINED.md](GAME-EXPLAINED.md) | `Engine.java` + `GameState.java`: the game engine — state machine, gravity, line clearing, screens, game over — plus a map of the classes |
| [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) | `Renderer.java`, `CellPainter.java`, `DigitFont.java` and `Palette.java`: from the game state to the pixels on the canvas |

Suggested order: pieces (what a block is), game (what the rules are),
renderer (how it is drawn).

```
Tetris (JavaFX shell): the only class that knows both sides
  │
  ├── keys ──► Engine (rules)
  │              ├── GameState (board, pieces, score, state; written by Engine, read by Renderer)
  │              │     └── Board (geometry + cells, written by Engine and Effects)
  │              ├── Piece     (templates + live piece)
  │              ├── Levels    (levels.csv + levels/*.txt)
  │              ├── Clock     (ticks, and the counters of each duration)
  │              ├── Effects   (four Sequence<T> animations; writes the Board hidden mask)
  │              └── Sound     (injected: JavaFxSound or silent)
  │
  ├── paint(gameState, fade) ──► Renderer (picture)
  │                                ├── CellPainter (cells, rectangles, pixels)
  │                                │     └── Palette (colours, shades, brightness)
  │                                └── DigitFont   (4×5 digits for the markers)
  │
  └── FrameTimer (pulse timestamps → 60 ticks per second)
```

## License

MIT — see [LICENSE](LICENSE).
