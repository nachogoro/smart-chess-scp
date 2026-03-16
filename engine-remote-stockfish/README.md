# engine-remote-stockfish

`EngineAdapterModule` implementation that wraps [chess-api.com](https://chess-api.com), a free remote Stockfish API. Requires internet access.

Parameters: `Depth` (1-18), `MaxThinkingTime` (1-100ms).

No local engine binary needed — all computation happens server-side.
