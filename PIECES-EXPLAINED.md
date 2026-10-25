# Pieces: how a Tetris block is built

### A short tour of [`Piece.java`](src/tetris/Piece.java)

A piece has to answer three questions while the game runs:

1. What shape am I, and what do my four rotations look like?
2. Which sides of each cell need a 3D border?
3. Where am I on the board right now?

The first two never change during a game. The third changes sixty times per
second. The whole design of `Piece.java` comes from that split: **compute what
never changes once, and keep what changes as small as possible.**

The rest of the game lives in [Engine.java](src/tetris/Engine.java) (rules),
[Board.java](src/tetris/Board.java) (playfield) and
[Renderer.java](src/tetris/Renderer.java) (painting). The companion
documents are [GAME-EXPLAINED.md](GAME-EXPLAINED.md) and
[RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md).

---

## 1. One enum, three classes, four jobs

`Piece` is one file holding the enum of the pieces and three nested classes:

| Type | Job | Changes? | How many exist |
|---|---|---|---|
| `Type` | The **enum of the ten pieces**: template + colour, and it owns the `Rotations` | Never | 10 constants |
| `Shape` | The **template**: which cells of a 4×4 grid are filled | Never | 10, one per type |
| `Rotations` | **The template and its 4 rotation states + their edges** | Never | 10, built at startup |
| `ActivePiece` | The **live piece**: its type, rotation state and board position | Constantly | 1 falling + 1 in the preview |


Two pieces of the same type **share** the same `Rotations` object:

```java
ActivePiece(Type type) {
    this.type = type;   // both pieces point at the same Rotations
}
```

**No piece is identified by a number.** The constant is the identity: asking
for `Piece.Type.L` says which piece it is, and the colour and the four
rotations come with it. When the game needs a count or a type by position, the
enum answers from its own list, `Type.count()` for the number of types and
`Type.at(i)` for the one in declaration position `i`.

Only the mutable part is duplicated per piece; the immutable part exists
exactly once.

The classes are `static` because they do not belong to an instance of `Piece`
(nobody ever writes `new Piece()`), and they are nested because they only make
sense together.

---

## 2. `Shape`: the template

```java
static class Shape {
    final int maxRow;                        // highest row index used
    final int maxCol;                        // highest column index used
    private final boolean[][] cells = new boolean[SIZE][SIZE];

    Shape(int maxRow, int maxCol, int[][] points) {
        this.maxRow = maxRow;
        this.maxCol = maxCol;
        for (int[] p : points) cells[p[0]][p[1]] = true;
    }

    boolean isFilled(int row, int column) {  // the only door to the matrix
        return cells[row][column];
    }
}
```

`cells` is private on purpose. `final` freezes the *reference*, not the
contents, so a `final boolean[][] cells` handed out would still let any caller
write `shape.cells[0][0] = true` and reshape the template every piece of that
type is built from. The matrix stays inside and callers ask one cell at a time.

A piece is defined by a handful of numbers, each point written as
`{row, column}`, and declared as an enum constant:

```java
enum Type {
    /** T: vertical stem, middle top. */
    T(1, new Shape(1, 2, new int[][]{{1, 0}, {1, 1}, {1, 2}, {0, 1}})),
    …
}
```

`{0, 1}` is the stem: *row 0, column 1*, one cell above the bar. The order is
`[row][column]`, the same as the board, so a piece cell goes into the playfield
with no reordering:

```java
int boardRow = row + p.getPosY();
int boardCol = col + p.getPosX();
board.setCell(boardRow, boardCol, value, edgeBits);
```

Those three lines are the body of `Engine.lockPiece`; `row`, `col`, `value` and
`edgeBits` arrive from `p.forEachCell(...)` (section 5).

**Why a dense 4×4 grid and not a list of four points?** Collision checks and
border detection are neighbour queries ("is the cell above me empty?"). With a
grid that is `cells[row - 1][col]`; with a list it is a search. Sixteen booleans
buy an entire algorithm.

**`maxRow` / `maxCol` are indexes, not sizes.** `new Shape(1, 2, …)` means "the
last used row and column are 1 and 2", so the real box is 2 × 3. That is why every
loop in the class uses `<=`, and why the preview asks `Rotations` for the box size
instead of computing it.

