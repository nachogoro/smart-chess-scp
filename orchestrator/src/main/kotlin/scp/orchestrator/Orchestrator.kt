package scp.orchestrator

import scp.protocol.*

class Orchestrator(
    private val board: BoardModule,
    private val gameController: GameControllerModule,
    private val ui: UIModule,
    private val engine: EngineAdapterModule? = null,
    private val remote: RemoteAdapterModule? = null,
) {
    suspend fun start() {
        TODO("Not yet implemented")
    }
}
