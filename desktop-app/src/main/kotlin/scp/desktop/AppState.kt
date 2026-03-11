package scp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen {
    MAIN_MENU,
    SETTINGS,
    GAME_SETUP,
    GAME,
}

class AppState {
    var screen by mutableStateOf(Screen.MAIN_MENU)
    var gameSetupMode by mutableStateOf<scp.protocol.GameMode?>(null)
}
