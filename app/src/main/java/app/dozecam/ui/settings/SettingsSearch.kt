package app.dozecam.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.DetectorSettings
import kotlin.math.roundToInt

/** The four sub-screens the hub opens; the monitoring switch itself stays on the hub. */
enum class SettingsCategory(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    @DrawableRes val iconRes: Int,
) {
    CAMERAS(
        R.string.section_cameras,
        R.string.category_cameras_summary,
        R.drawable.ic_videocam,
    ),
    DETECTION(
        R.string.section_detection,
        R.string.category_detection_summary,
        R.drawable.ic_graphic_eq,
    ),
    ALERTS(
        R.string.section_alerts,
        R.string.category_alerts_summary,
        R.drawable.ic_alarm,
    ),
    DISPLAY(
        R.string.section_display,
        R.string.category_display_summary,
        R.drawable.ic_smartphone,
    ),
}

/** Stable ids tying a search result to the row it jumps to. */
object SettingIds {
    const val MONITORING = "monitoring"
    const val READINESS = "readiness"
    const val THRESHOLD = "threshold"
    const val SUSTAIN = "sustain"
    const val QUIET = "quiet"
    const val ALERTS = "alerts"
    const val CHIME = "chime"
    const val VIBRATE = "vibrate"
    const val ALERT_SOUND = "alert-sound"
    const val ALERT_RAMP = "alert-ramp"
    const val ALERT_VOLUME = "alert-volume"
    const val ALERT_REPEAT = "alert-repeat"
    const val FAILURE_GRACE = "failure-grace"
    const val NIGHT_THEME = "night-theme"
    const val KEEP_SCREEN = "keep-screen"
    const val ORIENTATION = "orientation"
    const val TALKBACK_VOLUME = "talkback-volume"
}

data class SettingSearchEntry(
    val id: String,
    /** null: the row lives on the hub itself rather than behind a category. */
    val category: SettingsCategory?,
    val label: String,
    val description: String? = null,
)

/**
 * Case-insensitive substring match over what the rows themselves render. Label
 * hits come before description-only hits so the obvious answer is on top.
 */
fun searchSettings(query: String, entries: List<SettingSearchEntry>): List<SettingSearchEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val (byLabel, rest) = entries.partition { it.label.contains(trimmed, ignoreCase = true) }
    return byLabel + rest.filter { it.description?.contains(trimmed, ignoreCase = true) == true }
}

/**
 * The searchable settings, labelled with the same strings — current values
 * included — that their rows render, so search and screen cannot drift apart.
 * Preference rows only, on purpose: cameras are content, not settings.
 */
@Composable
fun settingsSearchEntries(
    settings: AppSettings,
    detector: DetectorSettings,
): List<SettingSearchEntry> = listOf(
    SettingSearchEntry(
        id = SettingIds.MONITORING,
        category = null,
        label = stringResource(R.string.monitoring_toggle),
        description = stringResource(R.string.section_monitoring),
    ),
    SettingSearchEntry(
        id = SettingIds.READINESS,
        category = null,
        label = stringResource(R.string.section_readiness),
        description = stringResource(R.string.readiness_test),
    ),
    SettingSearchEntry(
        id = SettingIds.THRESHOLD,
        category = SettingsCategory.DETECTION,
        label = stringResource(
            R.string.detector_threshold_label,
            (detector.threshold * 100).roundToInt(),
        ),
    ),
    SettingSearchEntry(
        id = SettingIds.SUSTAIN,
        category = SettingsCategory.DETECTION,
        label = stringResource(R.string.detector_sustain_label, detector.sustainMs / 1000f),
    ),
    SettingSearchEntry(
        id = SettingIds.QUIET,
        category = SettingsCategory.DETECTION,
        label = stringResource(R.string.detector_quiet_label, (detector.quietMs / 1000).toInt()),
    ),
    SettingSearchEntry(
        id = SettingIds.ALERTS,
        category = SettingsCategory.ALERTS,
        label = stringResource(R.string.setting_alerts),
        description = stringResource(R.string.setting_alerts_description),
    ),
    SettingSearchEntry(
        id = SettingIds.CHIME,
        category = SettingsCategory.ALERTS,
        label = stringResource(R.string.setting_alert_chime),
        description = stringResource(R.string.setting_alert_chime_description),
    ),
    SettingSearchEntry(
        id = SettingIds.VIBRATE,
        category = SettingsCategory.ALERTS,
        label = stringResource(R.string.setting_alert_vibrate),
        description = stringResource(R.string.setting_alert_vibrate_description),
    ),
    SettingSearchEntry(
        id = SettingIds.ALERT_SOUND,
        category = SettingsCategory.ALERTS,
        label = stringResource(R.string.setting_alert_sound),
        description = alertSoundTitle(settings),
    ),
    SettingSearchEntry(
        id = SettingIds.ALERT_RAMP,
        category = SettingsCategory.ALERTS,
        label = stringResource(R.string.setting_alert_ramp),
        description = stringResource(R.string.setting_alert_ramp_description),
    ),
    SettingSearchEntry(
        id = SettingIds.ALERT_VOLUME,
        category = SettingsCategory.ALERTS,
        label = stringResource(
            R.string.alert_volume_label,
            (settings.alertVolume * 100).roundToInt(),
        ),
    ),
    SettingSearchEntry(
        id = SettingIds.ALERT_REPEAT,
        category = SettingsCategory.ALERTS,
        label = stringResource(
            R.string.alert_repeat_label,
            (settings.alertRepeatIntervalMs / 1000).toInt(),
        ),
    ),
    SettingSearchEntry(
        id = SettingIds.FAILURE_GRACE,
        category = SettingsCategory.ALERTS,
        label = stringResource(
            R.string.setting_failure_grace_label,
            (settings.failureGraceMs / 1000).toInt(),
        ),
        description = stringResource(R.string.setting_failure_grace_footnote),
    ),
    SettingSearchEntry(
        id = SettingIds.NIGHT_THEME,
        category = SettingsCategory.DISPLAY,
        label = stringResource(R.string.setting_night_theme),
        description = stringResource(R.string.setting_night_theme_description),
    ),
    SettingSearchEntry(
        id = SettingIds.KEEP_SCREEN,
        category = SettingsCategory.DISPLAY,
        label = stringResource(R.string.setting_keep_screen),
        description = stringResource(R.string.setting_keep_screen_description),
    ),
    SettingSearchEntry(
        id = SettingIds.ORIENTATION,
        category = SettingsCategory.DISPLAY,
        label = stringResource(R.string.setting_orientation),
        description = stringResource(settings.orientationLock.descriptionRes()),
    ),
    SettingSearchEntry(
        id = SettingIds.TALKBACK_VOLUME,
        category = SettingsCategory.DISPLAY,
        label = stringResource(
            R.string.talkback_volume_label,
            (settings.talkbackVolume * 100).roundToInt(),
        ),
        description = stringResource(R.string.talkback_volume_footnote),
    ),
)
