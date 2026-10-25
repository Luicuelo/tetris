# Game engine: the rules

### A short tour of [`Engine.java`](src/tetris/Engine.java)

`Engine` answers the questions that make this a game: can the piece move, when
does it fall, what happens when it lands, and when does the player advance to
the next screen? It applies those rules but does not draw the result.

A normal turn follows this path:

1. A piece appears and the player can move or rotate it while it falls.
2. When it can no longer descend, its cells become part of the board.
3. If it completes any rows, those rows flash and are removed; otherwise the
   next piece appears immediately.
4. Clearing enough rows starts the next screen. Reaching the top ends the game.

The engine only moves when it is ticked, and each tick does the work of the
state the game is in. This document explains the parts it is made of, the data
it starts from, the states it can be in, what a tick does in each of them and
where the picture gets what it draws.

Reading order for the companion documents: [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md)
(the falling piece) and [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) (how the
state becomes pixels).

---

## 1. The parts

The game is a state machine with a memory. Every tick asks one question — what
is the game doing now? — and runs the piece of work that answers it. Only two
things move the machine: **ticks**, time counted in fixed steps, and **keys**,
what the player asks for.

| Part | What it does | Who uses it |
|---|---|---|
| `Engine` | Applies the rules: moves, locks, scores, changes state | `Tetris` ticks it and forwards the keys |
| `GameState` | Remembers the game: score, lines left, both pieces, current state, paused flag, completed-row queue, fill progress and difficulty counter | written by `Engine`, read by `Renderer` |
| `Board` | The playfield: geometry, cell values, edge flags and the hidden mask | `GameState`, `Engine`, `Effects`, `Renderer` |
| `Clock` | Counts ticks and hands out one counter per period | `Engine` for gravity, `Effects` for the animations |
| `Levels` | The screens read from `levels.csv` and `levels/*.txt` | `Engine` when it changes screen |
| `Effects` | The decorations: flash, fill, fade and blink | started and ticked by `Engine` |
| `Sound` | Sound effects behind an interface | `Engine` |
| `Random` | Chooses pieces and random obstacles | `Engine` |

**The engine only orchestrates: it decides, then asks the owner to change.**
The board owns the geometry, `Piece` owns the shapes, `Levels` owns the data.
The queue of completed rows and the count of painted rows are fields of
`GameState`, like the score: facts remembered between ticks, not authority over
the game. The same separation keeps the rules away from the presentation: the
engine decides that a completed row must disappear and asks `Effects` to make it
flash first. How the flash works does not change a single rule, so it is
explained at the end.

---

## 2. The data the game starts from

Before the first tick, three things are fixed: the playfield, the screens and
which pieces may appear. They are the static part of the game; the engine reads
them and never edits them.

**The playfield** is a fixed 10 × 20 grid. [`Board`](src/tetris/Board.java)
owns its geometry — `rows()`, `cols()` and `topRow()`, the highest row that is
not empty — so no other class repeats the size or the height of the stack.

**A screen** is one line of `/tetris/levels.csv`, with the fields
`linesRequired,difficultyActive,numPieces`, plus the optional file
`/tetris/levels/level_<id>.txt` with one obstacle per line as
`row,col,color`. [`Levels`](src/tetris/Levels.java) reads both and turns them
into one [`Level`](src/tetris/Level.java):

| Field | Meaning |
|---|---|
| `linesRequired` | Completed lines needed to clear the screen |
| `difficultyActive` | Whether extra blocks may appear after a lock |
| `numPieces` | How many piece types may appear (the first N of `Piece.Type`) |
| `cells` | Obstacles as `{row, col, color}`; empty when the screen has no file |

