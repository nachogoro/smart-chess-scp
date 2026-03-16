# remote-lichess

`RemoteAdapterModule` implementation for [Lichess](https://lichess.org) using the Board API.

Supports:
- Random opponent seeks, friend challenges, and AI challenges (Stockfish levels 1-8)
- Conditional parameter visibility (`opponent_name` only for friends, `ai_level` only for AI, `rated` hidden for AI)
- Real-time game streaming via NDJSON event and game streams
- Stale game filtering to prevent connecting to pre-existing games

Requires a Lichess personal API token with `board:play` scope. AI challenges also require `challenge:write`.

Debug logging: set `SCP_DEBUG=true` to see all stream messages and move detection.
