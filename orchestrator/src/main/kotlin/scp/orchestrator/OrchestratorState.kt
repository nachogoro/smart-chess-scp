package scp.orchestrator

import scp.protocol.Color

sealed class OrchestratorState {
    data object Idle : OrchestratorState()
    data object SeekingGame : OrchestratorState()
    data class AwaitingPlayerMove(val color: Color) : OrchestratorState()
    data class AwaitingOpponentMove(val color: Color) : OrchestratorState()
}