**The box is the height of the piece.** Both numbers are the extent of the cells,
so `maxRow` is the height of the piece standing up and `maxCol` the height of the
piece lying down, which is what `Rotations.boxRows` returns. Pieces are born in
rotation 0, so `spawnPosY()` anchors them by that height: the lowest cell is the
first row above the playfield and one fall away from being seen.

**The long bar is the one box with spare columns**, one on each side of its stem.
Rotation swaps rows and columns, so those spare columns become the spare row of
the bar lying down: with the stem in the middle the bar turns around itself,
while a one-column box would turn around its left edge and jump half a bar
sideways. No shape needs a spare row, because pieces are born upright.

The ten types, in declaration order — the constant is the identity, not a
position in an array:

```
   T            LONG         S            L            L_MIRRORED
   . X .        . X .        X .          X .          . X
   X X X        . X .        X X          X .          . X
                . X .        . X          X X          X X
                . X .

   Z            SQUARE       2L           6            9
   . X          X X          . X .        X X X        X X .
   X X          X X          . X .        X X .        X X X
   X .                       X X X
```

Each drawing is the shape exactly as it is written in `Piece.java`, and also
the shape that appears on screen: rotation state 0 is the template unchanged.

The first 7 are the classic set; 2L, 6 and 9 are exotics. A screen chooses how
many are in play with the `numPieces` column of `levels.csv`, and that number
is simply the bound of the draw: `Type.at(rng.nextInt(numPieces))` can only
return the first N in declaration order, so `7` means exactly the classic set.
No list is copied — the enum keeps the only array there is, and a screen only
decides how far into it the draw may go.

---

## 3. `Rotations`: everything precomputed

`Rotations` takes a template and a colour and builds **all four rotation states**,
plus the borders of each one, in the constructor:

```java
static class Rotations {
    private final int[][][] states = new int[SIZE][SIZE][SIZE];  // [state][row][column]
    private final int[][][] edges  = new int[SIZE][SIZE][SIZE];
    private final Shape template;

    Rotations(Shape template, int color) {
        this.template = template;
        for (int state = 0; state < 4; state++) {
            states[state] = buildRotation(state, color);
            edges[state]  = buildEdges(state);
        }
    }

    int valueAt(int state, int row, int column) { return states[state][row][column]; }
    int edgeAt(int state, int row, int column)  { return edges[state][row][column]; }
}
```

Both matrices are `private`, and that is the part that matters. Making them
`final` only stops someone from *replacing* the array by another one; a
three-dimensional array is mutable even when the reference is final, so a
`states(state)` accessor would hand out the real matrix and let a caller break
every piece of that type for the rest of the game. Reading one cell at a time
through `valueAt`/`edgeAt` closes that door, and the walks that need every cell
ask for the cells, so no copy is made per frame.

Each constant of `Type` owns its `Rotations`, built once when the enum is
initialised and shared by every piece that falls. The name says exactly what
the object holds: for each of the four `state` values, one colour matrix and its
edge flags. Rotating is then just `state = (state + 1) & 3`: no matrix is ever
transformed during the game.

**`states` stores colours, not booleans.** `0` means empty; `1..10` is the
colour value. One matrix answers both "is this cell filled?" (collision) and
"what colour is it?" (drawing).

### Rotating with mirrors instead of trigonometry

`buildRotation` does not rotate anything. It rewrites each cell of the template at
a new position, combining three rules:

| `state` | mirror columns | mirror rows | swap row/col | T looks like |
|---|---|---|---|---|
| 0 | no | no | no | stem up |
| 1 | no | yes | yes | stem right |
| 2 | yes | yes | no | stem down |
| 3 | yes | no | yes | stem left |

The four states go **clockwise**: 0 (up) → 1 (right) → 2 (down) → 3 (left). So
`nextState()`, that is `state + 1`, turns the piece to the right, and
`prevState()` turns it to the left — the same sign convention as
`moveLateral`.

