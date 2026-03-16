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
import scp.engine.carballo.CarballoAdapter
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

    // Create modules — these live for the entire app lifetime
    val uiModule = remember { ComposeUIModule() }
    val board = remember { FakeBoard(boardFilePath) }
    val moduleScope = remember { CoroutineScope(Dispatchers.Default) }
    val gameController = remember { GameController(moduleScope) }
    val lichessAdapter = remember { LichessAdapter(moduleScope) }
    val stockfishAdapter = remember { RemoteStockfishAdapter() }
    val carballoAdapter = remember { CarballoAdapter() }

    // Create and start the orchestrator once
    val orchestrator = remember {
        Orchestrator(
            board = board,
            gameController = gameController,
            ui = uiModule,
            engines = listOf(carballoAdapter, stockfishAdapter),
            remotes = listOf(lichessAdapter),
        )
    }
    LaunchedEffect(Unit) {
        orchestrator.start()
    }

    fun startGame(mode: GameMode) {
        // Write an initial board file so FakeBoard.getCapabilities() succeeds during setup
        boardFileManager.write(startingPosition(), settings.boardLevel)

        appScope.launch { uiModule.requestNewGame() }
        appState.gameSetupMode = mode
        appState.screen = Screen.GAME_SETUP
    }

    when (appState.screen) {
        Screen.MAIN_MENU -> MainMenuScreen(
            onLocalGame = { startGame(GameMode.LOCAL) },
            onEngineGame = { startGame(GameMode.ENGINE) },
            onRemoteGame = { startGame(GameMode.REMOTE) },
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
            GameSetupScreen(
                mode = appState.gameSetupMode ?: GameMode.LOCAL,
                settings = settings,
                uiModule = uiModule,
                onBack = {
                    uiModule.cancelAllPendingRequests()
                    appState.screen = Screen.MAIN_MENU
                },
            )

            // React to game start notification
            if (uiModule.lastGameStartedInfo != null) {
                uiModule.clearGameStartedInfo()
                appState.screen = Screen.GAME
            }
        }

        Screen.GAME -> {
            GameScreen(
                orchestrator = orchestrator,
                uiModule = uiModule,
                boardFileManager = boardFileManager,
                boardLevel = settings.boardLevel,
                onGameEnd = { appState.screen = Screen.MAIN_MENU },
            )
        }
    }
}
