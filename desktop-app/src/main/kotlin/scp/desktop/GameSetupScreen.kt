package scp.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import scp.protocol.*

@Composable
fun GameSetupScreen(
    mode: GameMode,
    settings: AppSettings,
    uiModule: ComposeUIModule,
    onBack: () -> Unit,
) {
    // Auto-complete game mode selection — the user already chose from the main menu
    val pendingGameMode = uiModule.pendingGameModeSelection
    LaunchedEffect(pendingGameMode) {
        if (pendingGameMode != null) {
            pendingGameMode.complete(mode)
        }
    }

    // Observe pending requests from the UIModule
    val pendingLocalConfig = uiModule.pendingLocalGameConfig
    val pendingEngineConfig = uiModule.pendingEngineGameConfig
    val pendingEngineSelection = uiModule.pendingEngineSelection
    val pendingRemotePlatformSelection = uiModule.pendingRemotePlatformSelection
    val pendingRemoteConfig = uiModule.pendingRemoteGameConfig

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val title = when (mode) {
            GameMode.LOCAL -> "Local Game Setup"
            GameMode.ENGINE -> "Engine Game Setup"
            GameMode.REMOTE -> "Remote Game Setup"
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        when {
            pendingLocalConfig != null -> LocalGameSetupForm(settings, pendingLocalConfig, onBack)
            pendingEngineSelection != null -> EngineSelectionForm(pendingEngineSelection, onBack)
            pendingEngineConfig != null -> EngineGameSetupForm(settings, pendingEngineConfig, onBack)
            pendingRemotePlatformSelection != null -> PlatformSelectionForm(pendingRemotePlatformSelection, onBack)
            pendingRemoteConfig != null -> RemoteGameSetupForm(settings, pendingRemoteConfig, onBack)
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Waiting for orchestrator...")
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun LocalGameSetupForm(
    settings: AppSettings,
    request: PendingRequest<Unit, LocalGameConfig>,
    onBack: () -> Unit,
) {
    var whiteName by remember { mutableStateOf(settings.playerName.ifEmpty { "White" }) }
    var blackName by remember { mutableStateOf("Black") }
    var useTimeControl by remember { mutableStateOf(false) }
    var timeMinutes by remember { mutableStateOf("10") }
    var incrementSeconds by remember { mutableStateOf("0") }

    OutlinedTextField(
        value = whiteName,
        onValueChange = { whiteName = it },
        label = { Text("White Player Name") },
        modifier = Modifier.width(300.dp),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = blackName,
        onValueChange = { blackName = it },
        label = { Text("Black Player Name") },
        modifier = Modifier.width(300.dp),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))

    TimeControlSection(
        useTimeControl = useTimeControl,
        onUseTimeControlChange = { useTimeControl = it },
        timeMinutes = timeMinutes,
        onTimeMinutesChange = { timeMinutes = it },
        incrementSeconds = incrementSeconds,
        onIncrementSecondsChange = { incrementSeconds = it },
    )

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = { request.cancel(); onBack() }) { Text("Cancel") }
        Button(onClick = {
            val tc = if (useTimeControl) {
                val mins = timeMinutes.toLongOrNull() ?: 10
                val inc = incrementSeconds.toLongOrNull() ?: 0
                TimeControl(type = TimeControlType.FISCHER, initialMs = mins * 60_000, incrementMs = inc * 1000)
            } else null
            request.complete(LocalGameConfig(whiteName.ifBlank { null }, blackName.ifBlank { null }, tc))
        }) { Text("Start Game") }
    }
}

