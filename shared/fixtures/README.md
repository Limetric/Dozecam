# Shared test fixtures

Golden test vectors that the Android and iOS unit tests both read, so the two
apps are held to the same behaviour. The Android app is the reference
implementation: every fixture here was first proven against its tests.

- **Android** reads them through `app.dozecam.testing.Fixtures`; Gradle passes
  this directory as the `dozecam.fixtures` system property and declares it as
  a test input.
- **iOS** reads them through `Fixtures` in `ios/DozecamTests/Support`, straight
  from the checkout (the simulator sees the host's file system).

## Conventions

- One directory per area, each with a `README.md` describing its schema.
- JSON for tables and timelines; raw `.bin` for byte captures.
- Keys are `camelCase`. Times are integers in milliseconds, named `…Ms`.
- Loaders reject unknown keys, so a typo in a fixture fails loudly instead of
  silently testing nothing.
- A case has a short `name` that says what it proves; tests report it on
  failure. A case is an object with a `name` in a top-level array of a fixture
  file (the verbatim console responses in `protect-api/public` and
  `protect-api/legacy` are not cases).
- Tests pick cases by name, so every case must be run by name on each
  platform. Android's `FixtureCoverageTest` fails when a case appears in no
  Android test; iOS gets the same guard with its first fixture-driven tests.
- Changing a fixture changes the rule for both apps: say so in the PR, and
  update `shared/spec` when the rule itself moves.