The id of a screen is its position among the data lines, so the table needs no
id column and screen 2 reads `level_2.txt`. Blank lines, comments after `#` and
an optional header are not data. `Levels.load()` reads; `Levels.validate()`
applies the rules — usable values, coordinates inside the board, colours
`1..10`, no repeated cell — and throws with every problem found, naming its
`file:line`. `Level.fill(board)` places the obstacles. The level editor goes
through the same `load` and `validate` in `LevelFiles`, so both accept exactly
the same data.

**The piece set** is `numPieces`. The engine copies it into `piecesInPlay`, and
the draw is `Piece.Type.at(rng.nextInt(piecesInPlay))`: an index in declaration
order, so a classic screen never picks the exotic ones. When a screen allows
fewer types than the previous one, the preview may show a piece that is no longer
allowed; `replaceForbiddenPieces()` draws another one as soon as the new limit is
loaded.

**Difficulty** is also a property of the screen: `difficultyActive` says whether
extra blocks may appear. The engine keeps the count for the current screen and
caps it at `MAX_BLOCKS` (5), with a 1 in `PROBABLE` (4) chance after each lock
and a colour from the first `BLOCK_COLORS` (8). Section 5 shows when a block may
appear.

---

## 3. The four states

A **tick** is one small step of game time. The application calls `Engine.tick()`
sixty times per second and uses [`FrameTimer`](src/tetris/FrameTimer.java) to
turn JavaFX's animation pulses into those steps; one tick does not mean one fall,
because gravity waits 18 ticks between downward moves (0.3 s of game time).

The state of the machine is a `GameState.State` value, and `Engine.tick()` is the
whole map:

| State | Work done on each tick | What comes next |
|---|---|---|
| `FALLING` | Check whether it is time to drop the piece | Another piece, completed lines, or game over when it lands |
| `COMPLETED_LINE` | Advance the flash and remove the row when it ends | Flash another row, spawn a piece, or change screens |
| `SCREEN_TRANSITION` | Advance the fill before loading the next screen | Return to falling |
| `GAME_OVER` | Gradually darken the screen | Wait for a restart |

```java
void tick() {
    if (gameState.isPaused()) return;
    clock.tick();
    effects.tickBlockBlink();          // outlives the fall that started it
    switch (gameState.currentState()) {
        case FALLING            -> applyGravity();
        case COMPLETED_LINE     -> tickCompletedLines();
        case SCREEN_TRANSITION  -> tickScreenTransition();
        case GAME_OVER          -> effects.tickGameOverFade();
    }
}
```

Only one branch runs per tick: while a row is flashing there is no active piece
and gravity does not run. The blink is the exception, and it is called before
the switch because a block may still be blinking when the piece that triggered
it has already gone.

Pause is not a fifth state. It is the early return at the top: it freezes the
clock and the animations and keeps the state to resume. There is no sleeping and
no animation thread; each call does the work due on this tick and returns to the
application's loop.

---

## 4. Inputs

The keyboard lives in `Tetris.java` and calls methods that state what the player
asks for; the engine decides whether it is possible:

| Key | Call | Effect |
|---|---|---|
| ← / → | `moveLateral(-1 / +1)` | Move one column, if it fits |
| ↑ or X | `moveRotate(+1)` | Turn clockwise (to the right), if it fits |
| Z | `moveRotate(-1)` | Turn counter-clockwise (to the left), if it fits |
| ↓ | `fastDrop()` | Request a drop all the way down at the next gravity update |
| P | `togglePause()` | Flip one boolean |
| Restart | `restart()` | Back to screen 1 with a new board |

