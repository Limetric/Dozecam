package app.dozecam.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dozecam.R

@Composable
fun HomeRoute(viewModel: HomeViewModel, onWatch: (String) -> Unit) {
    val url by viewModel.urlInput.collectAsStateWithLifecycle()
    val canWatch by viewModel.canWatch.collectAsStateWithLifecycle()
    HomeScreen(
        url = url,
        canWatch = canWatch,
        onUrlChange = viewModel::onUrlChange,
        onWatch = { onWatch(viewModel.commitUrl()) },
    )
}

@Composable
fun HomeScreen(
    url: String,
    canWatch: Boolean,
    onUrlChange: (String) -> Unit,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stream_url_label)) },
                placeholder = { Text(stringResource(R.string.stream_url_hint)) },
                singleLine = true,
            )
            Button(onClick = onWatch, enabled = canWatch) {
                Text(stringResource(R.string.watch))
            }
        }
    }
}
