# Análisis del Proyecto: Tetris FX

> Implementación completa de Tetris en **Java 17+ / JavaFX** con arquitectura limpia, motor a 60 ticks/seg fijos, renderizado por pipeline de píxeles propio (`WritableImage` + `PixelWriter`), 10 tipos de pieza (7 clásicas + 3 exóticas), 9 niveles configurables por archivos de texto y editor gráfico incluido.

---

## 🎮 Qué hace

| Característica | Detalle |
|----------------|---------|
| **Motor** | 60 ticks/seg fijos (`Clock.FrameTimer` convierte pulsos de monitor en ticks), *rules-only*, sin dependencias de JavaFX |
| **Renderizado** | `WritableImage` + `PixelWriter`, escalado proporcional (cualquier `--width/--height` ≥ 105×140 px) |
| **Tablero** | 10×20 clásico, con **edge flags 3D** (relieve brillante/oscuro en cada celda) |
| **Piezas** | **10 tipos**: 7 clásicas (T, I, S, Z, L, J, O) + **3 exóticas** (2L, 6, 9) |
| **Niveles** | 9 niveles definidos en `levels.csv` + `levels/level_N.txt` (obstáculos pre-colocados) |
| **Progresión** | Cada nivel pide `linesRequired` líneas; al completar → *transition fill* rosa fila a fila → siguiente nivel (wrap al final) |
| **Dificultad** | Niveles con `difficultyActive=true`: 1/4 de probabilidad tras cada *lock* de añadir un bloque obstáculo (máx 5 por nivel) |
| **Efectos** | Parpadeo de línea completada, *transition fill* rosa, *fade* a negro en Game Over, parpadeo de bloque de dificultad |
| **Sonido** | 3 clips (drop, line, block) + *fill beep* más suave; interfaz `Sound` inyectada (testable/mutable) |
| **Controles** | ←/→ mover, ↑/X rotar ↻, Z rotar ↺, ↓ *fast drop*, P pausa, botón Restart |
| **Editor de niveles** | `tetris.EditorLevels`: segunda entrada principal, pinta obstáculos con ratón y escribe los archivos fuente |

---

## 💡 Interés / Valor del proyecto

1. **Arquitectura de referencia**  
   Separación nítida **Engine (rules) ⟷ GameState (data) ⟷ Renderer (pixels)**. El motor no conoce JavaFX; el renderizador no conoce reglas. Ideal para estudiar *clean architecture* en juegos.

2. **Time-step fijo desacoplado del frame-rate**  
   `Clock.FrameTimer` + `Clock.Counter` — patrón robusto para cualquier juego en JavaFX.

3. **Pipeline de píxeles propio**  
   `CellPainter` + `Palette` + `WritableImage` evita overhead de nodos JavaFX; 3D relief por *edge flags* calculados geométricamente (`Edges.calculateEdges`).

4. **Sistema de niveles data-driven**  
   CSV + TXT plano, validación exhaustiva al arranque, editor gráfico incluido. Extensible sin tocar código.

5. **Piezas exóticas bien integradas**  
   3 formas nuevas (2L, 6, 9) con rotaciones precalculadas por espejos, *spawn Y* dinámico según bounding box rotado.

6. **Código limpio y documentado**  
   Javadoc denso, *package-private* mutators (solo `Engine` escribe `GameState`), `record` para datos inmutables (`Level`, `TextLine`), `enum` para máquina de estados.

7. **Tests unitarios**  
   `EdgesCheck.java`, `MarkerFrameCheck.java` verifican la lógica de bordes y dígitos.

---

## ⚠️ Puntos notables / Detalles de implementación

| Aspecto | Comentario |
|---------|------------|
| **`isFree(row < 0) → true`** | La colisión trata filas negativas como "libres": la pieza nace *fuera* del tablero y entra con la primera caída. Elegante y evita *spawn overlap*. |
| **`CompletedLines` usa filas *relativas* al box de la pieza** | Al borrar una línea, `pieceRow++` mantiene válidas las filas superiores encoladas sin reescribir la cola. Muy listo. |
| **`Sequence<T>` genérica para *todas* las animaciones** | Line blink, cell blink, transition fill, game-over fade → mismo motor de *period + repetitions + step/end actions*. Cero duplicación. |
| **`Palette` con LUT BGRA recalculada *solo* al cambiar brightness** | Pausa (0.55), Game Over (curva exponencial `0.55^n`), normal (1.0) → un `setBrightness` marca `dirty=true` y el próximo `pixel()` reconstruye. Cero alloc por frame. |
| **Dígitos 4×5 bitmap + edge flags precalculados** | `DigitFont` genera sus propios *edge flags* con la misma lógica `Edges` → los marcadores tienen relieve 3D idéntico a las celdas. |
| **Editor edita `src/tetris` directo** | Cambios visibles tras *Rebuild*; no hay base de datos ni binarios intermedios. |
| **Validación estricta al cargar** | Coords fuera de tablero, colores fuera de paleta (1–10), celdas duplicadas, `numPieces > 10`, `linesRequired < 1`, CSV vacío → error con *línea y archivo* exactos. |
| **`minWidth = 15 × 7 = 105`, `minHeight = 20 × 7 = 140`** | 7 px/celda = mínimo para que quepan 4 dígitos en el marcador. El shell hace fallback silencioso si el usuario pasa menos. |

