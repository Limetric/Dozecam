# Dozecam product spec

The rules that make Dozecam a baby monitor, written once for every platform. The Android app and the iOS app share no code; they are held to the same behaviour by this spec and by the golden test data in [`shared/fixtures/`](../fixtures/). Where this spec and a platform's AGENTS.md or code comments disagree, this spec wins, and the other one is fixed.

| File | Area |
|---|---|
| [monitoring-lifecycle.md](monitoring-lifecycle.md) | Always-on monitoring: arming, exit, the "Not monitoring" badge, staying alive |
| [alerts-and-sound-modes.md](alerts-and-sound-modes.md) | The sound alert, the detector, sound modes and listen mode |
| [failure-alerts.md](failure-alerts.md) | Failing loud: causes, grace period, once-only announcement, recovery |
| [connection-state.md](connection-state.md) | Connection states, stall detection, reconnect backoff, the monitor's transports |
| [protect.md](protect.md) | Protect consoles: APIs, camera ids, stream URLs, certificate pinning, the livestream |
| [privacy.md](privacy.md) | LAN only, and what the device stores |

## Reading a rule

- A rule is a statement of behaviour, not of code. Numbers are defaults unless a range says the user can change them.
- **Android reference:** names the class or function in `android/app/src/main/java/app/dozecam/` that implements the rule today. It is a pointer for reading, not part of the rule.
- **Fixtures:** links the directory in `shared/fixtures/` whose cases encode the rule as test data. Both apps' unit tests read those cases, so a fixture is the executable form of the rule next to it.
- **Android / iOS:** where a platform cannot do what the rule says, it does the nearest equivalent, and the note says what and why. A rule is never dropped silently. The iOS app is a scaffold today (#62): its notes state what the iOS app must do, with the issue that delivers it (#58 and #59 hold the spike findings; #64–#69 the implementation).

## Android is the reference

The Android app is the reference implementation. Every fixture was first proven against the Android unit tests, and where this spec leaves a detail open, the Android behaviour is the answer until the spec says otherwise.

## Changing a rule

A rule changes in one pull request that updates, together:

1. the rule in this spec;
2. the fixtures that encode it, if any (see the conventions in [`shared/fixtures/README.md`](../fixtures/README.md));
3. both apps, or, while a platform has not implemented the area yet, its tracking issue.

A pull request that changes one of these without the others is incomplete. Changes under `shared/` run every platform's CI.
