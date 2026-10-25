# Pieces: how a Tetris block is built

### A short tour of [`Piece.java`](src/principal/Piece.java)

A piece has to answer three questions while the game runs:

1. What shape am I, and what do my four rotations look like?
2. Which sides of each cell need a 3D border?
3. Where am I on the board right now?

The first two never change during a game. The third changes sixty times per
second. The whole design of `Piece.java` comes from that split: **compute what
never changes once, and keep what changes as small as possible.**

The rest of the game lives in [Game.java](src/principal/Game.java) (rules),
[Board.java](src/principal/Board.java) (playfield) and
[Renderer.java](src/principal/Renderer.java) (painting). The companion
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
| `Active` | The **live piece**: its type, rotation state and board position | Constantly | 1 falling + 1 in the preview |


Two pieces of the same type **share** the same `Rotations` object:

```java
Active(Type type) {
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
    final boolean[][] cells = new boolean[4][4];

    Shape(int maxRow, int maxCol, int[][] points) {
        this.maxRow = maxRow;
        this.maxCol = maxCol;
        for (int[] p : points) cells[p[0]][p[1]] = true;
    }
}
```

A piece is defined by a handful of numbers, each point written as
`{row, column}`, and declared as an enum constant:

```java
enum Type {
    /** T: vertical stem, middle top. */
    T(1, new Shape(2, 2, new int[][]{{1, 0}, {1, 1}, {1, 2}, {0, 1}})),
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

Those three lines are the body of `Game.copyPiece`; `row`, `col`, `value` and
`edgeBits` arrive from `p.forEachCell(...)` (section 5).

**Why a dense 4×4 grid and not a list of four points?** Collision checks and
border detection are neighbour queries ("is the cell above me empty?"). With a
grid that is `cells[row - 1][col]`; with a list it is a search. Sixteen booleans
buy an entire algorithm.

**`maxRow` / `maxCol` are indexes, not sizes.** `new Shape(2, 2, …)` means "the
last used row and column are 2", so the real box is 3 × 3. That is why every
loop in the class uses `<=`, and why the preview asks `Rotations` for the box size
instead of computing it.

The ten types, in declaration order — the constant is the identity, not a
position in an array:

```
   T            LONG         S            L            L_MIRRORED
   . X .        . X .        X .          X .          . X
   X X X        . X .        X X          X .          . X
                . X .        . X          X X          X X
                . X .

   Z            SQUARE       2L           6            9
   . X          X X          . . X .      X X X        X X .
   X X          X X          . . X .      X X .        X X X
   X .                       . X X X
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
    final int[][][] shapes = new int[4][4][4];  // [state][row][column]
    final int[][][] edges  = new int[4][4][4];
    final Shape template;

    Rotations(Shape template, int color) {
        this.template = template;
        for (int state = 0; state < 4; state++) {
            shapes[state] = buildShape(state, color);
            edges[state]  = buildEdges(state);
        }
    }
}
```

Each constant of `Type` owns its `Rotations`, built once when the enum is
initialised and shared by every piece that falls. The name says exactly what
the object holds: for each of the four `state` values, one colour matrix and its
edge mask. Rotating is then just `state = (state + 1) & 3`: no matrix is ever
transformed during the game.

**`shapes` stores colours, not booleans.** `0` means empty; `1..10` is the
colour value. One matrix answers both "is this cell filled?" (collision) and
"what colour is it?" (drawing).

### Rotating with mirrors instead of trigonometry

`buildShape` does not rotate anything. It rewrites each cell of the template at
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
result[pr][pc] = template.cells[row][col] ? color : 0;
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
**once**, in [Edges.java](src/principal/Edges.java) — the piece computes them,
the board stores them, the renderer draws them, and none of them writes a
literal. One static helper does the counting:

```java
static int mask(int row, int col, Area area) {
    int bits = 0;
    if (!area.isFilled(row, col - 1)) bits |= LEFT;
    if (!area.isFilled(row - 1, col)) bits |= TOP;
    if (!area.isFilled(row + 1, col)) bits |= BOTTOM;
    if (!area.isFilled(row, col + 1)) bits |= RIGHT;
    return bits;
}
```

`buildEdges` only has to say what "filled" means for a piece:

```java
Edges.Area area = (row, col) -> row >= 0 && col >= 0 && row <= lastRow && col <= lastCol
        && shapes[state][row][col] != 0;
result[row][col] = Edges.mask(row, col, area);
```

That is the whole 3D effect: **borders between two cells of the same piece are
not drawn**, so a T looks like one solid block instead of four glued squares.
Worked example with the T in state 0:

```
  . X .      (0,1): left+top+right   = 1+2+8 = 11
  X X X      (1,0): left+top+bottom  = 1+2+4 = 7
             (1,1): bottom only      = 4
             (1,2): top+bottom+right = 2+4+8 = 14
