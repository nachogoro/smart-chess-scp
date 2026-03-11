package scp.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import scp.board.fake.FakeBoard
import scp.game.GameController
import scp.orchestrator.Orchestrator
import scp.protocol.GameMode
import scp.remote.lichess.LichessAdapter
import scp.engine.remote.RemoteStockfishAdapter
import java.nio.file.Path

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1100.dp, 750.dp))

    Window(
        onCloseRequest = ::exitApplication,
        title = "SCP Chess",
        state = windowState,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val appState = remember { AppState() }
    var settings by remember { mutableStateOf(AppSettings.load()) }

    val boardFilePath = remember {
        Path.of(System.getProperty("user.home"), ".scp-chess", "board-state.txt")
    }
    val boardFileManager = remember { BoardFileManager(boardFilePath) }

    val appScope = rememberCoroutineScope()

    // Create modules
    val uiModule = remember { ComposeUIModule() }
    val board = remember { FakeBoard(boardFilePath) }
    val gameControllerScope = remember { CoroutineScope(Dispatchers.Default) }
    val gameController = remember { GameController(gameControllerScope) }
    val lichessAdapter = remember { LichessAdapter(gameControllerScope) }
    val stockfishAdapter = remember { RemoteStockfishAdapter() }

    // Track whether orchestrator is running
    var orchestratorInstance by remember { mutableStateOf<Orchestrator?>(null) }

    fun startOrchestrator(mode: GameMode) {
        // Write an initial board file so FakeBoard.getCapabilities() succeeds during setup
        boardFileManager.write(startingPosition(), settings.boardLevel)

        val orchestrator = Orchestrator(
            board = board,
            gameController = gameController,
            ui = uiModule,
            engines = listOf(stockfishAdapter),
            remotes = listOf(lichessAdapter),
        )
        orchestratorInstance = orchestrator

        appScope.launch {
            launch { orchestrator.start() }
            uiModule.requestNewGame()
        }

        appState.gameSetupMode = mode
        appState.screen = Screen.GAME_SETUP
    }

    when (appState.screen) {
        Screen.MAIN_MENU -> MainMenuScreen(
            onLocalGame = { startOrchestrator(GameMode.LOCAL) },
            onEngineGame = { startOrchestrator(GameMode.ENGINE) },
            onRemoteGame = { startOrchestrator(GameMode.REMOTE) },
            onSettings = { appState.screen = Screen.SETTINGS },
        )

        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onSave = { newSettings ->
                settings = newSettings
                newSettings.save()
                appState.screen = Screen.MAIN_MENU
            },
            onBack = { appState.screen = Screen.MAIN_MENU },
        )

        Screen.GAME_SETUP -> {
            val orchestrator = orchestratorInstance
            if (orchestrator != null) {
                GameSetupScreen(
                    mode = appState.gameSetupMode ?: GameMode.LOCAL,
                    settings = settings,
                    uiModule = uiModule,
                    onBack = {
                        orchestrator.stop()
                        orchestratorInstance = null
                        appState.screen = Screen.MAIN_MENU
                    },
                )

                // Poll for game session creation (Orchestrator state isn't Compose-observable)
                LaunchedEffect(Unit) {
                    while (true) {
                        if (orchestrator.session != null) {
                            appState.screen = Screen.GAME
                            break
                        }
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
        }

        Screen.GAME -> {
            val orchestrator = orchestratorInstance
            if (orchestrator != null) {
                GameScreen(
                    orchestrator = orchestrator,
                    uiModule = uiModule,
                    boardFileManager = boardFileManager,
                    boardLevel = settings.boardLevel,
                    onGameEnd = {
                        orchestrator.stop()
                        orchestratorInstance = null
                        appState.screen = Screen.MAIN_MENU
                    },
                )
            }
        }
    }
}
