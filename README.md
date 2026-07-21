# Simple Chess Protocol — Kotlin implementation

A Kotlin/JVM implementation of the [Simple Chess Protocol](https://github.com/nachogoro/simple-chess-protocol).
The protocol specification (`PROTOCOL.md`) lives in that separate repository; this repo is one
implementation of it. Keeping the two apart lets the spec version independently of any implementation.

## Modules

| Module | Role |
|---|---|
| `scp-core` | The shared protocol types and module interfaces (`BoardModule`, `EngineAdapterModule`, `UIModule`, the `Result` type, the model). Every other module depends on this. |
| `game-controller` | Game-mode-agnostic rules engine, state tracker and clock manager. Wraps the native rules library via `io.github.nachogoro:simplechess`. |
| `orchestrator` | The central hub: move inference, routing and coordination between modules. |
| `engine-carballo` | Engine adapter for the on-device Carballo engine. |
| `engine-remote-stockfish` | Engine adapter for a remote Stockfish service (chess-api.com), over OkHttp. |
| `remote-lichess` | Remote adapter for online play via the Lichess Board API, over OkHttp. |
| `board-fake` | A file-backed `BoardModule` for development and testing without physical hardware. |

`scp-core` is named to avoid colliding with the specification repository; its Kotlin package is
still `scp.protocol`.

## Consumers

Client applications supply their own `BoardModule` and `UIModule` and reuse the rest unchanged:

- [`simple-chess-desktop`](https://github.com/nachogoro/simple-chess-desktop) — Compose desktop demo.
- [`simple-chess-android`](https://github.com/nachogoro/simple-chess-android) — Android app for the physical board.

Both consume these modules as a Gradle **composite build**, so edits here are picked up without
publishing.

## Building

This repository builds on its own — the native rules library is reached through
`io.github.nachogoro:simplechess`, resolved from Maven Central, so no sibling checkouts are needed:

```sh
git clone git@github.com:nachogoro/simple-chess-scp.git
cd simple-chess-scp
./gradlew build      # compile and test every module
./gradlew test       # tests only
```

Its **consumers**, however, reference it by relative path (`../simple-chess-scp`) as a Gradle
composite build, so when working on one of them this repo must sit beside it under the same parent
directory:

```
<parent>/
├── simple-chess-scp        # this repo
├── simple-chess-desktop    # consumes it
└── simple-chess-android    # consumes it (alongside simple-chess-kotlin)
```
