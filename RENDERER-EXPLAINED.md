# Renderer: from the board to the screen

### A short tour of [`Renderer.java`](src/tetris/Renderer.java) — with its nested `Palette` and `CellPainter` — and [`DigitFont.java`](src/tetris/DigitFont.java)

Every frame, the renderer turns a model (a board of numbers, two pieces, a
score) into pixels, without knowing a single rule of the game: it **reads** the
model — accessors, no `setCell`, no `score++` — and never tells it what to do.

The companion documents are [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) (what a
block is) and [GAME-EXPLAINED.md](GAME-EXPLAINED.md) (the rules).
Read those first: here we assume the model already exists.

`CellPainter` and `Palette` are nested classes of `Renderer`: the pixels and the
colours are the picture itself, so they live with the class that composes it.
The level editor builds its own `Renderer.CellPainter` and paints with it, which
is why both screens show the same blocks and the same bevels.

---

## 1. The pipeline

```
   GameState (board, pieces, score) + game-over brightness
                     │
                     │   paint(gameOverBrightness)
                     ▼
   screenImage  (WritableImage, canvas size, black once)
   ├── paintBoard()           → board cells written directly to screenImage
   ├── paintGap()             → separator column
   ├── paintActivePiece()     → active piece over the board
   ├── paintPanelBackground() → INFO_BG fill for the whole panel
   ├── paintPreview()         → next piece, cell by cell
   └── paintMarker() × 2      → 4-digit markers (score, lines left)

   every flat area (letterbox, panel fill, preview and marker backgrounds,
   digit blocks) goes through CellPainter.fillRect(), one row at a time
                     │
                     │   painter.present()  →  gc.drawImage()
                     ▼
   Canvas (what the player sees)
```

Three decisions do all the work:

1. **Sub-rectangles, not a shared grid.** Each area of the screen owns its
   rectangle in pixels and paints directly into the image. No intermediate
   `cellData`/`cellEdges` matrix.
2. **Write order is depth.** No layers, no z-index: later writes overwrite
   earlier ones, so the active piece is on top because it is painted last.
3. **Paint off-screen, then flip.** One `gc.drawImage`, so nobody ever sees a
   half-drawn frame.

---

## 2. Layout and scaling

```java
int totalCols = boardCols + 1 + PANEL_CELLS;   // board + gap + panel = 15
int totalRows = boardRows;                      // 20
pixelSize = Math.max(1, Math.min(w / totalCols, h / totalRows));

int gridW   = totalCols * pixelSize;
int gridH   = totalRows * pixelSize;
int originX = (w - gridW) / 2;   // centred; leftover is black letterbox
int originY = (h - gridH) / 2;
```

The screen is a fixed grid of **15 × 20 cells**:

```
        columns 0..9        column 10      columns 11..14
        ┌──────────────────┬──────────────┬──────────────────┐
 row 0  │                  │              │   preview box    │
        │                  │              │   4 × 4 cells    │
        │                  │              ├──────────────────┤
        │      board       │  separator   │  score marker    │
        │      10 × 20     │   (1 cell)   │  lines marker    │
        │                  │              │                  │
 row 19 │                  │              │                  │
        └──────────────────┴──────────────┴──────────────────┘
```

`pixelSize` takes the **smaller** of the two ratios so the grid fits in both
directions. With 480 × 640 the answer is 32 px per cell: a bigger window renders
the same layout bigger, with no other change.

The canvas image is created at the full canvas size and filled with black **once
in the constructor**. The letterbox bands are never touched again — they stay
black for free.

Every sub-rectangle is an exact multiple of `pixelSize`, so there are no
fractional cells and no gap-filling arithmetic.

---

## 3. One frame: real time turned into ticks

The game needs a steady beat: 60 engine steps per second. The operating system
promises nothing of the kind. `AnimationTimer.handle(now)` is called once per
frame, and `now` is a **real timestamp** — nanoseconds of the system clock, the
same one `System.nanoTime()` reads — with three properties that matter:

- the calls arrive at the refresh rate of the monitor: 60, 75, 144, 240 …
- their spacing is not constant: a garbage collection, a slow frame or another
  program delays one;
- and none of that can be known in advance.

**One tick per call is not an option.** The game would run at the speed of the
screen: on a 144 Hz monitor the piece would fall 2.4 times faster than on a
60 Hz one, and a single hiccup would be a lost move. The shell measures instead
of assuming — between two calls it looks at how much real time has passed and
turns it into whole ticks, keeping the fraction for the next call:

```java
// Clock.FrameTimer: elapsed nanoseconds in, whole ticks out
static final long TICK_NANOS = 1_000_000_000L / 60;   // 16 666 666 ns
static final int  MAX_TICKS_PER_PULSE = 4;

int ticksFor(long now) {
    if (last < 0) { last = now; return 0; }  // first call: nothing elapsed yet
    long elapsed = Math.max(0,
            Math.min(now - last, TICK_NANOS * MAX_TICKS_PER_PULSE));
    last = now;
    pending += elapsed;                      // plus what was left over
    int ticks = 0;
    while (pending >= TICK_NANOS) {
        pending -= TICK_NANOS;
        ticks++;
    }
    return ticks;
}
```