```java
int pr = row, pc = col;
if (!keepsColumns(state)) pc = template.maxCol - col;   // mirror left/right
if (!keepsRows(state))    pr = template.maxRow - row;   // mirror up/down
if (isRotated(state))     { int t = pr; pr = pc; pc = t; }  // swap
result[pr][pc] = template.isFilled(row, col) ? color : 0;
```

A mirror plus a coordinate swap *is* a 90° rotation. Integers give exact
results, every time; `Math.cos(90°)` does not give 0. Symmetric pieces simply
end up with duplicate states (the square is identical in all four), and nothing
needs a special case.

### `edges`: the 3D relief

An "edge" here is not the outline of the shape: it is a **per-cell flag** that
says which of that cell's four sides must be drawn bright or dark.

| Constant | Value | Side | Painted |
|---|---|---|---|
| `Edges.LEFT` | 1 | left | bright (light from the top-left) |
| `Edges.TOP` | 2 | top | bright |
| `Edges.BOTTOM` | 4 | bottom | dark |
| `Edges.RIGHT` | 8 | right | dark |

The flag is set when the neighbour in that direction is empty or does not
exist. The numbers are the same everywhere in the project, so they are declared
**once**, in [Edges.java](src/tetris/Edges.java) — the piece computes them,
the board stores them, the renderer draws them, and none of them writes a
literal. The rule and the walk live there too:

```java
static void calculateEdges(Occupancy occupied, int boxRows, int boxColumns, EdgeWriter writer) {
    for (int row = 0; row < boxRows; row++)
        for (int column = 0; column < boxColumns; column++) {
            if (!occupied.isOccupied(row, column)) continue;
            int edges = edgeCalculation(occupied, row, column,
                                        boxRows, boxColumns);
            writer.write(row, column, edges);
        }
}
```

`edgeCalculation` answers the four neighbours of one cell; `calculateEdges` walks
the box and skips the empty cells. So `Rotations.buildEdges` has nothing left to
spell out:

```java
int[][] result = new int[SIZE][SIZE];
Edges.calculateEdges((row, col) -> states[state][row][col] != 0,
                boxRows(state), boxCols(state),
                (row, col, edges) -> result[row][col] = edges);
```

and the digit font says the same about a digit (`pixels[row][column]`, its own
size, its own matrix). Two answers to *is this cell mine?* and *where do its
edges go?*: that is all a caller has to know about the edge flags.

That is the whole 3D effect: **borders between two cells of the same piece are
not drawn**, so a T looks like one solid block instead of four glued squares.
Worked example with the T in state 0:

```
  . X .      (0,1): left+top+right   = 1+2+8 = 11
  X X X      (1,0): left+top+bottom  = 1+2+4 = 7
             (1,1): bottom only      = 4
             (1,2): top+bottom+right = 2+4+8 = 14
```

The middle cell gets only `4`: it is interior on three sides. Four bits in one
integer instead of four booleans is also why the board can merge flags later
with a single `|=` (the line-clear flash).

Note that `buildEdges` reads `states[state]`, so it must run **after**
`buildRotation(state)`. The order of those two lines is load-bearing.

---

## 4. `ActivePiece`: the piece that falls

```java
static class ActivePiece {
    private int state = 0;   // which rotation, 0..3
    private int posX = 0;    // column where the 4x4 matrix is anchored
    private int posY = 0;    // row where the 4x4 matrix is anchored
    private final Type type;
}
```

The type is `final`: a piece never changes shape, so changing the type always
means a new `ActivePiece`, and the matrices are reached through the constant.

Three integers and a pointer: that is the entire state of a falling piece.

**`posX`/`posY` are the origin of the matrix, not the piece.** The piece is
stored as *(which matrix)* + *(where its top-left corner goes)*, so cell
`(row, column)` of the matrix lands on:

```
boardRow = row + posY
boardCol = column + posX
```

Moving left is `posX--`, rotating is `state = (state + 1) & 3`, and undoing a
move restores one integer. Because all cells share the same offset, a piece can
be in the wrong place, but it can never be the wrong *shape*.