Movement follows the *try / validate / undo* pattern: change the position, ask
`fitsAt(board)`, restore the previous value when it does not fit.
[PIECES-EXPLAINED.md](PIECES-EXPLAINED.md#5-moving-try-validate-undo) follows
that code with the piece.

A paused game ignores the request instead of queueing it: input is applied now
or dropped, never later. `restart()` is the widest entry point: it clears the
state, resets the clock and the decorations and starts again on screen 1.

---

## 5. A tick in FALLING

`FALLING` is the state where time moves the piece and the player can still
change it. Gravity owns a counter of 18 ticks and asks it on every tick:

```java
private void applyGravity() {
    if (!gravity.trigger()) return;        // 18 ticks have not passed yet
    if (fastDropping) {
        while (gameState.currentPiece() != null && dropPiece() == Drop.FELL) { }
        fastDropping = false;
    } else if (gameState.currentPiece() != null) {
        dropPiece();
    }
    gravity.start();                       // the next period begins here
}
```

Fast-drop is one flag and one loop around the **same** `dropPiece()`, so it
inherits collisions, line clearing and game over without repeating them.

`dropPiece()` has three parts:

1. **Down.** `posY++`, then `fitsAt`. The check is skipped when
   `posY + Piece.SIZE < board.topRow()`: the whole piece is still above the
   highest occupied row, so nothing can be in the way.
2. **Spin the preview.** Every successful drop calls
   `gameState.nextPiece().nextState()`, which is why the waiting piece rotates
   while it waits.
3. **Land.** `posY--` undoes the failed step and `lockPiece` writes the colour
   **and the edge flags** into the board. A cell that still hangs above the top
   row is not written: `Board.inBounds` is the only gate, and the board has no
   cell there to receive it.

The return value names the ways a drop can end, so the caller reads it instead
of inspecting the state again:

| `Drop` | Meaning |
|---|---|
| `FELL` | The piece moved down one row |
| `LOCKED` | The piece is in the board and no line was completed |
| `LOCKED_CLEARING_LINES` | The piece is in the board and its lines are queued for the flash |
| `GAME_OVER` | The piece is in the board but there is no room left |

Only `FELL` is read: it is what keeps the fast-drop loop going.

The lock itself may add a difficulty block (below). After it, in this order: the
game-over check (section 6), the drop sound and the completion test of the four
rows the piece occupies. With no completed row the score grows by one and
`spawnPiece()` promotes the preview and draws a new one, so two pieces exist at
every moment. With completed rows the engine queues them, adds
`POINTS_PER_LINES[numLines - 1]` (`{5, 15, 30, 60}`) and leaves `FALLING`.

**New pieces.** `createPiece(type)` centres the piece and anchors it with its
lowest cell on the first row above the playfield, whatever its height or
rotation: it enters with the next fall and never appears over an occupied cell.

**Difficulty blocks.** On a difficulty screen, a lock may add one extra block.
Three guards keep it fair: the per-screen cap, the dice roll and a top row that
is not nearly full. The block lands on an empty cell of the highest occupied row
and `effects.appearBlock` blinks it, but the block is already part of the board:
it can stop a falling piece while it is momentarily invisible.

---

## 6. The other three states

### COMPLETED_LINE: flash, remove, repair

Completed rows are not removed at once. They go into the queue of `GameState`
and the engine starts the flash of the first one. The row blinks three times,
and while it does the game is in `COMPLETED_LINE` and no piece falls.

Here the engine only needs to know **whether the flash has finished**.
`effects.tickLineFlash()` advances the animation and reports its progress with
`Effects.Step`: `WAITING` means no step was due, `ADVANCED` means a step ran and
`FINISHED` means the animation is over. Only the last answer allows a removal:

```java
if (effects.tickLineFlash() != Effects.Step.FINISHED) return;   // still flashing
int row = gameState.takeFirstCompletedLine();
board.removeRow(row);                             // shift everything above down
gameState.shiftCompletedLinesAfterRemoval(row);   // queued rows have moved down
```

The removal is the board's job; repairing the queue is the state's job, next to
the queue it repairs:

```java
void shiftCompletedLinesAfterRemoval(int removedRow) {
    for (int i = 0; i < completedLines.size(); i++) {
        int row = completedLines.get(i);
        if (row < removedRow) completedLines.set(i, row + 1);
    }
}
```

Removing a row moves every row above it down by one, so any index still in the
queue that was above the cleared row now points one row too high. Forget that
loop and a two-line clear removes the wrong rows: the classic "the data changed
under my feet".

After each removal the engine plays the line sound and decrements `linesLeft`.
If rows remain queued it flashes the next one; when the queue empties, a screen
with no lines left moves to `SCREEN_TRANSITION`, and any other screen spawns a
piece back into `FALLING`.

### SCREEN_TRANSITION: the fill

The pink fill is the reward of a cleared screen. `Effects` paints **one** row
after a wait of 24 ticks; the engine decides which row follows, awards 5 points
and moves on when the walk passes the highest occupied row:

```java
if (effects.tickScreenFill() == Effects.Step.WAITING) return;   // not painted yet
gameState.addScore(POINTS_PER_FILL_ROW);
gameState.advancePaintedRows();
if (gameState.paintedRows() < board.topRow()) {
    effects.startFill(gameState.paintedRows());
    return;
}
advanceScreen();
gameState.setCurrentState(GameState.State.FALLING);
fastDropping = false;
spawnPiece();
```

`paintedRows` does two jobs with one number: it is the next row to paint and the
end of the walk. `board.topRow()` does not move while the fill runs, because
`Board.fillRow` writes cells without touching the height the board reports.
`advanceScreen()` loads the next screen from the data of section 2 — obstacles,
lines required, difficulty and piece set — and wraps around after the last one.

### GAME_OVER: the fade

Game over is decided by the **board**, not by the piece. `Board` tracks
`topRow()`, the highest row that is not empty, and after a lock the engine checks
`board.topRow() < 1`. Row 0 is the top of the playfield, so that condition means
the stack has reached it and there is no room left for another piece to enter.

```java
if (board.topRow() < 1) {
    gameState.setCurrentPiece(null);
    gameState.setCurrentState(GameState.State.GAME_OVER);
    effects.startGameOverFade();
}
```

Setting the current piece to `null` leaves nothing to move or draw.
`isGameOver()` reports the state to the renderer, and the fade that darkens the
picture belongs to `Effects`; section 7 explains how its value reaches the
renderer.

---

## 7. The boundary with the picture

`GameState` is the contract: the engine writes it and the renderer reads it
without changing the game.

| Renderer calls | Answers |
|---|---|
| `gameState.board().rows()`, `gameState.board().cols()` | the size |
| `gameState.board().visibleCellValue(r, c)`, `gameState.board().edgeAt(r, c)` | the cell as drawn |
| `gameState.currentPiece()`, `gameState.nextPiece()` | the two live pieces, or `null` |
| `gameState.points()`, `gameState.linesLeft()` | the markers |
| `gameState.isGameOver()`, `gameState.isPaused()` | the two flags for darkening |
| the brightness passed to `paint` | the game-over fade |

Three things cross that line and are easy to mix up.

**The hidden mask.** The flash and the blink must hide cells without removing
them, so `Board` keeps visibility apart from the value: `valueAt(r, c)` returns
the stored cell, the one collisions use, and `visibleCellValue(r, c)` returns 0
when the cell is hidden. `Effects` writes the mask with `hide` / `show` and
`hideRow` / `showRow`, and a restart clears it. Saying that the renderer only
reads therefore does not mean the engine alone changes the board: visual changes
are delegated to `Effects`, and the engine still decides when they start and
when the rules can continue.

**Edge flags.** Besides its colour, a cell says which sides get a bevel: four
bits shared through [`Edges`](src/tetris/Edges.java). They are unrelated to
the mask — one shapes the drawing, the other hides it.
[PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) builds them for a piece and
[RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) turns them into bevels.

**Brightness.** The renderer decides *when* a factor applies (`isGameOver`,
`isPaused`) and *how much* the pause darkens, with its own constant; the fade
curve lives in `Effects` and the shell reads it once per picture and hands the
value to `paint`; `Palette` decides *how* to darken a colour. One owner per
value, and no constant of the picture inside the model.

---

## 8. Inside the animations: counters and sequences

The rules only need to start an animation, advance it each tick and wait for its
end. This section explains how `Effects` does that work; it can be skipped on a
first reading.

### A counter waits for one period

Gravity and the animations both wait for a known number of ticks.
`Clock.newCounter(duration)` creates a counter and registers it with the clock:

```java
private static final long TICKS_GRAVITY = 18;
private final Clock.Counter gravity = clock.newCounter(TICKS_GRAVITY);
```

`gravity.start()` records the current tick. On later ticks `gravity.trigger()`
returns `false` until 18 ticks have passed, then `true` once, closing that
period; a new `start()` opens the next one. That is the test at the top of
`applyGravity()`.

The clock only increments a `long`. It does not advance the counters on each
tick: each caller checks its own. Registering them gives `Clock.reset()` a way
to stop every period on a restart, and keeps the durations beside the thing that
lasts that long instead of in a table inside the clock.

### A sequence repeats the wait and does something

A blink needs more than one wait: wait, change visibility, wait again, change it
back, and finish. [`Sequence<T>`](src/tetris/Sequence.java) packages that
pattern. Each sequence holds:

- a counter with the ticks between steps;
- a number of repetitions;
- an optional action for each step and an optional action at the end;
- a **target** of type `T`: the row or cell the actions work on, given each time
  a run starts.

`Effects` creates four private sequences in its constructor:

| Sequence | Target | Ticks per step | Repetitions | Work |
|---|---|---|---|---|
| `lineFlash` | Row number | 6 | 6 | Alternate row visibility, then leave it visible |
| `blockBlink` | Row and column | 8 | 4 | Alternate cell visibility, then leave it visible |
| `screenFill` | Row number | 24 | 1 | Paint one transition row and play its sound |
| `gameOverFade` | None | 21 | 3 | Count steps used to calculate the brightness |

The line flash is configured once with its actions:

```java
lineFlash = new Sequence<>(clock, 6, 6,
        (row, repetition) -> {
            if (repetition % 2 == 0) board.showRow(row);
            else                     board.hideRow(row);
        },
        (row, ignored) -> board.showRow(row));
```

The first argument is the target, so the same sequence serves every completed
row. Starting a run first settles the previous one, then prepares the
appearance and hands over the new target:

```java
lineFlash.finish();
board.markCompletedLineEdges(row);
board.hideRow(row);
lineFlash.start(row);
```

`start(row)` stores the target of the run, restores the repetition count and
opens the first period; from there the sequence hands that target to every
action it fires, so an action never asks the sequence for it. `tick()` checks
the counter and, when a period ends, runs the step and either opens the next
period or runs the end action. The end action restores visibility, so a
completed or replaced run cannot leave its old target hidden. The objects are
reused, not recreated per animation.

### Why the engine does not need to know about sequences

`Engine` calls `startLineFlash` and `tickLineFlash`, never `lineFlash.start(row)`
or `lineFlash.tick()`. `Effects` translates the progress of its private
sequences into the `Step` result described in section 6; the same separation
applies to the fill.

The game-over fade only needs a step count: `Effects.fadeProgress()` raises
`0.55` to `gameOverFade.repetitionsDone()`, `Engine.fadeProgress()` forwards it
and the shell hands the value to `paint`, so the renderer applies the curve
without seeing the sequence behind it.

---

## 9. The class map

The sections above explain ideas; this map shows how the files are connected.
**Direct** access means the class appears in a field, a parameter or a `new` of
the reader; **indirect** access means it is reached through another object, such
as `engine.gameState()`, `gameState.board()` or `painter.palette()`.

| File | What it holds | Who has access |
|---|---|---|
| [`Tetris.java`](src/tetris/Tetris.java) | The composition root: canvas, keyboard, timer | Nobody in the package: it is the entry point and the only class that knows all the others |
| [`Engine.java`](src/tetris/Engine.java) | The rules: tick, gravity, locking, lines, screens | `Tetris` (tick, keys, restart, `fadeProgress`); no one else sees it |
| [`GameState.java`](src/tetris/GameState.java) | The memory: board, pieces, score, state, completed-row queue, fill progress and difficulty counter | `Engine` reads and writes it; `Renderer` reads it; `Tetris` only passes it along |
| [`Board.java`](src/tetris/Board.java) | The playfield: cells, edges, hidden mask, geometry | `GameState` owns it; `Engine` and `Renderer` reach it through `gameState.board()`; `Effects` writes it; `Piece`, `Levels`, `Level` and `CellPainter` receive it as a parameter |
| [`Clock.java`](src/tetris/Clock.java) | Ticks and one counter per period | `Engine` creates and ticks it; `Effects` and every `Sequence` receive it to open counters |
| [`Piece.java`](src/tetris/Piece.java) | Ten types, four rotations, the live piece | `Engine` creates and reads it; `GameState` stores the live pieces; `Renderer` reads them through the state; `Levels` uses `Piece.Type.count()` |
| [`Effects.java`](src/tetris/Effects.java) | The four decorations and their sequences | `Engine` only |
| [`Sequence.java`](src/tetris/Sequence.java) | Wait, repeat, run a step | `Effects` only |
| [`Sound.java`](src/tetris/Sound.java) | The interface of the sounds | `Engine` calls it; `JavaFxSound` implements it; `Tetris` chooses the implementation |
| [`JavaFxSound.java`](src/tetris/JavaFxSound.java) | The clips on JavaFX | `Tetris` creates it; the rules only see `Sound` |
| [`FrameTimer.java`](src/tetris/FrameTimer.java) | Pulses into fixed ticks | `Tetris` only |
| [`Renderer.java`](src/tetris/Renderer.java) | Board and markers to pixels | `Tetris` creates it and calls `paint`; no one else |
| [`CellPainter.java`](src/tetris/CellPainter.java) | One cell, one marker, the pixel buffer | `Renderer` and `EditorRenderer`; `LevelEditor` uses one for its screen number; it lends its palette to `Renderer` |
| [`DigitFont.java`](src/tetris/DigitFont.java) | The 4×5 digits and their edges | `CellPainter` only |
| [`Palette.java`](src/tetris/Palette.java) | Colours, bevels and brightness | `CellPainter` and `LevelEditor` own one; `Effects` and `Renderer` use it directly; `Levels` takes `N_FORMCOLORS` |
| [`Edges.java`](src/tetris/Edges.java) | The four bevel bits | `Piece`, `Board`, `DigitFont` and `CellPainter`; no one else works with the flags |
| [`Levels.java`](src/tetris/Levels.java) | The screens and their validation | `Engine` loads and validates them; `LevelFiles` reuses `load` and `fillBoard` |
| [`Level.java`](src/tetris/Level.java) | One screen's four values | `Levels` creates and checks it; `Engine` reads it when a screen starts |
| [`LevelEditor.java`](src/tetris/LevelEditor.java) | The editor's window and input — a separate program | Nobody in the package: it has its own entry point |
| [`LevelFiles.java`](src/tetris/LevelFiles.java) | The editor's copy on disk | `LevelEditor` only |
| [`EditorRenderer.java`](src/tetris/EditorRenderer.java) | The editor's board and hit-testing | `LevelEditor` only |

Two files carry most of the relationships. `Board` is the shared object: the
engine writes it, `Effects` hides and fills it, the levels place obstacles on it
and the painter only reads it. `Palette` is the shared dictionary: a colour is
defined once and asked for by index. The editor is a separate program with its
own `Board`; it only reuses `Levels` (through `LevelFiles`), `Palette`,
`CellPainter` and `Edges`.