Two details complete the correction:

- **`pending` keeps the fraction.** A pulse is never an exact number of ticks:
  at 144 Hz each call brings about 0.42 of one, so most calls hand out none and
  every two or three calls complete a tick. Dropping that remainder would make
  the engine drift away from the wall clock.
- **The cap turns a stall into lost time, not into a jump.** After two seconds
  with the window dragged, the elapsed time is 120 ticks; without the cap they
  would all play in one call, and the piece would fall through the floor between
  two pictures. Paying the pause is the lesser evil.

With the count in hand, the pulse has two jobs: advance the engine, and paint the
picture every `TICKS_PER_PAINT` ticks.

```java
// Tetris.handle(now)
int ticks = frameTimer.ticksFor(now);  // 1. how many ticks fit in this pulse
for (int i = 0; i < ticks; i++) {
    engine.tick();                     // 2. advance the engine, tick by tick
}
ticksSincePaint += ticks;              // 3. the picture: every TICKS_PER_PAINT ticks
if (ticksSincePaint >= TICKS_PER_PAINT) {
    ticksSincePaint = 0;
    renderer.paint(                    // 4. paint, with the game-over brightness
            engine.fadeProgress());
}
```

- **Tick before paint**, so the player sees the state *after* the step.
- **The engine runs at 60 ticks per second, always.** The rate lives in
  `TICK_NANOS`, not in the monitor: the same loop is exact at 60 or at 240 Hz.
- **The picture is composed every four ticks** (≈ 15 frames per second):
  nothing in the picture changes faster than the blink of a completed line — a
  step every six ticks — so a faster rate would only repeat the same image.
- **Only the shell ever sees `now`.** [`Clock`](src/tetris/Clock.java) counts
  ticks, [`Engine`](src/tetris/Engine.java) counts them into durations, and the
  renderer never asks for the time.

---

## 4. Painting the board

`paintBoard()` iterates every board cell and writes it directly to `screenImage`:

```java
for (int r = 0; r < boardRows; r++) {
    for (int c = 0; c < boardCols; c++) {
        int v     = gameState.board().visibleCellValue(r, c);
        int idx   = (v != 0) ? Palette.indexOfValue(v)
                             : ((r + c & 1) != 0 ? Palette.BG_B : Palette.BG_A);
        int edges = (v != 0) ? gameState.board().edgeAt(r, c) : 0;
        paintCell(boardX + c * pixelSize, boardY + r * pixelSize, idx, edges);
    }
}
```

Background and content are merged in one pass: empty cells get the checkerboard
mosaic (`(r + c) & 1`), occupied cells get their colour and edge flags.
`visibleCellValue` answers `0` when the hidden mask covers the cell — a
difficulty cell blinking in, a completed line blinking off — so the mosaic
shows through. The mask is decoration: the cell keeps its value, `fitsAt` and
the collisions read `valueAt` and never see it.

The active piece is painted on top afterwards by `paintActivePiece()` at the
position the rules use (`posY`); there is no visual offset. A cell still above
row 0 is simply clipped by `Board.inBounds`, so the piece is drawn exactly while
it is inside the playfield.

---

## 5. Colour: value → index → variant

The model stores **values**, not colours: `0` empty and `1..10` for both piece
and obstacle colours. The conversion is one line, and it lives in `Palette`:

```java
static int indexOfValue(int v) { return BASE_COLORS + v - 1; }
```

`Palette` keeps 19 colours and, for each one, three shades — `BASE`, `BRIGHT`
(left/top edges) and `DARK` (bottom/right) — precomputed into a byte array:

```java
private final byte[][][] lut = new byte[COLOR_ENTRIES][3][4];   // [colour][variant][BGRA]
```

The table is rebuilt only when the brightness factor changes (pause, game over).

**Pause and game over have no painting code of their own**, only a brightness
factor:

```java
private double brightnessFactor(double gameOverBrightness) {
    if (gameState.isGameOver()) return gameOverBrightness;
    if (gameState.isPaused())   return PAUSE_BRIGHTNESS;
    return 1.0;
}
```

`GameState` says *whether* to darken (paused, over); the renderer says *how much*
for the pause, the fade curve comes from `Effects` — forwarded by `Engine` and
passed in by the shell with every picture — and `Palette` says *how*.

That factor reaches `Palette` before anything is painted. The next `pixel(...)`
rebuilds the table with it: `1.0` leaves every colour as declared and `0.0` turns
the pieces black. The `BRIGHT` and `DARK` shades are recalculated from the result,
so the 3D relief survives the darkening. `setBrightness` is that global factor,
not the `BRIGHT` variant of a single colour.

---

## 6. Cell painter

