import SwiftUI

@main
struct SpikeApp: App {
    @State private var monitor = Monitor()

    var body: some Scene {
        WindowGroup {
            ContentView(monitor: monitor)
        }
    }
}

struct ContentView: View {
    @Bindable var monitor: Monitor
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Monitoring") {
                    HStack {
                        Text(monitor.running ? "Running" : "Stopped").bold()
                        Spacer()
                        Button(monitor.running ? "Stop" : "Start") {
                            monitor.running ? monitor.stop() : monitor.start()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Text(monitor.lastLine.isEmpty ? "No beat yet" : monitor.lastLine)
                        .font(.caption.monospaced())
                    Toggle("Mix with others (applies on start)", isOn: $monitor.mixWithOthers)
                    Toggle("Live Activity", isOn: $monitor.liveActivityEnabled)
                    Stepper("Heartbeat every \(monitor.beatSeconds) s", value: $monitor.beatSeconds, in: 10...120, step: 10)
                    Stepper("Dead-man after \(monitor.deadManMinutes) min", value: $monitor.deadManMinutes, in: 1...30)
                    Toggle("Dead-man also as AlarmKit alarm", isOn: $monitor.alarmDeadMan)
                    TextField("LAN probe host (port 18554)", text: $monitor.probeHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Alert lab") {
                    Toggle("AlarmKit alarm", isOn: $monitor.useAlarmKit)
                    Toggle("Time-sensitive notification", isOn: $monitor.useTimeSensitive)
                    Toggle("Own alarm tone (media volume)", isOn: $monitor.useOwnSound)
                    Toggle("Vibrate", isOn: $monitor.useVibrate)
                    Stepper("Fire after \(monitor.triggerDelay) s", value: $monitor.triggerDelay, in: 5...3600, step: 5)
                    if let at = monitor.pendingTrigger {
                        Text("Fires at \(at.formatted(date: .omitted, time: .standard))")
                    }
                    HStack {
                        Button("Arm trigger") { monitor.armTrigger() }
                            .buttonStyle(.borderedProminent)
                        Spacer()
                        Button("Stop alarms", role: .destructive) { monitor.stopAlarms() }
                    }
                    Button("Probe AlarmKit limit") { Task { await monitor.probeAlarmLimit() } }
                    Button("Request permissions") { Task { await monitor.requestPermissions() } }
                }
                Section("Log") {
                    HStack {
                        TextField("Note, e.g. \"Sleep Focus on\"", text: $note)
                        Button("Log") {
                            SpikeLog.write("NOTE", note)
                            note = ""
                        }
                    }
                    ShareLink(item: SpikeLog.shared.url)
                    TimelineView(.periodic(from: .now, by: 2)) { _ in
                        VStack(alignment: .leading, spacing: 2) {
                            ForEach(Array(SpikeLog.shared.tail(120).enumerated()), id: \.offset) { _, line in
                                Text(line).font(.system(size: 10).monospaced())
                            }
                        }
                    }
                }
            }
            .navigationTitle("Night Spike")
        }
    }
}
