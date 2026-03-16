# game-controller

`GameControllerModule` implementation. Tracks game state, validates moves, manages clocks, and detects checkmate/stalemate/draw conditions.

Game-mode-agnostic — it doesn't know whether moves come from a human, engine, or remote opponent.

Supports two authority modes:
- `local` (default): the controller is the termination authority.
- `external`: an external source (e.g. a remote server) decides termination via `setResult`.