Each cell is painted into a **reusable** buffer of `pixelSize²` pixels, then
blitted with one `setPixels` call:

```java
for (int i = 0; i < pixelSize * pixelSize; i++) { /* fill with base colour */ }

if (Edges.hasLeft(edgeBits))   paintLine(0, 0, 0, pixelSize-1, bright);
if (Edges.hasTop(edgeBits))    paintLine(0, 0, pixelSize-1, 0, bright);
if (Edges.hasBottom(edgeBits)) paintLine(0, pixelSize-1, pixelSize-1, pixelSize-1, dark);
if (Edges.hasRight(edgeBits))  paintLine(pixelSize-1, 0, pixelSize-1, pixelSize-1, dark);
```

- **One buffer, one allocation.** No garbage in the drawing loop.
- **The same edge flags as `Piece`, through the same names.** The four bits are
  declared once in [`Edges`](src/tetris/Edges.java).
- **The order is load-bearing.** Left, top, bottom, right: the last one wins, so
  the top-right corner ends dark — lit from the top-left.
- The bevel is always one pixel wide, whatever the cell size — which is why the
  marker frame and the digits can reuse `paintCell` at any size.

---

## 7. The panel: background, preview and two markers

The right-hand column of the grid is a `PANEL_CELLS × boardRows` rectangle that
`Renderer` paints itself. `DigitFont` paints nothing: it only supplies the digits
and the metrics the markers need.

```
panel rectangle  (PANEL_CELLS × boardRows cells)
├── preview rect  (PREVIEW_CELLS × PREVIEW_CELLS, centred horizontally)
├── score rect    (same width as preview, centred in the 1st third below it)
└── lines rect    (same width as preview, centred in the 2nd third below it)
```

`paint()` calls them in order:

```java
paintPanelBackground();                       // INFO_BG fill for the whole panel
paintPreview(gameState.nextPiece());
paintMarker(gameState.score(),     scoreX, scoreY, Palette.BLACK,     Palette.DIGIT_RED);
paintMarker(gameState.linesLeft(), linesX, linesY, Palette.MARKER_BG, Palette.DIGIT_BLUE);
```

`paintPanelBackground()` fills the full panel rectangle with `INFO_BG` before the
sub-areas are painted on top of it.

`paintPreview()` paints the next piece cell by cell using the same `paintCell`
logic as the board, centred on its bounding box:

```java
int dx = Math.round((PREVIEW_CELLS - piece.previewCols()) / 2.0f);
int dy = Math.round((PREVIEW_CELLS - piece.previewRows()) / 2.0f);
```

The waiting piece spins while it waits, so its bounding box changes on almost
every frame and `dx`/`dy` keep it centred.

`paintMarker()` receives its rectangle in pixels plus the two palette indices
that pick the colours: black background with red digits for the score, grey with
blue digits for the lines left. Both markers are:

1. one background cell — `paintCell(x, y, backgroundColour, Edges.ALL, markerW,
   markerH)` fills the rectangle and draws the four frame lines in a single
   `setPixels`,
2. four digits drawn from a **4 × 5 bitmap font** (`DigitFont.DIGITS`,
   hand-placed pixels, identical look at any scale), painted as cells.

The frame can go first because the font metrics reserve it: `TOP_MARGIN` and
`BOTTOM_MARGIN` keep every digit at least one pixel away from it.

The digits are arithmetic, not formatting: `remaining % 10` takes the last digit,
`remaining /= 10` removes it, and drawing right to left gives the leading zeros
of an arcade display for free.

Inside a digit, `paintDigit` reuses `paintCell`: every "on" pixel of the 4 × 5
matrix becomes a `digitW × digitH` cell. `DigitFont.buildEdges` pre-calculates
the edge flags of every cell of every digit once, at class-initialisation time
(the same trick `Piece.Rotations` plays with the four rotation states, and the
same `Edges.calculateEdges` walk underneath), so the renderer only reads
`DigitFont.edgesOf(digit, row, column)`. The 3D bevel therefore follows the
outline of the character, drawn by the same method as the board.

The marker has **no buffer of its own**: its background/frame and its digits are
both `paintCell` calls, and `cellBuffer` is sized for the largest of the three
users (board cell, digit cell, whole marker). The flat areas that are not cells —
the panel fill and the preview background — go through `fillRect(x, y, w, h,
colour)`, which copies the colour once into the shared `fillBuffer` and writes the
rectangle a row at a time, clipped to the canvas.

`DigitFont` is built from the marker rectangle and exposes what the marker needs:
`MARKER_DIGITS`, the slot/column/row sizes (`digitSlotW`, `digitW`, `digitH`), the
size of the matrix (`DIGIT_ROWS`, `DIGIT_COLUMNS`) and `digitPixel(n, row,
column)` for one pixel of a digit. The ten matrices stay private: a getter that
handed out `DIGITS[n]` would let any caller redraw that digit for the whole game,
the same leak `Piece.Rotations` does not have.
