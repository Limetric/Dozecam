package app.dozecam.audio

import app.dozecam.data.DetectorSettings

/**
 * Wake-on-sound state machine. Deliberately boring:
 *
 * - ARMED: waiting for a loud level.
 * - BUILDING: level went loud; a dip back below threshold re-arms (a single
 *   thud or dropped pacifier must not wake the room). Staying loud for
 *   [DetectorSettings.sustainMs] fires the trigger.
 * - TRIGGERED: refractory; re-arms only after the level stays below threshold
 *   for [DetectorSettings.quietMs] straight.
 *
 * Not thread-safe; feed it from one thread or synchronize externally.
 */
class SoundDetector(settings: DetectorSettings) {

    enum class Phase { ARMED, BUILDING, TRIGGERED }

    var settings: DetectorSettings = settings
        private set

    var phase: Phase = Phase.ARMED
        private set

    private var loudSinceMs = 0L
    private var quietSinceMs = -1L

    fun updateSettings(settings: DetectorSettings) {
        this.settings = settings
    }

    /** Feed one level sample; returns true exactly when a trigger fires. */
    fun onLevel(rms: Float, nowMs: Long): Boolean {
        val loud = rms >= settings.threshold
        when (phase) {
            Phase.ARMED -> if (loud) {
                phase = Phase.BUILDING
                loudSinceMs = nowMs
            }

            Phase.BUILDING -> {
                if (!loud) {
                    phase = Phase.ARMED
                } else if (nowMs - loudSinceMs >= settings.sustainMs) {
                    phase = Phase.TRIGGERED
                    quietSinceMs = -1
                    return true
                }
            }

            Phase.TRIGGERED -> {
                if (loud) {
                    quietSinceMs = -1
                } else if (quietSinceMs < 0) {
                    quietSinceMs = nowMs
                } else if (nowMs - quietSinceMs >= settings.quietMs) {
                    phase = Phase.ARMED
                }
            }
        }
        return false
    }
}
