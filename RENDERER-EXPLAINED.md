# Renderer: from the board to the screen

### A short tour of [`Renderer.java`](src/principal/Renderer.java) and [`InfoPanel.java`](src/principal/InfoPanel.java)

Every frame, the renderer turns a model (a board of numbers, two pieces, a
score) into pixels, without knowing a single rule of the game: it **reads** the
engine — accessors, no `setCell`, no `score++` — and never tells it what to do.

The class rests on one idea: **flatten everything into one grid of cells, then
paint that grid**, so board, falling piece, preview and background all end up as
a colour index and a border mask in a cell.

The companion documents are [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) (what a
block is) and [GAME-EXPLAINED.md](GAME-EXPLAINED.md) (the rules).
Read those first: here we assume the model already exists.

---

## 1. The pipeline

```
   Board (values + edge bits)   Piece.Active ×2   score   lines left
                     │
                     │   composeScreen()          ← reads the model, writes a grid
                     ▼
   cellData[r][c]  +  cellEdges[r][c]             ← the whole screen as 15 × 20 cells
                     │
                     │   bufferToScreen() → paintCell()
                     ▼
   screenImage  (WritableImage, one pixel per canvas pixel)
                     │
                     │   panel.draw()   ← the numbers, in their own buffer
                     │   present()
                     ▼
   Canvas (what the player sees)
```

Four decisions do all the work:

1. **Flatten first, paint later.** Painting never asks where the piece is: it
   reads one grid.
2. **Write order is depth.** No layers, no z-index: later writes overwrite
   earlier ones, so the falling piece is on top because it is painted last.
3. **Cells are the unit, pixels are derived.** The grid is always 15 × 20; the
   window only decides the pixels per cell.
4. **Paint off-screen, then flip.** One `drawImage`, so nobody ever sees a
   half-drawn frame.

---

## 2. Who does what

| Question | Answered by |
|---|---|
| Where is the piece? Is a line complete? Is the game paused? | `Game` |
| Does this piece fit? What shape is it at this rotation? | `Piece` |
| How many rows and columns does the playfield have? | `Board` |
| What colour is value 7? What is a 20 % darker version? | `Palette` |
| Which pixel is cell (4, 7)? | `Renderer` |
| Where does the score marker go? | `InfoPanel` |

The keyboard is not in that table: `Tetris` hands the keys straight to `Game`.
The engine reaches the renderer only to be *read*, so a method that forwarded a
key would be a middleman that reads nothing.

---

## 3. Cells and pixels

