# desktop-app

Compose Desktop application for testing SCP module implementations. Provides a GUI with:

- Main menu: local PvP, engine, or remote game modes
- Settings: player name, Lichess token, board capability level
- Dynamic setup forms generated from module parameter descriptors (with conditional visibility)
- Chess board display with piece rendering
- Game status, clocks, and move history

Run:
```sh
./gradlew :desktop-app:run
```

Debug logging:
```sh
SCP_DEBUG=true ./gradlew :desktop-app:run
```
