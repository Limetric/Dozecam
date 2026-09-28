import ActivityKit
import AlarmKit
import SwiftUI
import WidgetKit

@main
struct SpikeWidgets: WidgetBundle {
    var body: some Widget {
        MonitoringActivityWidget()
        AlarmActivityWidget()
    }
}

struct MonitoringActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MonitoringAttributes.self) { context in
            VStack(alignment: .leading, spacing: 4) {
                Text("Monitoring \(context.state.rooms) rooms").font(.headline)
                Text(context.state.status).font(.subheadline)
                HStack {
                    Text("Last beat")
                    Text(context.state.lastBeat, style: .relative)
                    Text("ago · #\(context.state.beats)")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if context.isStale {
                    Text("STALE — the app stopped updating").font(.caption.bold()).foregroundStyle(.red)
                }
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    Text("Monitoring \(context.state.rooms) rooms · \(context.state.status)")
                }
            } compactLeading: {
                Image(systemName: "ear")
            } compactTrailing: {
                Text("\(context.state.rooms)")
            } minimal: {
                Image(systemName: "ear")
            }
        }
    }
}

/// AlarmKit renders its countdown (snooze) state through a Live Activity the app provides.
struct AlarmActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<SpikeAlarmMetadata>.self) { context in
            VStack(alignment: .leading) {
                Text(context.attributes.metadata?.room ?? "Alarm").font(.headline)
                switch context.state.mode {
                case .countdown(let countdown):
                    Text(timerInterval: Date.now...countdown.fireDate, countsDown: true)
                default:
                    Text("Alarm")
                }
            }
            .padding()
        } dynamicIsland: { _ in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text("Alarm") }
            } compactLeading: {
                Image(systemName: "alarm")
            } compactTrailing: {
                EmptyView()
            } minimal: {
                Image(systemName: "alarm")
            }
        }
    }
}