---

## 🏗️ Estructura clave (`src/tetris/`)

```
Tetris.java           # JavaFX App: ventana, canvas, AnimationTimer, teclado, --width/--height
Engine.java           # Motor: gravedad, lock, líneas, niveles, dificultad, scoring
GameState.java        # Estado observable (board, score, pieces, flags, completedLines queue)
Renderer.java         # Composición: board + active piece + panel (preview, score, lines)
CellPainter.java      # Capa de píxeles: WritableImage, Palette, paintCell 3D, fillRect, paintMarker
Palette.java          # 19 índices, 3 variantes (BASE/BRIGHT/DARK), brightness factor, LUT BGRA
Piece.java            # Type(10), Shape, Rotations(4 precalculadas por espejos), ActivePiece
Board.java            # Geometría 10x20, cells/edges/hidden/occupied[], firstOccupiedRow
Edges.java            # Contrato de 4 bits (L/T/B/R), calculateEdges genérico
Levels.java           # Carga/validación levels.csv + levels/level_N.txt → List<Level>
Effects.java          # 4 Sequence<T>: lineBlink, cellBlink, transitionFill, gameOverFade
Clock.java            # FrameTimer (pulsos→ticks 60Hz), Counter (períodos registrados en clock)
Sound.java            # Interfaz (drop, line, block, fill) + implementación JavaFxSound en Tetris
DigitFont.java        # Bitmap 4×5, slot metrics, edge flags de dígitos
EditorLevels.java     # 2ª main: UI editor (tablero + panel colores + botones nivel)
EditorRenderer.java   # Reusa CellPainter para pintar obstáculos en editor
EditorLevelFiles.java # Lee/escribe levels.csv + level_N.txt desde disco
```

---

## 📄 Archivos de configuración de niveles

**`levels.csv`** (una línea por nivel, id = número de línea 1-based):
```csv
linesRequired,difficultyActive,numPieces
5,true,10
10,false,7
10,false,7
...
```

**`levels/level_N.txt`** (obstáculos, uno por línea `row,col,color`):
```text
15,0,7
15,9,7
```
- Fila 0 = arriba, columna 0 = izquierda
- Color 1..10 = índice de paleta de piezas
- Archivo ausente = nivel sin obstáculos (ej. nivel 1)

---

## 🛠️ Compilación y ejecución

```bash
# Compilar (desde la raíz del repo, con JavaFX SDK en module-path)
javac --release 17 \
      --module-path /path/to/javafx-sdk/lib \
      --add-modules javafx.controls,javafx.graphics,javafx.media \
      -d bin \
      src/tetris/*.java

# Ejecutar (Linux/macOS)
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.graphics,javafx.media \
     -cp "bin:src" \
     tetris.Tetris [--width=XXX --height=YYY]

# Ejecutar (Windows PowerShell)
java --module-path "C:\path\to\javafx-sdk-XX\lib" \
     --add-modules javafx.controls,javafx.graphics,javafx.media \
     -cp "bin;src" tetris.Tetris

# Editor de niveles
java ... tetris.EditorLevels
```

- `src` en classpath porque contiene los datos de niveles y sonidos.
- `javafx.media` obligatorio; sin él el juego corre mudo.
- Tamaño mínimo aceptado: **105 × 140 px** (7 px/celda). Menor → fallback a defecto (480 × 640).

---

## 📚 Documentación técnica del proyecto

| Documento | Qué explica |
|-----------|-------------|
| [PIECES-EXPLAINED.md](PIECES-EXPLAINED.md) | `Piece.java`: plantillas, 4 rotaciones, edge flags 3D, `ActivePiece` |
| [GAME-EXPLAINED.md](GAME-EXPLAINED.md) | `Engine.java` + `GameState.java`: motor — máquina de estados, gravedad, borrado de líneas, niveles, game over — + mapa de clases |
| [RENDERER-EXPLAINED.md](RENDERER-EXPLAINED.md) | `Renderer.java` con `CellPainter`/`Palette` y `DigitFont.java`: del estado del juego a los píxeles en el canvas |

Orden sugerido: **pieces → game → renderer**.

---

## 📜 Licencia

MIT — ver [LICENSE](LICENSE).