@Composable
private fun EngineSelectionForm(
    request: PendingRequest<List<EngineInfo>, String>,
    onBack: () -> Unit,
) {
    Text("Select Engine", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    if (request.input.isEmpty()) {
        Text("No engines available.")
    } else {
        request.input.forEach { engine ->
            OutlinedButton(
                onClick = { request.complete(engine.name) },
                modifier = Modifier.width(300.dp).padding(vertical = 4.dp),
            ) {
                Text("${engine.name} by ${engine.author}")
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = { request.cancel(); onBack() }) { Text("Cancel") }
}

@Composable
private fun EngineGameSetupForm(
    settings: AppSettings,
    request: PendingRequest<ConfigureEngineGameParams, EngineGameConfig>,
    onBack: () -> Unit,
) {
    var playerColor by remember { mutableStateOf(Color.WHITE) }
    var useTimeControl by remember { mutableStateOf(false) }
    var timeMinutes by remember { mutableStateOf("10") }
    var incrementSeconds by remember { mutableStateOf("0") }

    Text("Playing against: ${request.input.engineName}", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    Text("Play as:", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = playerColor == Color.WHITE,
            onClick = { playerColor = Color.WHITE },
            label = { Text("White") },
        )
        FilterChip(
            selected = playerColor == Color.BLACK,
            onClick = { playerColor = Color.BLACK },
            label = { Text("Black") },
        )
    }

    Spacer(Modifier.height(8.dp))
    TimeControlSection(
        useTimeControl = useTimeControl,
        onUseTimeControlChange = { useTimeControl = it },
        timeMinutes = timeMinutes,
        onTimeMinutesChange = { timeMinutes = it },
        incrementSeconds = incrementSeconds,
        onIncrementSecondsChange = { incrementSeconds = it },
    )

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = { request.cancel(); onBack() }) { Text("Cancel") }
        Button(onClick = {
            val tc = if (useTimeControl) {
                val mins = timeMinutes.toLongOrNull() ?: 10
                val inc = incrementSeconds.toLongOrNull() ?: 0
                TimeControl(type = TimeControlType.FISCHER, initialMs = mins * 60_000, incrementMs = inc * 1000)
            } else null
            request.complete(EngineGameConfig(
                playerColor = playerColor,
                engineSettings = emptyMap(),
                timeControl = tc,
            ))
        }) { Text("Start Game") }
    }
}

@Composable
private fun PlatformSelectionForm(
    request: PendingRequest<List<PlatformInfo>, String>,
    onBack: () -> Unit,
) {
    Text("Select Platform", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    if (request.input.isEmpty()) {
        Text("No remote platforms available.")
    } else {
        request.input.forEach { platform ->
            OutlinedButton(
                onClick = { request.complete(platform.platform) },
                modifier = Modifier.width(300.dp).padding(vertical = 4.dp),
            ) {
                Text(platform.platform)
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = { request.cancel(); onBack() }) { Text("Cancel") }
}

@Composable
private fun RemoteGameSetupForm(
    settings: AppSettings,
    request: PendingRequest<ConfigureRemoteGameParams, RemoteGameConfig>,
    onBack: () -> Unit,
) {
    val params = request.input

    // Build platform settings from parameter descriptors, pre-filling from AppSettings
    val platformFields = remember(params) {
        params.parameters.associate { p ->
            val default = when (p.name) {
                "token" -> settings.lichessToken
                else -> (p.default ?: "").toString()
            }
            p.name to mutableStateOf(default)
        }
    }

    val seekFields = remember(params) {
        params.seekParameters.associate { p ->
            val default = (p.default ?: "").toString()
            p.name to mutableStateOf(default)
        }
    }

    Text("Platform: ${params.platform}", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    if (platformFields.isNotEmpty()) {
        Text("Connection Settings", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        platformFields.forEach { (name, state) ->
            OutlinedTextField(
                value = state.value,
                onValueChange = { state.value = it },
                label = { Text(name) },
                modifier = Modifier.width(300.dp),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(12.dp))
    }

    if (seekFields.isNotEmpty()) {
        Text("Game Settings", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        seekFields.forEach { (name, state) ->
            OutlinedTextField(
                value = state.value,
                onValueChange = { state.value = it },
                label = { Text(name) },
                modifier = Modifier.width(300.dp),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = { request.cancel(); onBack() }) { Text("Cancel") }
        Button(onClick = {
            val platformSettings = platformFields.mapValues { (_, state) -> state.value as Any }
            val seekSettings = seekFields.mapValues { (_, state) -> state.value as Any }
            request.complete(RemoteGameConfig(platformSettings, seekSettings))
        }) { Text("Find Game") }
    }
}

@Composable
private fun TimeControlSection(
    useTimeControl: Boolean,
    onUseTimeControlChange: (Boolean) -> Unit,
    timeMinutes: String,
    onTimeMinutesChange: (String) -> Unit,
    incrementSeconds: String,
    onIncrementSecondsChange: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = useTimeControl, onCheckedChange = onUseTimeControlChange)
        Text("Time Control")
    }
    if (useTimeControl) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = timeMinutes,
                onValueChange = onTimeMinutesChange,
                label = { Text("Minutes") },
                modifier = Modifier.width(100.dp),
                singleLine = true,
            )
            Text("+")
            OutlinedTextField(
                value = incrementSeconds,
                onValueChange = onIncrementSecondsChange,
                label = { Text("Increment (s)") },
                modifier = Modifier.width(120.dp),
                singleLine = true,
            )
        }
    }
}