**Nothing is public: every change goes through a method.** `setState` keeps the
rotation inside `0..3`, and `setPosX`/`setPosY` are the only way to place the
piece — the renderer reads `getPosX()`/`getPosY()` and cannot move it by
accident.

```java
void nextState() { setState((state + 1) & 3); }
void prevState() { setState((state + 3) & 3); }
void setState(int s) { state = s & 3; }   // & 3 also works for negatives
```

The setter is the only door, and the door is narrow. (`-1 % 4` is `-1` in Java;
`-1 & 3` is `3`.)

---

## 5. Moving: try, validate, undo

All movement in [Engine.java](src/tetris/Engine.java) follows the
same three steps:

```java
boolean moveLateral(int d) {
    Piece.ActivePiece p = gameState.currentPiece();
    if (p == null || d == 0 || gameState.isPaused()) return false;
    int saved = p.getPosX();                // 1. remember
    p.setPosX(saved + Integer.signum(d));   // 2. try
    if (p.fitsAt(board)) return true;       // 3. validate: it fits
    p.setPosX(saved);                       //    undo
    return false;
}
```

`moveRotate` follows the same three steps over the rotation: save
`getState()`, turn, check, undo. The alternative,
"check first, then move", forces `fitsAt` to take a hypothetical position
(`wouldFit(x, y, rotation, board)`): more parameters, and a second source of
truth to keep in sync. Moving the real object and undoing it is shorter and
cannot disagree with itself.

### One walk over the box: `forEachCell`

The game needs the same 4×4 sweep four times: to check a collision, to lock the
piece into the board, and twice to draw it (the falling piece and the preview).
Each one wrote its own double loop and its own `if (empty) continue`; now the
sweep exists once and the four callers only say what to do with a cell:

```java
@FunctionalInterface
interface CellVisitor { boolean visit(int row, int col, int value, int edgeBits); }

boolean forEachCell(CellVisitor visitor) {
    for (int row = 0; row < SIZE; row++) {
        for (int col = 0; col < SIZE; col++) {
            int value = type.rotations.valueAt(state, row, col);
            if (value == 0) continue;                       // empty: cannot collide
            int edges = type.rotations.edgeAt(state, row, col);
            if (!visitor.visit(row, col, value, edges)) return false;
        }
    }
    return true;
}
```

There is no `currentShape()` any more: the walk reads the two cells it needs
from the shared `Rotations`, so it never asks for a matrix and never holds one.

Returning `false` from the visitor aborts the walk, which is exactly what a
failed collision check needs. The coordinates are the piece's own (0..3), never
board ones, so every caller adds the offset itself and no one inherits somebody
else's translation.

### The rulebook is four lines

```java
boolean fitsAt(Board board) {
    return forEachCell((row, col, value, edgeBits) ->
            board.isFree(row + posY, col + posX));
}
```

- Only filled cells exist; the `continue` is the definition, not an optimisation.
- The board owns the whole rule, `Board.isFree`: a cell **above** the top row is
  free (a piece enters the playfield from there), a column outside the board or a
  row below it is blocked, and inside the board only an empty cell is free. The
  piece no longer reads a row the matrix does not have, and it does not repeat a
  `10` or a size bound of its own.
- Collision is only against the board, because there is only one falling piece.

---

## 6. The life of a piece

A piece is born and promoted in `Engine`; from there the rules of the game take
over:

1. **Spawn.** `createPiece(type)` makes `new ActivePiece(type)`, sets
   `posX = board.cols() / 2` and anchors `posY` through `spawnPosY()`, which
   lifts the box of the piece just above the playfield (`-boxRows(state)`:
   `maxRow` standing up). Pieces are born in rotation 0, where the box is the
   real height, so the lowest cell sits on the first row above the board: the
   piece enters the playfield with the next fall and never appears over a cell
   that is already occupied. The type comes from `randomType()`, which draws one
   of the types allowed on the screen.
2. **Pipeline.** `spawnPiece()` promotes `nextPiece` to `currentPiece` and draws
   a new preview, so two pieces exist at every moment of a game.

Gravity, locking, line clearing and game over are the engine's side of the story:
[GAME-EXPLAINED.md](GAME-EXPLAINED.md) follows the piece from there.
