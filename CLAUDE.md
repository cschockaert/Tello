# CLAUDE.md — TelloPilot

Contribution guide for Claude Code on this repo. Read it before building,
releasing or pushing.

## Language: English only

**All content in this repository must be written in English** — no exceptions:

- Code comments and KDoc.
- User-facing strings (UI labels in `res/values/strings.xml`, `onLog`/`toast`
  messages, status text). If localization is ever needed, English stays the
  default `values/` resource and other languages go in `values-<lang>/`.
- Documentation (`README.md`, `CLAUDE.md`, inline docs).
- Commit messages, branch names, PR titles and descriptions.
- Release notes and workflow YAML comments.

When editing or adding anything, keep it in English even if nearby legacy text
was in another language — and translate that legacy text while you are there.

## Project

Native Android app in Kotlin to fly a Ryze/DJI Tello drone (UDP SDK).
`minSdk 26`, `targetSdk 34`. See `README.md` for the architecture and the
manual test checklist.

## Build & verification

- Build: `./gradlew assembleDebug` (Gradle 8.7 wrapper included, JDK 17).
- The **Android SDK is not available in the sandbox** (Google endpoints blocked
  by the proxy) → full compilation and the APK are verified **in CI**
  (`.github/workflows/android.yml`), not locally.
- Local check still possible: the pure-logic modules (`TelloController`,
  `TelloState`, `JoystickView`) compile against the embedded Kotlin compiler +
  a stub `android.jar`. Useful to catch syntax/typing errors before pushing.
- Mandatory honesty: **real flight** (UDP, video, media writing) is **not
  verifiable** here. Never claim piloting "works" — only compilation and the CI
  build are proven.

## Semantic release — `[release vX.Y.Z]` marker

We version in **SemVer** (`vMAJOR.MINOR.PATCH`). A release publishes a **GitHub
Release** with the debug APK attached, via `.github/workflows/release.yml`.

### Why this mechanism

The sandbox git proxy **blocks pushing tags** (and `main`) with a 403, and the
GitHub integration token can neither dispatch a workflow nor create a release.
Solution: the **runner's `GITHUB_TOKEN`** (server-side, outside the proxy)
creates the tag + the release. We trigger it with a plain push of the allowed
branch carrying a marker in the commit message.

### How to cut a release

1. Bump the app version in `app/build.gradle.kts`:
   - `versionName` = the SemVer version (e.g. `"1.1.1"`),
   - `versionCode` = a monotonic integer increment (e.g. `2` → `3`).
   The APK must reflect the published version, otherwise it installs under the
   old one.
2. Commit with a **`[release vX.Y.Z]`** marker in the message, then
   `git push -u origin <branch>`.
3. The `release.yml` workflow:
   - runs only if the HEAD commit message contains `[release` (or a tag push,
     or `workflow_dispatch`),
   - resolves the tag from `[release vX.Y.Z]` (default `v1.0` if absent),
   - builds `assembleDebug`, renames the APK `TelloPilot-vX.Y.Z-debug.apk`,
   - creates the release + tag `vX.Y.Z` with the APK attached
     (`softprops/action-gh-release`).
4. Verify: `get_release_by_tag vX.Y.Z` → the `.apk` asset must be `uploaded`.

A commit **without** `[release` triggers only the normal build CI — no release.
The tag points at the built commit of the branch.

> Note: match the marker prefix `[release` (no closing bracket), so both
> `[release]` and `[release vX.Y.Z]` trigger. Matching `[release]` alone would
> miss the versioned form.

### Version history

- `v1.0` — initial MVP.
- `v1.1` — reliable SDK handshake (takeoff + video work). Landing still broken.
- `v1.1.1` — landing fix (rc loop gated on `flying`) + `versionName`/`versionCode`
  aligned with the tag (the APK still reported 1.0).

## Git / PR

- Develop on the dedicated branch `claude/tello-android-app-mvp-5xuyfa`.
- The proxy only allows pushing **that branch** (no tags, no direct `main`).
- A **merged PR is finished**: any follow-up work = **new PR** (do not stack on
  already-merged history; restart the branch from the latest `main`).
- Always `git push -u origin <branch>`; retry with backoff on network errors.
  PR merges via the GitHub API (MCP).

## Tello-specific pitfalls (already handled — do not regress)

- **rc loop gated on `flying`**: continuously streaming `rc 0 0 0 0` is a
  hold-altitude command that **prevents landing**. `land`/`emergency` set
  `flying=false` to cut the keep-alive. Never make the rc loop unconditional.
- **Blocking SDK handshake**: wait for the Tello's `ok` before starting the rc
  loop / `streamon`. Sending `takeoff`/`streamon` before the `ok` = silently
  ignored.
- **Network binding**: `bindProcessToNetwork` on the WIFI transport before
  opening the sockets (the Tello Wi-Fi has no internet).
- **Socket I/O never on the main thread.**
