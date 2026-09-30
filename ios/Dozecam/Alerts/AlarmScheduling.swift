@preconcurrency import ActivityKit
@preconcurrency import AlarmKit
import Foundation
import SwiftUI
import os

/// One AlarmKit alarm, as Dozecam describes it: a value, so what is scheduled
/// can be checked in a test without AlarmKit.
struct AlarmSpec: Equatable, Sendable {
    enum Purpose: String, Codable, Sendable {
        case room
        case failure
        case test
        case deadMan
    }

    /// The whole of what the alarm's full-screen alert says, beside the time
    /// and the app's name: AlarmKit shows a title and nothing else.
    var title: String
    /// When it rings. AlarmKit refuses "now"; a second ahead is the soonest
    /// it takes (#58).
    var fireDate: Date
    /// The system alarm sound when nil; otherwise a bundled tone.
    var tone: AlarmTone?
    var purpose: Purpose
    /// The room, for a room's alarm.
    var cameraId: String?
}

/// Where an alarm stands, as AlarmKit reports it.
enum AlarmPhase: Equatable, Sendable {
    case scheduled
    case countdown
    case paused
    case alerting
}

struct AlarmSnapshot: Equatable, Sendable {
    var id: UUID
    var phase: AlarmPhase
}

/// AlarmKit, behind a seam: the alert path (`AlarmKitAlerts`) and the dead-man
/// (`DeadManSwitch`) decide what to ring and when, and this only carries it
/// out. Kept thin because AlarmKit cannot be exercised in the simulator; the
/// rules around it are tested with a fake.
@MainActor
protocol AlarmScheduling: AnyObject {
    /// Schedules `spec` under `id`. Throws when AlarmKit refuses: not
    /// authorised, a date too soon, or its per-app limit reached.
    func schedule(id: UUID, _ spec: AlarmSpec) async throws
    /// Silences and removes the alarm, whether it is ringing or still
    /// scheduled. An alarm that is already gone (it rang and was stopped, say)
    /// is not an error: AlarmKit answers that with its generic error, code 0
    /// (#58), and this treats it as done. Throws only when the alarm is still
    /// there afterwards.
    func end(id: UUID) throws
    /// Every alarm of this app now; nil when AlarmKit cannot say.
    func current() -> [AlarmSnapshot]?
    /// Every alarm of this app, on every change. The acknowledgement is an alarm leaving this
    /// list without the app having ended it: the user stopped it from the
    /// lock screen.
    func updates() -> AsyncStream<[AlarmSnapshot]>
}

/// What Dozecam attaches to its alarms. No widget extension reads it: the
/// alerting UI is the system's, and Dozecam's alarms have no countdown, the
/// one presentation that needs a Live Activity of the app's own.
struct DozecamAlarmMetadata: AlarmMetadata {
    var purpose: AlarmSpec.Purpose
    var cameraId: String?
}

/// `AlarmManager.shared`.
///
/// Every alarm is `.alarm` with a `.fixed` date and an alert presentation with
/// only a title: no snooze (a secondary button with countdown behaviour), since
/// the spec's alarm has none and a countdown would need a widget extension
/// (AlarmKit "may unexpectedly dismiss alarms" without one). The system's Stop
/// is the only button, and it is the acknowledgement.
@MainActor
final class SystemAlarmScheduler: AlarmScheduling {
    private static let log = Logger(subsystem: "app.dozecam", category: "alerts")

    func schedule(id: UUID, _ spec: AlarmSpec) async throws {
        try await Self.schedule(id: id, spec)
    }

    func end(id: UUID) throws {
        let manager = AlarmManager.shared
        let phase = (try? manager.alarms)?.first { $0.id == id }?.state
        do {
            // A ringing alarm is stopped, then cancelled, so neither the sound
            // nor the alarm outlives this call.
            if phase == .alerting { try? manager.stop(id: id) }
            try manager.cancel(id: id)
        } catch {
            // Gone already is done (#58: code 0 on an alarm that has rung).
            let stillThere = (try? manager.alarms)?.contains { $0.id == id } ?? true
            guard stillThere else { return }
            Self.log.error("alarm \(id, privacy: .public) could not be ended: \(error, privacy: .public)")
            throw error
        }
    }

    func current() -> [AlarmSnapshot]? {
        (try? AlarmManager.shared.alarms).map { $0.map(AlarmSnapshot.init) }
    }

    func updates() -> AsyncStream<[AlarmSnapshot]> {
        Self.observe()
    }

    // Built off the main actor: the configuration is not Sendable, and the
    // update loop runs on AlarmKit's terms (#58).

    private nonisolated static func schedule(id: UUID, _ spec: AlarmSpec) async throws {
        let attributes = AlarmAttributes<DozecamAlarmMetadata>(
            presentation: AlarmPresentation(
                alert: AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: spec.title))),
            metadata: DozecamAlarmMetadata(purpose: spec.purpose, cameraId: spec.cameraId),
            tintColor: spec.purpose == .room || spec.purpose == .test ? .orange : .red
        )
        let sound: AlertConfiguration.AlertSound = spec.tone.map { .named($0.fileName) } ?? .default
        let configuration = AlarmManager.AlarmConfiguration<DozecamAlarmMetadata>.alarm(
            schedule: .fixed(spec.fireDate), attributes: attributes, sound: sound)
        _ = try await AlarmManager.shared.schedule(id: id, configuration: configuration)
    }

    private nonisolated static func observe() -> AsyncStream<[AlarmSnapshot]> {
        AsyncStream { continuation in
            let task = Task {
                for await alarms in AlarmManager.shared.alarmUpdates {
                    continuation.yield(alarms.map(AlarmSnapshot.init))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

extension AlarmSnapshot {
    init(_ alarm: Alarm) {
        let phase: AlarmPhase =
            switch alarm.state {
            case .scheduled: .scheduled
            case .countdown: .countdown
            case .paused: .paused
            case .alerting: .alerting
            @unknown default: .scheduled
            }
        self.init(id: alarm.id, phase: phase)
    }
}
