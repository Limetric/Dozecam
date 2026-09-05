package app.dozecam.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.ui.components.AudioLevelMeter
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape

/**
 * Settings is a hub and four category screens, all inside this one composable:
 * the hub says what the monitor is doing and hands everything else to Cameras,
 * Sound Detection, Alerts, and Display. A search field on the
 * hub finds any preference row and jumps into the screen that owns it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    monitoringRunning: Boolean,
    canMonitor: Boolean,
    localNetworkGranted: Boolean,
    audioLevel: Float,
    readiness: List<ReadinessFinding>,
    onReadinessRemedy: (ReadinessRemedy) -> Unit,
    onTestAlert: () -> Unit,
    cameras: List<Camera>,
    onCameraEnabled: (String, Boolean) -> Unit,
    onEditCamera: (Camera) -> Unit,
    onDeleteCamera: (String) -> Unit,
    form: CameraFormState,
    onFormName: (String) -> Unit,
    onFormUrl: (String) -> Unit,
    onFormSave: () -> Unit,
    onFormCancel: () -> Unit,
    detector: DetectorSettings,
    onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit,
    onOpenOnboarding: () -> Unit,
    onBack: () -> Unit,
    onPickAlertSound: () -> Unit = {},
    onPreviewAlertSound: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A plain string survives process death where a nullable enum would need a
    // custom saver; "" is the hub.
    var route by rememberSaveable { mutableStateOf("") }
    val category = SettingsCategory.entries.firstOrNull { it.name == route }
    var query by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: a jump highlight that replays after a
    // configuration change would flash at nothing the user just did.
    var jumpTarget by remember { mutableStateOf<String?>(null) }

    val goBack = {
        when {
            category != null -> {
                route = ""
                jumpTarget = null
            }
            query.isNotEmpty() -> query = ""
            else -> onBack()
        }
    }
    // Leaving the activity stays the system's job; only inner levels are ours.
    BackHandler(enabled = category != null || query.isNotEmpty()) { goBack() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(category?.titleRes ?: R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = goBack,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("settings-back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        // Fresh scroll for every screen: arriving in a category at the offset
        // the hub was left at would open it half-way down.
        val scrollState = remember(route) { ScrollState(initial = 0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            when (category) {
                null -> SettingsHub(
                    query = query,
                    onQuery = { query = it },
                    entries = settingsSearchEntries(settings, detector),
                    onOpenResult = { entry ->
                        query = ""
                        jumpTarget = entry.id
                        route = entry.category?.name ?: ""
                    },
                    onOpenCategory = { opened ->
                        jumpTarget = null
                        route = opened.name
                    },
                    monitoringRunning = monitoringRunning,
                    canMonitor = canMonitor,
                    localNetworkGranted = localNetworkGranted,
                    audioLevel = audioLevel,
                    threshold = detector.threshold,
                    readiness = readiness,
                    // The one remedy that stays inside settings: the cameras
                    // it is about are two taps away, and sending someone to
                    // Android for them would be absurd.
                    onReadinessRemedy = { remedy ->
                        if (remedy == ReadinessRemedy.CAMERA_SETTINGS) {
                            jumpTarget = null
                            route = SettingsCategory.CAMERAS.name
                        } else {
                            onReadinessRemedy(remedy)
                        }
                    },
                    onTestAlert = onTestAlert,
                    jumpTarget = jumpTarget,
                    onJumpDone = { jumpTarget = null },
                )
                SettingsCategory.CAMERAS -> CamerasSettings(
                    cameras = cameras,
                    onCameraEnabled = onCameraEnabled,
                    onEdit = onEditCamera,
                    onDelete = onDeleteCamera,
                    onOpenOnboarding = onOpenOnboarding,
                    form = form,
                    onFormName = onFormName,
                    onFormUrl = onFormUrl,
                    onFormSave = onFormSave,
                    onFormCancel = onFormCancel,
                )
                SettingsCategory.DETECTION -> DetectionSettings(
                    detector = detector,
                    onDetectorChange = onDetectorChange,
                    jumpTarget = jumpTarget,
                    onJumpDone = { jumpTarget = null },
                )
                SettingsCategory.ALERTS -> AlertsSettings(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onPickAlertSound = onPickAlertSound,
                    onPreviewAlertSound = onPreviewAlertSound,
                    jumpTarget = jumpTarget,
                    onJumpDone = { jumpTarget = null },
                )
                SettingsCategory.DISPLAY -> DisplaySettings(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    jumpTarget = jumpTarget,
                    onJumpDone = { jumpTarget = null },
                )
            }
        }
    }
}

/**
 * The hub: search on top, then what the monitor is doing right now, then the
 * doors to everything tuned once and left alone.
 */