```java
int infoX0    = boardCols + 1;              // 11
cellWidth     = infoX0 + PANEL_CELLS;       // 15
cellHeight    = boardRows;                  // 20
pixelSize     = Math.max(1, Math.min(width / cellWidth, height / cellHeight));
offsetX       = Math.max(0, (width  - cellWidth  * pixelSize) / 2);
offsetY       = Math.max(0, (height - cellHeight * pixelSize) / 2);
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
directions, and `Math.max(1, …)` guarantees at least one pixel per cell. With
480 × 640 the answer is 32 px per cell: a bigger window renders the same layout
bigger, with no other change.

Rounding is done in **integers** (1-pixel borders stay exactly 1 pixel), and
whatever the canvas has left over becomes black margin: the grid is centred,
not stretched.

Because the panel starts at column 11, **board coordinates are screen
coordinates**: board cell (7, 3) is screen cell (7, 3). Nothing is translated in
the board area.

---

## 4. One frame

The application shell (`Tetris.java`) creates the engine and a `Canvas` and
leaves the loop to an `AnimationTimer`. The pulse rate is **not** the speed of the
game: the timer pulses at the refresh rate of the monitor (60, 120, 144 …) and
stretches when a frame is expensive, so the shell first turns the elapsed time
into whole ticks of the engine:

```java
// Tetris.handle(now)
int ticks = frameTimer.ticksFor(now);  // 1. how many ticks fit in this pulse
for (int i = 0; i < ticks; i++) {
    game.tick();                       // 2. advance the engine, tick by tick
}
ticksSincePaint += ticks;              // 3. the picture: one tick of every two
if (ticksSincePaint >= TICKS_PER_PAINT) {
    ticksSincePaint = 0;
    renderer.paint();                  // 4. recompose and copy to the canvas
}
```

```java
// Renderer.paint()
updateImageFromPalette();              // cells become pixels
present();                             // the image is dumped on the canvas
```

- **Tick before paint**, so the player sees the state *after* the step.
- **The engine runs at 60 ticks per second, always.**
  [`FrameTimer`](src/principal/FrameTimer.java) keeps the leftover nanoseconds
  between pulses and hands out whole ticks: 60 Hz pulses give one tick each,
  144 Hz pulses give one every two or three pulses, and a pulse that lasted 50 ms
  gives three. The game runs at the same speed on any monitor and no longer
  slows down when drawing gets expensive — nor speeds up when it gets cheap,
  which is exactly what used to happen: with the engine ticked once per pulse,
  making the paint cheaper made the whole game run faster.
- **The picture is composed every two ticks** (≈ 30 frames per second). Painting
  is the expensive half: 300 cells, one `setPixels` per cell and the four margins
  of the canvas. The price is up to one skipped pulse (≈ 33 ms) of visual delay
  on a key press, and nothing else: the state is already the right one.
- **The picture is counted in ticks, not in pulses.** A pulse is worth 0, 1 or 4
  ticks depending on the machine, so a parity that flipped per pulse would paint
  at whatever rate the monitor wanted.
- **Each piece owns its rate, and only the shell holds `now`.** The engine keeps
  its rules ([`Game`](src/principal/Game.java)), the renderer only reads the game
  and paints ([`Renderer`](src/principal/Renderer.java)), and the shell decides
  *when*, because it is the only one holding the timestamp of the pulse. Split it
  the other way and the renderer would have to tick the game to keep it alive, or
  the engine would stop running without a window.
- The `AnimationTimer` wraps the call in `try`/`catch`: an exception there would
  otherwise repeat sixty times per second and flood the console.

The speed of the game is a real duration: `TICKS_DROP = 18` in
[`Clock`](src/principal/Clock.java) means 18 ticks at 60 per second, 0.3 s of wall
time whatever the machine draws. The constant is the only knob: 18 ticks = 0.3 s
per row, 24 = 0.4 s, and no machine can change it any more.

---

## 5. Compose: the model becomes a grid

Two arrays the size of the screen, indexed `[row][column]` like everything else:

```java
private final int[][] cellData;    // palette index per screen cell
private final int[][] cellEdges;   // edge flags per screen cell
```

`composeScreen()` fills them in a fixed order, **and the order is the point**:

**Step 1 — background.** A reset of the whole grid:

```java
if (c < boardCols)      idx = (((r + c) & 1) != 0) ? Palette.BG_B : Palette.BG_A;
else if (c == boardCols) idx = Palette.GAP;        // the separator column
else                     idx = Palette.INFO_BG;    // the panel
cellData[r][c] = idx;
cellEdges[r][c] = 0;
```

The board background is a **checkerboard produced by arithmetic**, not by an
image: `(r + c) & 1` picks one of two very dark blues. And because every cell is
written here, no cell remembers anything from the previous frame.

**Step 2 — the board.** Locked pieces and obstacles:

```java
int v = game.cellValue(r, c);
if (v != 0) {                        // empty cells leave the background alone
    cellData[r][c]  = colorIndex(v);
    cellEdges[r][c] = game.edgeValue(r, c);
}
```

The value comes from `game.cellValue`, not from the board: a difficulty block
that is blinking answers `0` during its dark phases, so the mosaic shows through
and the block seems to flicker in. The board still holds it — collisions and
`fitsAt` never see the blink — and the renderer knows nothing about it: it asks
for a value and paints whatever it receives.

**Step 3 — the falling piece.** The same 4×4 sweep as `fitsAt`, but writing
instead of checking, and shifted by one row:

```java
p.forEachCell((row, col, value, edgeBits) -> {
    int r = row + p.getPosY() + DRAW_OFFSET_Y;   // DRAW_OFFSET_Y = -1
    int c = col + p.getPosX();
    if (r >= 0 && r < cellHeight && c >= 0 && c < cellWidth) {
        cellData[r][c]  = colorIndex(value);
        cellEdges[r][c] = edgeBits;
    }
    return true;
});
```

`DRAW_OFFSET_Y = -1` is a quirk of the drawing: the piece is painted one row
*above* its collision position, so it "jumps" one row down as it locks. The
rules use `posY`; only the picture is shifted. There is no collision check here
because `fitsAt` already guarantees the piece does not overlap anything.

**Step 4 — the preview.** The same code again, moved into the panel and
centred using the piece's real bounding box:

```java
int dx = Math.round((boxCells - q.previewCols()) / 2.0f);
int dy = Math.round((boxCells - q.previewRows()) / 2.0f);
```

The waiting piece spins while it waits (another quirk of the drawing), so its
bounding box changes on almost every frame and `dx`/`dy` keep it centred.

**Why this order matters:** step 1 resets, so steps 2–4 can write only what
exists and ignore the rest. If you delete the `if (v != 0)` in step 2, empty
board cells become black and the mosaic disappears.

### One number, two meanings

| Where | `0` means |
|---|---|
| `Board` cell value | no cell here (empty) |
| `cellData[r][c]` | palette index 0 = **black** |
| `cellEdges[r][c]` | no border on any side |

The rule that keeps them apart: *empty board cells are skipped* (the background
survives), while *the preview background is written as 0* (the box is black).

---

## 6. Colour: value → index → variant

The model stores **values**, not colours: `0` empty, `1..10` piece colours,
`11..14` allowed for obstacles. The conversion is one line, and it lives in
`Palette`:

```java
static int indexOfValue(int v) { return BASE_COLORS + v - 1; }   // 5 + v - 1
```

`Palette` keeps 19 colours and, for each one, three shades — `BASE`, `BRIGHT`
(left/top edges) and `DARK` (bottom/right) — precomputed into a byte array laid
out for the pixel writer:

```java
private final byte[][][] lut = new byte[COLOR_ENTRIES][3][4];   // [colour][variant][BGRA]
```

So painting a cell is three array reads instead of three colour computations.
The table is rebuilt only when something changes (the dim factor), the same
"compute once, read many times" idea as the precomputed rotations in `Piece`.

**Pause and game over have no painting code of their own**, only a factor:

```java
palette.setDim(game.dimFactor());   // 1.0, or 0.55, or less and less
```

The engine decides *how much*, the palette decides *how*; backgrounds and
digits are not affected, so the score stays readable while paused.

---

## 7. Paint: cells become pixels

```java
for (r …) {
    int screenY = offsetY + r * pixelSize;
    for (c …) paintCell(offsetX + c * pixelSize, screenY, cellData[r][c], cellEdges[r][c]);
}
```

Each cell is painted into a **reusable** buffer of `pixelSize²` pixels, then
blitted with one `setPixels` call:

```java
for (int i = 0; i < pixelSize * pixelSize; i++) { /* fill with base colour */ }

