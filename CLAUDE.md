# CLAUDE.md

Guidance for anyone (people and AI assistants) working in this repository.

## Project

Ghostly VPN — open-source client (Kotlin Multiplatform + Compose Multiplatform) with two cores:
Xray (default) and mihomo (Prizrak-Core). Modules: `shared/` (all logic and UI), `androidApp/`,
`desktopApp/` (Windows/macOS/Linux), iOS via `shared/src/iosMain`. The app talks to Ghostly's
subscription server (`/sub/<id>`: Xray JSON for the Ghostly/Happ User-Agent, a mihomo profile with
groups for clash/mihomo User-Agents).

## How we work

- **Branch + pull request into `main`.** `main` is protected: no direct pushes, no force pushes;
  a PR needs one approval from the maintainer (@Nelxi). Name branches by topic
  (`mihomo-…`, `android-…`, `ui-…`).
- Keep PRs focused; describe what changed and how it was checked (device, core log, tests).
- `./gradlew :shared:jvmTest` must pass. Add a test in `shared/src/commonTest` for logic changes.
- Do **not** bump `versionName`/`versionCode`/`ghostlyVersion` in PRs — the maintainer sets the
  version when releasing.
- Releases are made by the maintainer only: Android builds are signed with a key that is not in
  the repo, and auto-update is served from Ghostly's own server (`/dl/latest.json`). Don't create
  tags or GitHub releases from PRs.

## Conventions

- Code comments in English, user-facing strings in Russian.
- One core at a time: what's shown and used follows the core chosen in settings
  (`core/mihomo/CoreFilter.kt`).
- Haptics go through `GhostlyController.haptic(Haptic.…)` with a strength (TICK / CLICK / HEAVY /
  SUCCESS / ERROR); the user's vibration setting is respected there.
- Update manifest: each file in `latest.json` carries its own `version` — a release may reuse the
  previous build of a platform that didn't change.
