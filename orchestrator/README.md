# orchestrator

Central hub that coordinates all modules. Drives game setup, move flow, and termination.

Handles:
- Game setup flows (local, engine, remote) with UI-driven configuration and error retry
- Move inference from the board module
- Engine and remote opponent move routing
- Draw/resign/abort flows
- Remote game seeking and lifecycle

The orchestrator communicates exclusively through protocol interfaces — it has no knowledge of any specific module implementation.
