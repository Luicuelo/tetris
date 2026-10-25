# Game: the game engine

### A short tour of [`Game.java`](src/principal/Game.java)

`Game` is the game itself. It owns the board, the two pieces and the score, and
it knows nothing about pixels: it is advanced one tick at a time and somebody
else decides how that looks. It knows nothing about JavaFX either: the sound
arrives as a [`Sound`](src/principal/Sound.java) and everything that is only
decoration lives in [`Effects`](src/principal/Effects.java).

```java
void tick() {
    if (paused) return;
    switch (gameState) {
        case STATE_PLAY:  countDown();        break;   // falling piece
        case STATE_LINE:  tickLines();        break;   // a line has just gone
        case STATE_TRANS: tickTransition();   break;   // a screen has just gone
        case STATE_END:   effects.tickFade(); break;   // darkening
    }
}
```

Everything else in the file is a consequence of that method: **four modes, one
switch, and no way to be in two of them at once.**

Reading order for the companion documents: [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md)
(the falling piece) and [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) (how the
state becomes pixels).

---

## 1. What the engine holds

```
   Tetris  ── keys ──►  Game  ── tick() ──►  new state
                          │
                          │ accessors (read only)
                          ▼
                       Renderer
```

| Field | What it is |
|---|---|
| `board` | The playfield (`Board`): cells, edge flags, row fill, topmost row |
| `levels` | The screens loaded from `levels.csv` + `levels/*.txt` |
| `currentPiece`, `nextPiece` | The falling piece and the preview one (either may be `null`) |
| `score`, `linesLeft`, `piecesInPlay` | Markers and how many piece types may appear |
| `gameState`, `paused` | Which of the four modes is running |
| `pendingLines` | Completed rows waiting for their flash animation |
| `clock` | The ticks of every event (`Clock`): gravity, flash, fill, fade and blink |
| `fastDropping` | Soft drop: gravity accelerates until the piece lands |
| `difficultyActive`, `difficultyCount`, `difficultyBlocks` | The random obstacles |
| `sound`, `effects`, `rng` | Two collaborators injected or built here, and one random source |

The board owns the geometry, `Piece` owns the shapes, `Levels` owns the data.
`Game` only orchestrates: **it decides, then asks the owner to change.**

Two `state`s, two meanings: `Active.state` is the piece's rotation (0..3),
`gameState` is the mode of the whole game.

The two collaborators are deliberately split the same way:

| Class | Holds | Why outside the engine |
|---|---|---|
| `Sound` | the three effects as an interface | the rules say "play the drop"; *how* it sounds is the application's business |
| `JavaFxSound` | the `AudioClip`s | the only file of the engine folder that mentions audio |
| `Effects` | line flash, screen fill, game-over fade | they are decoration: they paint and wait for ticks, they never decide a rule |

---

## 2. Inputs are intentions, not commands

The keyboard lives in `Tetris.java` and calls methods that say what the player
*wants*, never what to do internally:

| Key | Call | Effect |
|---|---|---|
| ← / → | `moveLateral(-1 / +1)` | Move one column, if it fits |
| ↑ or X | `moveRotate(+1)` | Turn clockwise (to the right), if it fits |
| Z | `moveRotate(-1)` | Turn counter-clockwise (to the left), if it fits |
| ↓ | `fastDrop()` | Set a flag; gravity accelerates until the piece lands |
| P | `togglePause()` | Flip one boolean |
| Restart | `restart()` | Back to screen 1 with a new board |

