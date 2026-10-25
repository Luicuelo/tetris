# Tetris FX

A Tetris game built with JavaFX: an engine ticked 60 times per second — whatever the monitor and however expensive the drawing gets — and a screen composed at 30 frames per second through a `WritableImage` / `PixelWriter` rendering pipeline. It features a progression of 8 screens with hand-crafted obstacle layouts, a set of 10 piece shapes, 3 exotic beyond the classic 7, and level configuration driven by plain text files.

## Features

- 8 screens with unique obstacle layouts, defined in `levels.csv` + `levels/level_N.txt`
- 10 piece shapes: the classic 7 plus 3 exotic ones (2L, 6, 9) configurable per screen
- 3D-relief cell rendering with bright/dark edge masks
- Animated line-clear flash, level-transition fill effect and game-over darkening
- Sound effects (drop, line clear, difficulty block)
- Pause, fast-drop and Restart support
- Proportional scaling: the board keeps its 10x20 cells and the pixels per
  cell are derived from the canvas, so any `--width` / `--height` renders the
  same layout bigger (leftovers are letterboxed in black)

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
      src/principal/*.java
```

## Running

From the repository root (the classpath paths are relative to it):

Linux / macOS:

```bash
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.graphics,javafx.media \
     -cp "bin:src" \
     principal.Tetris
```

Windows (note the `;` separator, one line to avoid the caret):

```powershell
java --module-path "C:\path\to\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.graphics,javafx.media -cp "bin;src" principal.Tetris
```

- `src` is on the classpath because it holds the level data and the sounds.
- `javafx.media` is required; without it the game runs muted.

The window size only chooses the pixels per cell (default 480 × 640, i.e. 32 px
per cell for the fixed 15 × 20 grid):

```bash
java ... principal.Tetris --width=750 --height=1000   # 50 px per cell
```

### Running from IntelliJ IDEA

In *Run → Edit Configurations*, the module flags must go in **VM options**
(hidden by default: enable it with *Modify options → Add VM options*), not in
*Program arguments*:

```
--module-path "C:\path\to\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.graphics,javafx.media
```

with **Main class** = `principal.Tetris`.

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
| `difficultyActive` | boolean | When `true`, random obstacle blocks are inserted after each piece lands (`true`/`false`, `1`/`0`, `yes`/`no`, `si`/`no`) |
| `numPieces` | int | How many piece types may appear: `7` for the classic set, up to `10` to include the exotic shapes |
| `row`, `col` | int | Cell coordinates, 0-based: rows `0..19` (0 is the top), columns `0..9` |
| `color` | int | Palette value of the cell, `1..14` |

Any number of screens is supported: add or remove lines in `levels.csv` and the
game wraps around after the last one. Blank lines, `#` comments (whole line or
after the data) and a leading header line are ignored, and fields may be
separated by commas, semicolons or spaces.

Data is validated at startup. If something is wrong the game refuses to start
and lists every problem with its file and line:

```
Invalid level data (2 problems):
  levels/level_3.txt:7: obstacle at (20,3) is outside the board (rows 0..19, columns 0..9)
  levels/level_3.txt:9: duplicated obstacle at (6,6)
```

The same check catches colors outside the palette, non-numeric or negative
fields, a wrong number of fields, `numPieces` larger than the defined pieces
and `linesRequired` below 1.

## Project structure

```
src/principal/
├── Tetris.java       # Entry point: JavaFX Application, keyboard wiring, animation loop
├── FrameTimer.java   # Turns the pulses of the timer into a fixed 60 ticks per second
├── Renderer.java     # Screen composition and pixel-level painting
├── InfoPanel.java    # Preview box and score/lines markers
├── Game.java         # Game engine: state machine, physics, scoring
├── Effects.java      # Decorations: flash, fill, fade, blink
├── Clock.java        # Ticks since the start, and when each event began
├── Sound.java        # Sound effects as an interface
├── JavaFxSound.java  # The AudioClip implementation of Sound
├── Edges.java        # The edge bitmask (1/2/4/8) shared by piece, board and renderer
├── Board.java        # Playfield: geometry (rows/cols) and cell state
├── Piece.java        # Shape definitions, rotation states, active piece
├── Levels.java       # Loads levels.csv + levels/level_N.txt, validates them
├── Palette.java      # Color palette with dim/bright/dark variants
├── levels.csv        # One line per screen: lines, difficulty, piece types
├── levels/
│   ├── level_2.txt   # Obstacles of screen 2 as row,col,color (…)
│   └── level_8.txt   # Screen 1 has no file: no obstacles
└── sounds/
    ├── drop.wav
    ├── linea.wav
    └── bloque.wav
```

## How it is built

Three documents explain the design:

| Document | What it explains |
|---|---|
| [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) | `Piece.java`: the shape templates, the four rotations, the 3D edge flags and the falling piece |
| [GAME-EXPLAINED.md](GAME-EXPLAINED.md) | `Game.java`: the game engine — state machine, gravity, line clearing, screens, game over |
| [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) | `Renderer.java` and `InfoPanel.java`: from the game state to the pixels on the canvas |

Suggested order: pieces (what a block is), game (what the rules are),
renderer (how it is drawn).

```
Tetris  ──keys──►  Game  ──reads──►  Renderer  ──►  Canvas
(JavaFX shell)     (the game)       (the picture)
  │                  │
  │                  ├── Board   (geometry + cells)
  │                  ├── Piece   (templates + live piece)
  │                  ├── Levels  (levels.csv + levels/*.txt)
  │                  ├── Palette (colours and their shades)
  │                  ├── Effects (the four animations)
  │                  ├── Clock   (ticks of gravity and animations)
  │                  └── Sound   (injected: JavaFxSound or silent)
  │
  └── FrameTimer (pulse timestamps → 60 ticks per second)
```

## License

MIT — see [LICENSE](LICENSE).
