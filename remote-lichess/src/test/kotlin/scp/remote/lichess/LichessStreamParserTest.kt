package scp.remote.lichess

import kotlin.test.*

class LichessStreamParserTest {

    @Test
    fun `parse gameFull event`() {
        // A real payload, ratings and all. Lichess sends them; this system has no use for one, so
        // the models do not ask and ignoreUnknownKeys drops them. Keeping them in the fixture is
        // the point - it is what shows a field we do not want cannot break the parse.
        val line = """{"type":"gameFull","id":"abc123","rated":true,"variant":{"key":"standard","name":"Standard"},"speed":"rapid","white":{"id":"alice","name":"Alice","rating":1500},"black":{"id":"bob","name":"Bob","rating":1400},"initialFen":"startpos","state":{"type":"gameState","moves":"","wtime":900000,"btime":900000,"winc":10000,"binc":10000,"status":"started"},"clock":{"initial":900000,"increment":10000}}"""

        val event = parseGameStreamLine(line)
        assertIs<GameStreamEvent.Full>(event)
        assertEquals("abc123", event.data.id)
        assertEquals("Alice", event.data.white.name)
        assertEquals("Bob", event.data.black.name)
        assertEquals(900000, event.data.clock?.initial)
        assertEquals("started", event.data.state.status)
    }

    @Test
    fun `parse gameState event`() {
        val line = """{"type":"gameState","moves":"e2e4 e7e5","wtime":895000,"btime":890000,"winc":10000,"binc":10000,"status":"started"}"""

        val event = parseGameStreamLine(line)
        assertIs<GameStreamEvent.State>(event)
        assertEquals("e2e4 e7e5", event.data.moves)
        assertEquals(895000, event.data.wtime)
        assertEquals(890000, event.data.btime)
    }

    @Test
    fun `parse gameState with draw offer`() {
        val line = """{"type":"gameState","moves":"e2e4","wtime":900000,"btime":900000,"winc":0,"binc":0,"status":"started","bdraw":true}"""

        val event = parseGameStreamLine(line)
        assertIs<GameStreamEvent.State>(event)
        assertTrue(event.data.bdraw)
        assertFalse(event.data.wdraw)
    }

    @Test
    fun `parse gameState with terminal status`() {
        val line = """{"type":"gameState","moves":"e2e4 e7e5 d1h5 b8c6 f1c4 g8f6 h5f7","wtime":880000,"btime":870000,"winc":10000,"binc":10000,"status":"mate","winner":"white"}"""

        val event = parseGameStreamLine(line)
        assertIs<GameStreamEvent.State>(event)
        assertEquals("mate", event.data.status)
        assertEquals("white", event.data.winner)
    }

    @Test
    fun `parse unknown event type returns null`() {
        val line = """{"type":"chatLine","username":"alice","text":"hello","room":"player"}"""

        val event = parseGameStreamLine(line)
        assertNull(event)
    }

    @Test
    fun `parse event stream gameStart`() {
        val line = """{"type":"gameStart","game":{"fullId":"abc123full","gameId":"abc123","fen":"startpos","color":"white","speed":"rapid","perf":"rapid","rated":true,"opponent":{"id":"bob","username":"Bob","rating":1400},"isMyTurn":true}}"""

        val event = parseEventStreamLine(line)
        assertNotNull(event)
        assertEquals("gameStart", event.type)
        assertNotNull(event.game)
        assertEquals("abc123", event.game!!.gameId)
    }

    @Test
    fun `parse event stream gameFinish`() {
        val line = """{"type":"gameFinish","game":{"fullId":"abc123full","gameId":"abc123","fen":"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1","color":"white","speed":"rapid","perf":"rapid","rated":true,"opponent":{"id":"bob","username":"Bob","rating":1400},"isMyTurn":false}}"""

        val event = parseEventStreamLine(line)
        assertNotNull(event)
        assertEquals("gameFinish", event.type)
    }

    @Test
    fun `parse malformed line returns null`() {
        val event = parseEventStreamLine("not json at all")
        assertNull(event)
    }
}