if (Edges.has(edgeBits, Edges.LEFT))   paintLine(0, 0, 0, pixelSize - 1, bright);
if (Edges.has(edgeBits, Edges.TOP))    paintLine(0, 0, pixelSize - 1, 0, bright);
if (Edges.has(edgeBits, Edges.BOTTOM)) paintLine(0, pixelSize - 1, pixelSize - 1, pixelSize - 1, dark);
if (Edges.has(edgeBits, Edges.RIGHT))  paintLine(pixelSize - 1, 0, pixelSize - 1, pixelSize - 1, dark);
```

- **One buffer, one allocation.** No garbage in the drawing loop.
- **The same mask as `Piece`, through the same names.** The four bits are
  declared once in [`Edges`](src/principal/Edges.java); the painter asks
  `Edges.has(...)` and never writes `1`, `2`, `4` or `8` itself. `paintCell` is
  generic: it paints a locked piece, an obstacle, a flashed line or a fill cell
  identically.
- **The order is load-bearing.** Left, top, bottom, right: the last one wins, so
  the top-right corner ends dark. That is what makes blocks look lit from the
  top-left.
- The bevel is always one pixel wide, whatever the cell size.

Then the frame is flipped to the canvas:

```java
gc.setFill(Color.BLACK);
if (offsetX > 0) gc.fillRect(0, offsetY, offsetX, gridHeight);                  // left
if (right   > 0) gc.fillRect(offsetX + gridWidth, offsetY, right, gridHeight);  // right
if (offsetY > 0) gc.fillRect(0, 0, width, offsetY);                            // top
if (bottom  > 0) gc.fillRect(0, offsetY + gridHeight, width, bottom);          // bottom
gc.drawImage(screenImage, 0, 0);    // the whole frame in one call
```

Only the four bands the grid does not cover are painted black. With 480 × 640 the
grid covers the canvas exactly (15 × 32 = 480, 20 × 32 = 640), the four calls
disappear and a quarter of the pixels of the frame — the ones the image was about
to cover anyway — are no longer written.

---

## 8. The panel: a second, smaller painting model

The markers are not on the 15 × 20 grid: they are rectangles painted directly
into the image from their own buffers, so they live in `InfoPanel` — one class
per painting model.

The panel receives its geometry from the renderer as a small `Layout` record
(grid size, pixel size, offsets), so it never guesses the scale. A marker is:

1. a black (or grey) rectangle,
2. four digits drawn from a **4 × 5 bitmap font** (`DIGITS`, 96 hand-placed
   pixels, so the look is identical at any scale and needs no system font),
3. a raised border on top of them, bright top/left, dark bottom/right,
4. one `setPixels` call.

The digits are arithmetic, not formatting: `mm % 10` takes the last digit,
`mm /= 10` removes it, and drawing right to left gives the leading zeros of an
arcade display for free. Four digits is the hard limit of the marker.

Every write in the panel's mini rasteriser (`fillRect`, `hLine`, `vLine`) is
clamped to its buffer, so a glyph that is one pixel too tall is simply cut
instead of crashing.

---

## 9. Where to look

| What you want to see | File | Look for |
|---|---|---|
| The frame loop | `Tetris.java` | `AnimationTimer`, `initialize` |
| The engine rate | `FrameTimer.java` | `TICK_NANOS`, `MAX_TICKS_PER_PULSE`, `ticksFor` |
| The keyboard | `Tetris.java` | `setupKeyListeners` |
| Composition root | `Tetris.java` | `new Game(new JavaFxSound())`, `new Renderer(...)` |
| Per-frame pipeline | `Renderer.java` | `paint`, `updateImageFromPalette`, `present` |
| Layout and scaling | `Renderer.java` | constructor, `pixelSize`, `offsetX/Y` |
| The four composition steps | `Renderer.java` | `composeScreen` |
| Cell → pixels | `Renderer.java` | `paintCell`, `paintLine`, `bufferToScreen` |
| Draw offset quirk | `Renderer.java` | `DRAW_OFFSET_Y` |
| The blinking difficulty block | `Game.java`, `Effects.java` | `cellValue`, `appearBlock`, `tickBlink` |
| Markers and panel geometry | `InfoPanel.java` | `draw`, `drawMarker`, `Layout` |
| The digit font | `InfoPanel.java` | `DIGITS`, `drawDigit` |
| Value ↔ colour index | `Palette.java` | `indexOfValue`, `valueOfIndex` |
| Colour shades and dimming | `Palette.java` | `rebuild`, `pixel`, `setDim` |
| The dim curve | `Game.java` | `dimFactor` |
| The edge bits | `Edges.java` | `LEFT`, `TOP`, `BOTTOM`, `RIGHT`, `has` |
| Sounds | `Sound.java`, `JavaFxSound.java` | `drop`, `line`, `block` |
