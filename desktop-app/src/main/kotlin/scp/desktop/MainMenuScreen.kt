package scp.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainMenuScreen(
    onLocalGame: () -> Unit,
    onEngineGame: () -> Unit,
    onRemoteGame: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "SCP Chess",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onLocalGame,
            modifier = Modifier.width(280.dp).height(48.dp),
        ) {
            Text("Play Another Player Locally")
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onEngineGame,
            modifier = Modifier.width(280.dp).height(48.dp),
        ) {
            Text("Play an Engine")
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onRemoteGame,
            modifier = Modifier.width(280.dp).height(48.dp),
        ) {
            Text("Play Remotely")
        }

        Spacer(Modifier.height(32.dp))

        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.width(280.dp).height(48.dp),
        ) {
            Text("Settings")
        }
    }
}
