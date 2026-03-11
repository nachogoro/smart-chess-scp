package scp.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var playerName by remember(settings) { mutableStateOf(settings.playerName) }
    var lichessToken by remember(settings) { mutableStateOf(settings.lichessToken) }
    var boardLevel by remember(settings) { mutableStateOf(settings.boardLevel) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it },
            label = { Text("Player Name") },
            modifier = Modifier.width(400.dp),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = lichessToken,
            onValueChange = { lichessToken = it },
            label = { Text("Lichess API Token") },
            modifier = Modifier.width(400.dp),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        Text("Board Capability Level", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("full", "color", "occupancy").forEach { level ->
                FilterChip(
                    selected = boardLevel == level,
                    onClick = { boardLevel = level },
                    label = { Text(level.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(onClick = {
                onSave(AppSettings(playerName, lichessToken, boardLevel))
            }) {
                Text("Save")
            }
        }
    }
}
