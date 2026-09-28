# AGENTS.md

This file provides guidance to coding agents working in this repository. Each platform has its own AGENTS.md with its commands, build variants and architecture; read the one for the code you are changing.

## What this is

Dozecam is a baby monitor for UniFi Protect cameras: low-latency live view, wake-on-sound monitoring, honest connection state, and Protect console onboarding. Everything is LAN-only — no cloud, no accounts.

Naming: the product is "Dozecam". Store copy must not lead with "UniFi" (Ubiquiti trademark) — describe compatibility as "for UniFi Protect cameras".

## Repository layout

A monorepo with one native app per platform:

- `android/` — the Android app (Kotlin, Jetpack Compose, Gradle). Guidance: `android/AGENTS.md`.
- `ios/` — the iPhone and iPad app (Swift, SwiftUI), arriving with #62. Guidance will live in `ios/AGENTS.md`.
- `shared/` — the platform-neutral product spec and the golden test fixtures both apps' tests read, arriving with #61.
- `tools/` — shared tooling: `testbed.sh` (synthetic RTSP cameras for testing without a Protect console), `release/` (Play copy extraction), and the talk-back spike.
- `store-listing/<platform>/` — store copy.

The two apps share no code. Shared behaviour is enforced through the spec and fixtures in `shared/`, with the Android app as the reference implementation. Until `shared/spec` exists (#61), `android/AGENTS.md` is where the product rules are written down.

## Product rules

These hold on every platform. Where a platform cannot do what another does, it implements the nearest equivalent and the spec says so; a rule is never dropped silently.

- **LAN only**: no cloud, no accounts.
- **Always-on monitoring**: monitoring arms whenever the viewer is open and ends only when the app is exited.
- **Fail loud**: every way Dozecam can stop being a baby monitor while armed is announced, once, after a grace period.
- **Honest connection state**: a frozen frame never pretends to be live.

## CI and releases

Each platform has its own workflows, prefixed with its name (`.github/workflows/android-*.yml`), and path-filtered so a change to one platform does not run the other's suite. Changes under `shared/` run every platform.

Releases are Android-only for now: see "Build variants" in `android/AGENTS.md`. Per-platform release tags arrive with iOS releases (#63).