@Composable
private fun SettingsHub(
    query: String,
    onQuery: (String) -> Unit,
    entries: List<SettingSearchEntry>,
    onOpenResult: (SettingSearchEntry) -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    monitoringRunning: Boolean,
    canMonitor: Boolean,
    localNetworkGranted: Boolean,
    audioLevel: Float,
    threshold: Float,
    readiness: List<ReadinessFinding>,
    onReadinessRemedy: (ReadinessRemedy) -> Unit,
    onTestAlert: () -> Unit,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
) {
    SettingsSearchField(query = query, onQuery = onQuery)
    if (query.isNotBlank()) {
        SearchResults(
            results = searchSettings(query, entries),
            onOpen = onOpenResult,
        )
    } else {
        MonitoringSection(
            running = monitoringRunning,
            canMonitor = canMonitor,
            localNetworkGranted = localNetworkGranted,
            audioLevel = audioLevel,
            threshold = threshold,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
        )
        // Above the doors to everything tuned once and left alone, because it
        // is the opposite kind of thing: the one part of settings worth reading
        // again on a night when something has quietly changed.
        ReadinessSection(
            findings = readiness,
            onRemedy = onReadinessRemedy,
            onTestAlert = onTestAlert,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
        )
        Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val categories = SettingsCategory.entries
            categories.forEachIndexed { index, entry ->
                GroupRow(
                    headline = stringResource(entry.titleRes),
                    supporting = stringResource(entry.summaryRes),
                    shape = groupShape(index, categories.size),
                    leading = {
                        Icon(
                            painter = painterResource(entry.iconRes),
                            contentDescription = null,
                        )
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    onClick = { onOpenCategory(entry) },
                    modifier = Modifier.testTag("category-${entry.name}"),
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("settings-search"),
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = { onQuery("") },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.testTag("settings-search-clear"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.settings_search_clear),
                    )
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun SearchResults(
    results: List<SettingSearchEntry>,
    onOpen: (SettingSearchEntry) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("search-empty"),
            )
        }
        results.forEachIndexed { index, entry ->
            GroupRow(
                headline = entry.label,
                // The category names where the jump will land; a hub row has
                // nowhere else to point, so its description carries the context.
                supporting = entry.category?.let { stringResource(it.titleRes) }
                    ?: entry.description,
                shape = groupShape(index, results.size),
                onClick = { onOpen(entry) },
                modifier = Modifier.testTag("search-result-${entry.id}"),
            )
        }
    }
}

/**
 * What the monitor is doing, and the level meter that makes the detection
 * threshold settable. Status only: monitoring runs for as long as the app
 * does, and the way to end it — exiting — lives on the viewer, so nothing here
 * can quietly switch the night off.
 */
@Composable
private fun MonitoringSection(
    running: Boolean,
    canMonitor: Boolean,
    localNetworkGranted: Boolean,
    audioLevel: Float,
    threshold: Float,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
) {
    Section(title = stringResource(R.string.section_monitoring)) {
        JumpTarget(
            id = SettingIds.MONITORING,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupRow(
                    headline = stringResource(R.string.monitoring_toggle),
                    supporting = stringResource(
                        when {
                            running -> R.string.monitoring_always_on
                            !canMonitor -> R.string.monitoring_unavailable
                            // Named rather than reported as idleness, because
                            // arming refuses without it and nothing else on
                            // this row would say why.
                            !localNetworkGranted -> R.string.monitoring_needs_local_network
                            else -> R.string.monitoring_idle
                        },
                    ),
                    containerColor = if (running) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_graphic_eq),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.testTag("monitoring-status"),
                )
                AnimatedVisibility(visible = running) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.audio_level_label),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        AudioLevelMeter(
                            level = audioLevel,
                            threshold = threshold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
