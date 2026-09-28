import ActivityKit
import AlarmKit
import Foundation

/// The Live Activity counterpart of Android's ongoing "Monitoring 2 rooms" card.
struct MonitoringAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var rooms: Int
        var lastBeat: Date
        var status: String
        var beats: Int
    }

    var startedAt: Date
}

struct SpikeAlarmMetadata: AlarmMetadata {
    var room: String
}