Movement is the *try / validate / undo* pattern described in
[PIECES-EXPLAINED.md](PIECES-EXPLAINED.md#5-moving-try-validate-undo): change
the position, ask `fitsAt(board)`, restore if it does not fit. A paused game
ignores input instead of queueing it.

---

## 3. Time is a clock of ticks

The engine is ticked exactly 60 times per second — the shell hands out the ticks
from the timestamps of the pulses, whatever the monitor — so every duration is a
number of ticks, and all of them live in one place: `Clock`.

```java
// Clock
private static final long TICKS_DROP = 18;   // gravity (~0.3 s)
private static final long TICKS_LINE = 30;   // line flash (~0.5 s)
private static final long TICKS_FILL = 24;   // one row of the screen fill
private static final long TICKS_FADE = 21;   // one step of the game-over fade
private static final long TICKS_BLINK = 8;   // one change of a block blinking in

final Counter drop = new Counter(TICKS_DROP);
```

`Game.tick()` increments one number — `clock.tick()` is `ticks++` and nothing
else — and every "has it been long enough?" in the project is the same call:

```java
if (!clock.drop.trigger()) return;   // not yet
```

A counter is a **period**, not a stopwatch: it stores the tick at which it was
opened, and the elapsed time is a subtraction. It is **idle** (no period open)
until someone calls `start()`, which records the current tick; from there
`trigger()` answers `true` once `ticks - startedAt` reaches the limit, and the
period is closed. So the clock knows nothing about the counters — it does not
walk them every tick — a counter that nobody started never fires, and a period
cannot fire twice. Whoever opens a period starts it: `spawnPiece` (gravity),
`flashLine`, `startFill`, `startFade`, `appearBlock` (the blink of a new block).

The tick count is a `long` and not an `int`: a counter keeps the tick it started
at and subtracts, so the number has to survive a long session without wrapping.

The other half of the contract is that the caller asks every tick: gravity on
every tick of `STATE_PLAY`, the flash on every tick of `STATE_LINE`, and so on.
Nobody keeps a count of its own.

No `Thread.sleep`, no timers, no callbacks, and nothing in the engine reads a
clock: it counts ticks, and the shell turns the timestamps of the animation pulses
into ticks at a fixed rate of 60 per second ([`FrameTimer`](src/principal/FrameTimer.java)).
So a paused game freezes it, a slow machine drops frames instead of falling
slower, and `TICKS_DROP = 18` is 0.3 s of wall time on any monitor. Gravity and
the four animations share the clock but not their counters, so a slow animation
cannot delay the fall of a piece.

---

## 4. Gravity, and the moment a piece becomes board

```java
private void countDown() {
    if (!clock.drop.trigger()) return;      // 18 ticks have not passed yet
    if (fastDropping) {
        while (currentPiece != null && dropPiece() == 1) { }  // to the floor
        fastDropping = false;
    } else if (currentPiece != null) {
        dropPiece();
    }
    clock.drop.start();                     // the next period begins here
}
```

Fast-drop is one flag plus one loop around the **same** `dropPiece()`, so it
inherits collisions, line clearing and game over for free.

`dropPiece()` has three parts:

1. **Down.** `posY++`, then `fitsAt`. The check is skipped when
   `posY + 4 < board.topRow()`: the piece is still far above the highest
   occupied row, so nothing can be in the way.
2. **Spin the preview.** Every successful drop calls `nextPiece.nextState()`,
   which is why the waiting piece rotates while it waits.
3. **Land.** `posY--` undoes the failed step and `copyPiece` writes the colour
   **and the edge flags** into the board.

The return value is a compact report to the caller:

| Return | Meaning |
|---|---|
| `1` | Dropped one row |
| `0` | Locked, no lines completed |
| `-1` | Locked, lines queued for the flash |
| `-2` | Locked, but there is no room left: game over |

Locking in order: `posY--` → `copyPiece` → `fastDropping = false` → game-over
check (`board.topRow() <= 1`) → sound → check the four rows the piece occupies.
No lines: `score++` and spawn the next piece. Lines: add
`POINTS_PER_LINES[numLines - 1]` (`{5, 15, 30, 60}`) and switch to `STATE_LINE`.
`spawnPiece` starts `clock.drop`, so a new piece gets a full period before it
falls, also when it arrives after a flash or a screen change.

---

## 5. Lines: flash, remove, and fix the indexes

Completed rows are **not** removed immediately. They go into `pendingLines`,
`effects.flashLine(row)` decorates the row and its neighbours with bright edges,
and the state machine flashes them one at a time:

```java
if (!effects.flashFinished()) return;   // still flashing
int row = pendingLines.remove(0);
clearLine(row);
```

`clearLine` has to do three things, and the third is the subtle one:

```java
adjustDifficultyForClearedLine(row);   // 1. give the budget back
board.removeRow(row);                  // 2. shift everything above down
for (int i = 0; i < pendingLines.size(); i++) {
    int r = pendingLines.get(i);
    if (r < row) pendingLines.set(i, r + 1);   // 3. the queue is now stale
}
```

Removing a row moves every row above it down by one, so any index still in the
queue that was above the cleared row now points one row too high. Forget that
loop and a two-line clear removes the wrong rows: the classic "the data changed
under my feet".

When the queue empties: if `linesLeft == 0` the screen is done and the game
enters `STATE_TRANS`; otherwise back to `STATE_PLAY` with a new piece.

---

## 6. Screens: data becomes gameplay

A screen is a line of `levels.csv` plus an optional obstacle file. `advanceScreen()`
is the only place where that data enters the game:

```java
if (++currentScreen > levels.count()) currentScreen = 1;   // wrap around
loadScreen(currentScreen - 1);          // clear the board, place the obstacles
difficultyActive = levels.difficultyActive(...);
piecesInPlay     = levels.numPieces(...);   // 7 classic, up to 10
replaceForbiddenPieces();               // the preview may belong to the old set
return levels.linesRequired(...);
```

The screen-clear sequence is the pink fill of `STATE_TRANS`: each step fills one
row from the top down with the transition colour, awards 5 points and plays a
sound, until it reaches the highest occupied row. The painting is
`effects.paintRow()`; the score and the screen change stay in
`tickTransition()`. Then the next screen is loaded and play resumes.

`numPieces` is what makes the exotic pieces appear: it is nothing more than the
bound of the draw (`rng.nextInt(piecesInPlay)`), so on a classic screen
`randomType()` never picks the last three. The enum is the only list of pieces;
the screen just decides how far into it the draw may go.

The set can also **shrink** between screens (`10` on the first one, `7` after),
while the preview was drawn with the set of the screen just finished.
`replaceForbiddenPieces()` runs as soon as the new limit is loaded: a preview
that is no longer allowed is discarded and another one is drawn from the new
set, so a forbidden type can never appear. The falling piece needs no check — a
screen only ends after its last piece has been locked into the board — and a new
game loads screen 1 *before* drawing anything, so its two pieces are born with
the rules of the screen they are played on.

---

## 7. The board never grows, so difficulty does

On screens with `difficultyActive` true, every locked piece may insert one
extra block near the top:

```java
if (!difficultyActive || difficultyCount <= 0) return;      // budget exhausted
if (rng.nextInt(PROBABLE) != 0) return;                     // 1 in 4
```

Three guards keep it fair: the row cannot be nearly full, the block stays on the
topmost row, and the budget is `MAX_DIFFICULTY = 5` per screen. The blocks are
remembered in `difficultyBlocks` and the budget is **given back** when one of
them is cleared — the array is a little stack, with the free budget at the
front and the placed blocks at the end.

This is the most intricate corner of the engine, and it exists because the
obstacles are the only state that has to be tracked backwards in time.

The new block announces itself: `effects.appearBlock(row, col)` makes it blink
for three changes of `TICKS_BLINK` ticks, starting hidden and ending visible. The
board never hides it — only the picture does, because `Game.cellValue` answers 0
while the blink is in its dark phase, and the collisions read `board.valueAt`
directly and know nothing about the blink.

---

## 8. Pause and game over are one number

Game over is decided by the **board**, not by the piece: if the highest occupied
row has reached the top two rows there is no room to spawn.

```java
if (board.topRow() <= 1) { currentPiece = null; gameState = STATE_END; }
```

The reference becomes `null` and every reader already tolerates it, so "the game
is over" is expressed in one field instead of a flag checked everywhere.

The end animation does not repaint anything either: `effects.startFade()` starts
it, every tick `effects.tickFade()` advances the step, and the palette darkens
everything.

```java
double dimFactor() {
    if (gameState == STATE_END) return Math.pow(FADE_DIM, effects.fadeStep());  // 1.0, 0.55, 0.30…
    if (paused)                 return PAUSE_DIM;
    return 1.0;
}
```

The engine says **how much** to darken; `Palette` decides **how**. Pause and
game over share the same mechanism, and the renderer needs no special case.

---

## 9. The contract with the renderer

The renderer reads, the engine decides:

| Renderer calls | Answers | Real owner |
|---|---|---|
| `boardRows()`, `boardCols()` | the size | `Board` |
| `cellValue(r, c)`, `edgeValue(r, c)` | one cell of the playfield | `Board` |
| `currentPiece()`, `nextPiece()` | the two live pieces, or `null` | `Piece.Active` |
| `points()`, `linesLeft()` | the markers | `Game` |
| `dimFactor()` | the darkening factor | `Game` |
| `tick()` | advance one step | `Game` |

Plus `finished()` and `isPaused()`, which the shell uses to stop the animation
timer or react to the Restart button.

`cellValue` is the only accessor that does not answer with the plain board:
while a difficulty block is blinking it answers 0, so the picture can hide it
without the board noticing.

The values that cross that boundary are plain integers, and one of them is a
shared contract: the edge mask of a cell. The four bits (`LEFT` 1, `TOP` 2,
`BOTTOM` 4, `RIGHT` 8) are declared **once** in [`Edges`](src/principal/Edges.java)
and used by the three places that touch them — `Piece.buildEdges` (which bits a
piece cell has), `Board` (which bits an obstacle gets) and
`Renderer.paintCell` (which bevel to draw). One `Edges.mask(row, col, area)`
computes them from "is this neighbour filled?".

---

## 10. Where to look

| What you want to see | Look for |
|---|---|
| The four modes | `STATE_PLAY`, `STATE_LINE`, `STATE_TRANS`, `STATE_END`, `tick` |
| Input entry points | `moveLateral`, `moveRotate`, `fastDrop`, `togglePause`, `restart` |
| Gravity and dropping | `countDown`, `dropPiece` |
| Locking a piece | `copyPiece`, `outOfBounds`, `forEachCell` |
| Spawning | `createPiece`, `randomType`, `spawnPiece` |
| Line animation | `pendingLines`, `tickLines`, `clearLine` |
| Scoring | `POINTS_PER_LINES`, `POINTS_PER_FILL_ROW` |
| Screens and levels | `tickTransition`, `advanceScreen`, `loadScreen` |
| Pieces allowed per screen | `piecesInPlay`, `replaceForbiddenPieces` |
| The decorations | `Effects`: `flashLine`, `paintRow`, `tickFade`, `appearBlock` |
| How long anything lasts | `Clock`, `Counter.start`, `trigger` |
| Random obstacles | `spawnDifficulty`, `appearBlock`, `adjustDifficultyForClearedLine` |
| Game over | `topRow() <= 1`, `effects.startFade`, `dimFactor` |
| Sound | `Sound`, `JavaFxSound`, `drop.wav`, `linea.wav`, `bloque.wav` |
| Edge mask | `Edges.LEFT`, `Edges.mask`, `Edges.has` |
