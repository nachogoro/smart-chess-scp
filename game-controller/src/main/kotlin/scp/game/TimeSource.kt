package scp.game

interface TimeSource {
    fun currentTimeMs(): Long
}

class SystemTimeSource : TimeSource {
    override fun currentTimeMs(): Long = System.currentTimeMillis()
}
