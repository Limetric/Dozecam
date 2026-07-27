package app.dozecam.monitoring

import app.dozecam.data.Camera

/**
 * What [MonitoringService] must change to match a new set of enabled cameras.
 * Pure, and separate from the service, because the interesting part is the
 * decision — not the ExoPlayer instances it results in.
 */
data class MonitorPlan(
    /** Camera ids whose monitor must be torn down. */
    val stop: Set<String>,
    /** Cameras that need a monitor started. */
    val start: List<Camera>,
) {
    val isEmpty: Boolean get() = stop.isEmpty() && start.isEmpty()

    companion object {
        /**
         * A camera keeps its running monitor — and so its detector phase and
         * reconnect backoff — unless it is gone or its URL changed under the
         * same id. Renaming a camera or editing another one must never re-arm
         * a detector that was mid-way through a refractory window.
         */
        fun of(running: Map<String, Camera>, wanted: List<Camera>): MonitorPlan {
            val byId = wanted.associateBy { it.id }
            val stale = running.filterKeys { it in byId }
                .filterValues { it.url != byId.getValue(it.id).url }
                .keys
            val stop = (running.keys - byId.keys) + stale
            val start = wanted.filter { it.id !in running || it.id in stale }
            return MonitorPlan(stop = stop, start = start)
        }
    }
}