```

The middle cell gets only `4`: it is interior on three sides. A bitmask instead
of four booleans is also why the board can merge flags later with a single `|=`
(the line-clear flash).

Note that `buildEdges` reads `shapes[state]`, so it must run **after**
`buildShape(state)`. The order of those two lines is load-bearing.

---

## 4. `Active`: the piece that falls

```java
static class Active {
    private int state = 0;   // which rotation, 0..3
    private int posX = 0;    // column where the 4x4 matrix is anchored
    private int posY = 0;    // row where the 4x4 matrix is anchored
    private final Type type;
}
```

The type is `final`: a piece never changes shape, so changing the type always
means a new `Active`, and the matrices are reached through the constant.

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

All movement in [Game.java](src/principal/Game.java) follows the
same three steps:

```java
boolean moveLateral(int d) {
    if (paused || d == 0 || currentPiece == null) return false;
    int saved = currentPiece.getPosX();              // 1. remember
    currentPiece.setPosX(saved + (d > 0 ? 1 : -1));  // 2. try
    if (!currentPiece.fitsAt(board)) {               // 3. validate
        currentPiece.setPosX(saved);                 //    undo
        return false;
    }
    return true;
}
```

`moveRotate` is identical, saving and restoring `state`. The alternative,
"check first, then move", forces `fitsAt` to take a hypothetical position
(`wouldFit(x, y, state, board)`): more parameters, and a second source of truth
to keep in sync. Moving the real object and undoing it is shorter and cannot
disagree with itself.

### One walk over the box: `forEachCell`

The game needs the same 4×4 sweep four times: to check a collision, to lock the
piece into the board, and twice to draw it (the falling piece and the preview).
Each one wrote its own double loop and its own `if (empty) continue`; now the
sweep exists once and the four callers only say what to do with a cell:

```java
@FunctionalInterface
interface CellVisitor { boolean visit(int row, int col, int value, int edgeBits); }

boolean forEachCell(CellVisitor visitor) {
    int[][] shape = currentShape();
    int[][] edges = currentEdges();
    for (int row = 0; row < SIZE; row++) {
        for (int col = 0; col < SIZE; col++) {
            if (shape[row][col] == 0) continue;             // empty: cannot collide
            if (!visitor.visit(row, col, shape[row][col], edges[row][col])) return false;
        }
    }
    return true;
}
```

Returning `false` from the visitor aborts the walk, which is exactly what a
failed collision check needs. The coordinates are the piece's own (0..3), never
board ones, so every caller adds the offset itself and no one inherits somebody
else's translation.

### The rulebook is ten lines

```java
boolean fitsAt(Board board) {
    return forEachCell((row, col, value, edgeBits) -> {
        int boardCol = col + posX;
        int boardRow = row + posY;
        if (boardCol < 0 || boardCol >= board.cols() || boardRow >= board.rows()) return false;
        return board.valueAt(boardRow, boardCol) == 0;
    });
}
```

- Only filled cells exist; the `continue` is the definition, not an optimisation.
- The board is asked for its size (`board.cols()`), never `10` hard-coded.
- `row < 0` is deliberately **not** checked: a piece may hang above the board
  while it spawns.
- Collision is only against the board, because there is only one falling piece.

---

## 6. The life of a piece

A piece is born and promoted in `Game`; from there the rules of the game take
over:

1. **Spawn.** `createPiece(type)` makes `new Active(type)` and sets
   `posX = board.cols() / 2`. The type comes from `randomType()`, which draws
   one of the types allowed on the screen.
2. **Pipeline.** `spawnPiece()` promotes `nextPiece` to `currentPiece` and draws
   a new preview, so two pieces exist at every moment of a game.

Gravity, locking, line clearing and game over are the game's side of the story:
[GAME-EXPLAINED.md](GAME-EXPLAINED.md) follows the piece from there.

---

## 7. Where to look

| What you want to see | File | Look for |
|---|---|---|
| The ten pieces | `Piece.java` | `Type` constants, `Shape`, `Type.at` |
| Rotation generation | `Piece.java` | `Rotations.buildShape`, `keepsColumns`, `keepsRows`, `isRotated` |
| Edge flags | `Piece.java` | `Rotations.buildEdges` |
| The edge bits themselves | `Edges.java` | `LEFT`, `TOP`, `BOTTOM`, `RIGHT`, `mask`, `has` |
| Live piece | `Piece.java` | `Active`, `nextState`, `setState`, `fitsAt`, `forEachCell` |
| Try / validate / undo | `Game.java` | `moveLateral`, `moveRotate`, `dropPiece` |
| Spawn and pipeline | `Game.java` | `createPiece`, `randomType`, `spawnPiece`, `restart` |
| Locking | `Game.java` | `copyPiece` |
| Line queue | `Game.java` | `tickLines`, `clearLine` |
| Game modes | `Game.java` | `STATE_PLAY` … `effects.tickFade` |
| Board cells and geometry | `Board.java` | `rows`, `cols`, `setCell`, `removeRow`, `Edges.ALL` |
