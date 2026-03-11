package scp.desktop

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.*

data class AppSettings(
    val playerName: String = "",
    val lichessToken: String = "",
    val boardLevel: String = "full",
) {
    companion object {
        private val settingsPath: Path = Path.of(
            System.getProperty("user.home"), ".scp-chess", "settings.properties"
        )

        fun load(): AppSettings {
            if (!settingsPath.exists()) return AppSettings()
            val props = Properties()
            settingsPath.inputStream().use { props.load(it) }
            return AppSettings(
                playerName = props.getProperty("playerName", ""),
                lichessToken = props.getProperty("lichessToken", ""),
                boardLevel = props.getProperty("boardLevel", "full"),
            )
        }
    }

    fun save() {
        settingsPath.parent.createDirectories()
        val props = Properties()
        props.setProperty("playerName", playerName)
        props.setProperty("lichessToken", lichessToken)
        props.setProperty("boardLevel", boardLevel)
        settingsPath.outputStream().use { props.store(it, "SCP Chess Settings") }
    }
}
