# Game engine: the rules

### A short tour of [`Engine.java`](src/tetris/Engine.java)

`Engine` answers the questions that make this a game: can the piece move, when
does it fall, what happens when it lands, and when does the player advance to
the next level? It applies those rules but does not draw the result.

A normal turn follows this path:

1. A piece appears and the player can move or rotate it while it falls.
2. When it can no longer descend, its cells become part of the board.
3. If it completes any rows, those rows blink and are removed; otherwise the
   next piece appears immediately.
4. Clearing enough rows starts the next level. Reaching the top ends the game.

The engine only moves when it is ticked, and each tick does the work of the
state the game is in. This document explains the parts it is made of, the data
it starts from, the states it can be in, what a tick does in each of them and
where the picture gets what it draws.

Reading order for the companion documents: [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md)
(the active piece) and [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) (how the
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
| `GameState` | Remembers the game: score, lines left, both pieces, current state, paused flag, completed-row queue and difficulty counter | written by `Engine`, read by `Renderer` |
| `Board` | The playfield: geometry, cell values, edge flags and the hidden mask | `GameState`, `Engine`, `Effects`, `Renderer` |
| `Clock` | Counts ticks and hands out one counter per period | `Engine` for gravity, `Effects` for the animations |
| `Levels` | The levels read from `levels.csv` and `levels/*.txt` | `Engine` when it changes level |
| `Effects` | The decorations: the two blinks, the fill and the fade | started and ticked by `Engine` |
| `Sound` | Sound effects behind an interface | `Engine` |
| `Random` | Chooses pieces and random obstacles | `Engine` |

**The engine only orchestrates: it decides, then asks the owner to change.**
The board owns the geometry, `Piece` owns the shapes, `Levels` owns the data.
The queue of completed rows is a field of `GameState`, like the score: a fact
remembered between ticks, not authority over the game. The same separation keeps
the rules away from the presentation: the engine decides that a completed row
must disappear and asks `Effects` to make it blink first. How the blink works
does not change a single rule, so it is explained at the end.

---

## 2. The data the game starts from

Before the first tick, three things are fixed: the playfield, the levels and
which pieces may appear. They are the static part of the game; the engine reads
them and never edits them.

**The playfield** is a fixed 10 × 20 grid. [`Board`](src/tetris/Board.java)
owns its geometry — `rows()`, `cols()` and `firstOccupiedRow()`, the first row
that is not empty — so no other class repeats the size or the height of the
stack.

**A level** is one line of `/tetris/levels.csv`, with the fields
`linesRequired,difficultyActive,numPieces`, plus the optional file
`/tetris/levels/level_<id>.txt` with one obstacle per line as
`row,col,color`. [`Levels`](src/tetris/Levels.java) reads both and turns them
into one [`Levels.Level`](src/tetris/Levels.java):

| Field | Meaning |
|---|---|
| `linesRequired` | Completed lines needed to clear the level |
| `difficultyActive` | Whether extra blocks may appear after a lock |
| `numPieces` | How many piece types may appear (the first N of `Piece.Type`) |
| `cells` | Obstacles as `{row, col, color}`; empty when the level has no file |

The id of a level is its position among the data lines, so the table needs no
id column and level 2 reads `level_2.txt`. Blank lines, comments after `#` and
an optional header are not data. `Levels.load()` reads; `Levels.validate()`
applies the rules — usable values, coordinates inside the board, colours
`1..10`, no repeated cell — and throws with every problem found, naming its
`file:line`. `Levels.Level.fill(board)` places the obstacles. The level editor
goes through the same `load` and `validate` in `EditorLevelFiles`, so both
accept exactly the same data.

**The piece set** is `numPieces`. The engine copies it into `piecesInPlay`, and
the draw is `Piece.Type.at(rng.nextInt(piecesInPlay))`: an index in declaration
order, so a classic level never picks the exotic ones. When a level allows
fewer types than the previous one, the preview may show a piece that is no longer
allowed; `replaceForbiddenPieces()` draws another one as soon as the new limit is
loaded.

**Difficulty** is also a property of the level: `difficultyActive` says whether
extra blocks may appear. The engine keeps the count for the current level and
caps it at `MAX_DIFFICULTY_BLOCKS` (5), with a 1 in `DIFFICULTY_ONE_IN` (4)
chance after each lock and a colour from the first `DIFFICULTY_COLORS` (8). A
difficulty block is a single cell, not a piece. Section 5 shows when one may
appear.

---

## 3. The four states

A **tick** is one small step of game time. The application calls `Engine.tick()`
sixty times per second and uses [`Clock.FrameTimer`](src/tetris/Clock.java) to
turn JavaFX's animation pulses into those steps; one tick does not mean one fall,
because gravity waits 18 ticks between downward moves (0.3 s of game time).

The state of the machine is a `GameState.State` value, and `Engine.tick()` is the
whole map:

| State | Work done on each tick | What comes next |
|---|---|---|
| `FALLING` | Check whether it is time to drop the piece | Another piece, completed lines, or game over when it lands |
| `COMPLETED_LINE` | Advance the blink and remove the row when it ends | Blink another row, spawn a piece, or change level |
| `LEVEL_TRANSITION` | Advance the fill before loading the next level | Return to falling |
| `GAME_OVER` | Gradually darken the screen | Wait for a restart |

```java
void tick() {
    if (gameState.isPaused()) return;
    clock.tick();
    effects.tickCellBlink();           // outlives the fall that started it
    switch (gameState.currentState()) {
        case FALLING            -> applyGravity();
        case COMPLETED_LINE     -> tickCompletedLines();
        case LEVEL_TRANSITION   -> tickLevelTransition();
        case GAME_OVER          -> effects.tickGameOverFade();
    }
}
```

Only one branch runs per tick: while a row is blinking there is no active piece
and gravity does not run. The cell blink is the exception, and it is called
before the switch because a cell may still be blinking when the piece that
triggered it has already gone.

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
| Restart | `restart()` | Back to level 1 with a new board |

Movement follows the *try / validate / undo* pattern: change the position, ask
`fitsAt(board)`, restore the previous value when it does not fit.
[PIECES-EXPLAINED.md](PIECES-EXPLAINED.md#5-moving-try-validate-undo) follows
that code with the piece.

A paused game ignores the request instead of queueing it: input is applied now
or dropped, never later. `restart()` is the widest entry point: it clears the
state, resets the clock and the decorations and starts again on level 1.

---

## 5. A tick in FALLING

`FALLING` is the state where time moves the piece and the player can still
change it. Gravity owns a counter of 18 ticks and asks it on every tick:

```java
private void applyGravity() {
    if (!gravity.trigger()) return;        // 18 ticks have not passed yet
    if (fastDropping) {
        while (gameState.activePiece() != null && dropPiece() == Drop.FELL) { }
        fastDropping = false;
    } else if (gameState.activePiece() != null) {
        dropPiece();
    }
    gravity.start();                       // the next period begins here
}
```

Fast-drop is one flag and one loop around the **same** `dropPiece()`, so it
inherits collisions, line clearing and game over without repeating them.

`dropPiece()` has three parts:

1. **Down.** The target row is computed once and written once
   (`newPieceRow = pieceRow + 1`), then `fitsAt` decides. The check is skipped
   when `newPieceRow + Piece.SIZE < board.firstOccupiedRow()`: the whole piece
   is still above the first occupied row, so nothing can be in the way.
2. **Spin the preview.** Every successful drop calls
   `gameState.nextPiece().nextState()`, which is why the waiting piece rotates
   while it waits.
3. **Land.** `pieceRow` is written back into the piece and `lockPiece` writes
   the colour **and the edge flags** into the board. A cell that still hangs
   above row 0 is not written: `Board.inBounds` is the only gate, and the
   board has no cell there to receive it.

The return value names the ways a drop can end, so the caller reads it instead
of inspecting the state again:

| `Drop` | Meaning |
|---|---|
| `FELL` | The piece moved down one row |
| `LOCKED` | The piece is in the board and no line was completed |
| `LOCKED_CLEARING_LINES` | The piece is in the board and its lines are queued for the blink |
| `GAME_OVER` | The piece is in the board but there is no room left |

Only `FELL` is read: it is what keeps the fast-drop loop going.

The lock itself may add a difficulty block (below). After it, in this order: the
game-over check (section 6), the drop sound and the completion test of the four
rows the piece occupies. With no completed row the score grows by one and
`spawnPiece()` promotes the preview and draws a new one, so two pieces exist at
every moment. With completed rows the engine queues them, adds
`SCORE_PER_LINE_SET[numLines - 1]` (`{5, 15, 30, 60}`) and leaves `FALLING`.

**New pieces.** `createPiece(type)` centres the piece and places it with its
lowest cell on the first row above the playfield, whatever its height or
rotation: it enters with the next fall and never appears over an occupied cell.

**Difficulty blocks.** On a difficulty level, a lock may add one extra block.
Three guards keep it fair: the per-level cap, the dice roll and a row that must
still have **two** empty cells — filling the last hole would seal the row, since
no piece could complete it again. The block lands on one of the empty cells of
the first occupied row, drawn at random, and `effects.startCellBlink` blinks
it. The block is already part of the board, so it can stop the active piece
while it is momentarily invisible.

---

## 6. The other three states

### COMPLETED_LINE: blink, remove, repair

Completed rows are not removed at once. They go into the queue of `GameState`
and the engine starts the blink of the lowest one. The row blinks three times,
and while it does the game is in `COMPLETED_LINE` and no piece falls.

Here the engine only needs to know **whether the blink has finished**, and that
is exactly what `effects.tickLineBlink()` answers: it advances the animation and
returns `true` on the tick that closes it, `false` on every other tick. Only that
tick allows a removal:

```java
if (!effects.tickLineBlink()) return;   // still blinking
GameState.CompletedLines completedLines = gameState.completedLines();
int row = completedLines.takeNextRow();
board.removeRow(row);                        // everything above falls one row
completedLines.advancePieceRow();            // and the queue follows, piece row included
```

The removal is the board's job. The queue, though, stores **no absolute board
row**: a row number is a moving target, because `removeRow` shifts everything
above the cleared row one step down, and a two-line clear would then blink the
wrong rows. What never changes is the distance between the rows of one piece, so
the queue keeps the **rows of the piece's box** (0..3) plus the board row the
piece sat at when it locked, which it calls `pieceRow`:

```java
gameState.completedLines().queue(pieceRow, r);   // r = 3, 2, 1, 0: from the floor up
```

The row a blink works on is `pieceRow + boxRow`, and a collapse moves `pieceRow`
a step on, so the whole queue follows at once: `completedLines.advancePieceRow()` in
the code above is the entire repair.

The blink always takes the lowest queued row, so every row still waiting lies
above the removed one, and one step of `pieceRow` keeps all of them true.

After each removal the engine plays the line sound and decrements `linesLeft`.
If rows remain queued it blinks the next one; when the queue empties, a level
with no lines left moves to `LEVEL_TRANSITION`, and any other level spawns a
piece back into `FALLING`.

### LEVEL_TRANSITION: the fill

The pink fill is the reward of a cleared level. `Effects` fills **one** row
after a wait of 24 ticks; the engine decides which row follows, awards 5 points
and moves on when the walk passes the first occupied row. `tickTransitionFill()`
answers the same question as the blink — `true` on the tick that closes the run —
and a run fills a single row, so that tick is the one that fills it:

```java
if (!effects.tickTransitionFill()) return;   // not filled yet
gameState.addScore(SCORE_PER_FILL_ROW);

fillRow++;
if (fillRow < board.firstOccupiedRow()) {
    effects.startTransitionFill(fillRow);
    return;
}
advanceLevel();
gameState.setCurrentState(GameState.State.FALLING);
fastDropping = false;
spawnPiece();
```

`fillRow` does two jobs with one number: it is the next row to fill and the end
of the walk. It is an engine field, like `levelIndex`: no one outside the engine
reads it, so the state does not carry it. `board.firstOccupiedRow()` does not
move while the fill runs, because `Board.fillRow` writes cells without touching
the height the board reports. `advanceLevel()` loads the next level from the data
of section 2 — obstacles, lines required, difficulty and piece set — and wraps
around after the last one.

### GAME_OVER: the fade

Game over is decided by the **board**, not by the piece. `Board` tracks
`firstOccupiedRow()`, the first row that is not empty, and after a lock the
engine checks `board.firstOccupiedRow() < 1`. Row 0 is the top of the playfield,
so that condition means the stack has reached it and there is no room left for
another piece to enter.

```java
if (board.firstOccupiedRow() < 1) {
    gameState.setActivePiece(null);
    gameState.setCurrentState(GameState.State.GAME_OVER);
    effects.startGameOverFade();
}
```

Setting the active piece to `null` leaves nothing to move or draw.
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
| `gameState.activePiece()`, `gameState.nextPiece()` | the active piece and the waiting one, or `null` |
| `gameState.score()`, `gameState.linesLeft()` | the markers |
| `gameState.isGameOver()`, `gameState.isPaused()` | the two flags for darkening |
| the brightness passed to `paint` | the game-over fade |

Three things cross that line and are easy to mix up.

**The hidden mask.** The two blinks must hide cells without removing them, so
`Board` keeps visibility apart from the value: `valueAt(r, c)` returns the
stored cell, the one collisions use, and `visibleCellValue(r, c)` returns 0
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
back, and finish. [`Effects.Sequence<T>`](src/tetris/Effects.java) packages that
pattern. Each sequence holds:

- a counter with the ticks between steps;
- a number of repetitions;
- an optional action for each step and an optional action at the end;
- a **target** of type `T`: the row or cell the actions work on, given each time
  a run starts.

`Effects` creates four private sequences in its constructor:

| Sequence | Target | Ticks per step | Repetitions | Work |
|---|---|---|---|---|
| `lineBlink` | Row number | 6 | 6 | Alternate row visibility, then leave it visible |
| `cellBlink` | Row and column | 8 | 4 | Alternate cell visibility, then leave it visible |
| `transitionFill` | Row number | 24 | 1 | Fill one transition row and play the fill sound |
| `gameOverFade` | None | 21 | 3 | Count steps used to calculate the brightness |

The line blink is configured once with its actions:

```java
lineBlink = new Sequence<>(clock, 6, 6,
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
lineBlink.finish();
board.markCompletedLineEdges(row);
board.hideRow(row);
lineBlink.start(row);
```

`start(row)` stores the target of the run, restores the repetition count and
opens the first period; from there the sequence hands that target to every
action it fires, so an action never asks the sequence for it. `tick()` checks
the counter and, when a period ends, runs the step and either opens the next
period or runs the end action. The end action restores visibility, so a
completed or replaced run cannot leave its old target hidden. The objects are
reused, not recreated per animation.

### Why the engine does not need to know about sequences

`Engine` calls `startLineBlink` and `tickLineBlink`, never `lineBlink.start(row)`
or `lineBlink.tick()`. All `Effects` reports is the boolean of section 6, the tick
that closes the run; the same separation applies to the fill.

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
| [`Engine.java`](src/tetris/Engine.java) | The rules: tick, gravity, locking, lines, levels | `Tetris` (tick, keys, restart, `fadeProgress`); no one else sees it |
| [`GameState.java`](src/tetris/GameState.java) | The memory: board, pieces, score, state, completed-row queue and difficulty counter | `Engine` reads and writes it; `Renderer` reads it; `Tetris` only passes it along |
| [`Board.java`](src/tetris/Board.java) | The playfield: cells, edges, hidden mask, geometry | `GameState` owns it; `Engine` and `Renderer` reach it through `gameState.board()`; `Effects` writes it; `Piece`, `Levels` and `Renderer.CellPainter` receive it as a parameter |
| [`Clock.java`](src/tetris/Clock.java) | Ticks, one counter per period, and the nested `FrameTimer` that turns the pulses of the shell into ticks | `Engine` creates and ticks it, and `Effects` opens counters in it; `Tetris` owns its `FrameTimer` |
| [`Piece.java`](src/tetris/Piece.java) | Ten types, four rotations, the active piece | `Engine` creates and reads it; `GameState` stores the active piece and the waiting one; `Renderer` reads them through the state; `Levels` uses `Piece.Type.count()` |
| [`Effects.java`](src/tetris/Effects.java) | The four decorations, each one a private `Sequence` with its period, repetitions and actions (nested at the bottom of the file) | `Engine` only |
| [`Sound.java`](src/tetris/Sound.java) | The interface of the sounds, and the silent implementation | `Engine` and `Effects` call it; `Tetris.JavaFxSound` (nested in the shell) implements it and `Tetris` chooses |
| [`Renderer.java`](src/tetris/Renderer.java) | Board and markers to pixels, plus the nested `Palette` and `CellPainter` | `Tetris` creates it and calls `paint`; `EditorRenderer` and `EditorLevels` borrow its pixel layer; no one else |
| [`DigitFont.java`](src/tetris/DigitFont.java) | The 4×5 digits and their edges | `Renderer.CellPainter` only |
| [`Edges.java`](src/tetris/Edges.java) | The four bevel bits | `Piece`, `Board`, `DigitFont` and `Renderer.CellPainter`; no one else works with the flags |
| [`Levels.java`](src/tetris/Levels.java) | The levels, their validation and the nested `Level` | `Engine` loads and validates them; `EditorLevelFiles` reuses `load` and `fillBoard` |
| [`EditorLevels.java`](src/tetris/EditorLevels.java) | The editor's window and input — a separate program | Nobody in the package: it has its own entry point |
| [`EditorLevelFiles.java`](src/tetris/EditorLevelFiles.java) | The editor's copy on disk | `EditorLevels` only |
| [`EditorRenderer.java`](src/tetris/EditorRenderer.java) | The editor's board and hit-testing | `EditorLevels` only |

Two files carry most of the relationships. `Board` is the shared object: the
engine writes it, `Effects` hides and fills it, the levels place obstacles on it
and the painter only reads it. `Renderer.Palette` is the shared dictionary: a
colour is defined once and asked for by index. The editor is a separate program
with its own `Board`; it only reuses `Levels` (through `EditorLevelFiles`),
`Renderer.Palette`, `Renderer.CellPainter` and `Edges`.
