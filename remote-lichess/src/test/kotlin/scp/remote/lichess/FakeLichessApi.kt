package scp.remote.lichess

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

class FakeLichessApi : LichessApi {

    var account: LichessAccount = LichessAccount(
        id = "testuser",
        username = "TestUser",
        perfs = mapOf("rapid" to LichessPerf(games = 10, rating = 1500)),
    )
    var authShouldFail = false
    var lastSeekParams: SeekParams? = null
    var lastMoveSent: Pair<String, String>? = null // gameId to move
    var lastResignedGame: String? = null
    var lastDrawAction: Pair<String, Boolean>? = null // gameId to accept
    var lastChallengeParams: ChallengeParams? = null

    val eventStreamChannel = Channel<String>(Channel.UNLIMITED)
    val gameStreamChannel = Channel<String>(Channel.UNLIMITED)
    val seekChannel = Channel<String>(Channel.UNLIMITED)
    val challengeChannel = Channel<String>(Channel.UNLIMITED)

    data class SeekParams(val time: Int, val increment: Int, val rated: Boolean, val color: String)
    data class ChallengeParams(val username: String, val time: Int, val increment: Int, val rated: Boolean, val color: String)

    override suspend fun getAccount(token: String): LichessAccount {
        if (authShouldFail) throw LichessApiException(401, "Unauthorized")
        return account
    }

    override fun streamEvents(token: String): Flow<String> =
        eventStreamChannel.consumeAsFlow()

    override fun streamGame(token: String, gameId: String): Flow<String> =
        gameStreamChannel.consumeAsFlow()

    override suspend fun createSeek(
        token: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String> {
        lastSeekParams = SeekParams(time, increment, rated, color)
        return seekChannel.consumeAsFlow()
    }

    override suspend fun createChallenge(
        token: String,
        username: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String> {
        lastChallengeParams = ChallengeParams(username, time, increment, rated, color)
        return challengeChannel.consumeAsFlow()
    }

    override suspend fun makeMove(token: String, gameId: String, move: String) {
        lastMoveSent = gameId to move
    }

    override suspend fun resign(token: String, gameId: String) {
        lastResignedGame = gameId
    }

    override suspend fun offerDraw(token: String, gameId: String) {
        lastDrawAction = gameId to true
    }

    override suspend fun respondDraw(token: String, gameId: String, accept: Boolean) {
        lastDrawAction = gameId to accept
    }

    override suspend fun abort(token: String, gameId: String) {}
}
