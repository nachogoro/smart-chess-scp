package scp.orchestrator

import scp.protocol.*

sealed class OrchestratorEvent {
    // UI events
    data object NewGameRequested : OrchestratorEvent()
    data class MoveReady(val gameId: String) : OrchestratorEvent()
    data class ResignRequested(val gameId: String) : OrchestratorEvent()
    data class DrawRequested(val gameId: String) : OrchestratorEvent()
    data class AbortRequested(val gameId: String) : OrchestratorEvent()

    // Game Controller events
    data class GameEnded(val event: GameEndedEvent) : OrchestratorEvent()
    data class FlagFell(val event: FlagFallEvent) : OrchestratorEvent()

    // Remote events
    data class RemoteGameStarted(val event: GameStartedEvent) : OrchestratorEvent()
    data class RemoteOpponentMove(val event: OpponentMoveEvent) : OrchestratorEvent()
    data class RemoteGameEnded(val event: RemoteGameEndedEvent) : OrchestratorEvent()
    data object RemoteDrawOffered : OrchestratorEvent()

    // Error events
    data class BoardError(val error: scp.protocol.BoardError) : OrchestratorEvent()
    data class EngineError(val error: scp.protocol.EngineError) : OrchestratorEvent()
    data class RemoteError(val error: scp.protocol.RemoteError) : OrchestratorEvent()
}
