# Multiplayer Bridge

A separate Android project for playing supported offline games together through
shared rooms and text chat. The first Android build is the game library: it imports
APK files, reads their app information, and keeps a private local copy.

## Current build: 0.1.0

- Native Kotlin application with Jetpack Compose and system light/dark appearance.
- Import a single APK using Android's file picker, without broad storage access.
- See the app name, version, original filename, package, and file size.
- Search saved games and remove Bridge's copy without touching the original file.
- Persistent storage, duplicate detection by file content, and cleanup of failed
  imports. File copying and inspection run off the UI thread.

This is an import and library build. It does not execute imported APKs, replace
computer opponents, connect rooms, or provide chat yet. No game APK is bundled.
The application ID is `com.ruyolidus.multiplayerbridge`, separate from Ruyo.

## Install or build

The **Bridge Android** workflow runs only for this branch. A successful run
provides a **Multiplayer-Bridge-preview** artifact containing `app-debug.apk` and
a **Bridge-checks** artifact containing test reports and UI screenshots.

The app supports Android 8.0 and newer. The preview signing key is cached by CI;
if that cache is ever cleared, a subsequent build can require reinstallation.
Production signing is not configured.

For a local build, use JDK 17 and an Android SDK with `platforms;android-36` and
`build-tools;35.0.0`. Set `ANDROID_HOME` or the untracked `local.properties` file.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The build pins Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, and Compose BOM 2025.09.01.
The Gradle distribution is checksum-verified. See [NOTICE.md](NOTICE.md).

## Dedicated branch

Repository: **Ruyolidus/Ruyo**

Permanent project branch: **multiplayer-bridge**

All work for this project stays on this branch. Do not create more project
branches or merge this project into Ruyo. See [AGENTS.md](AGENTS.md) for the
standing instructions.

This branch started with a separate file tree; the Ruyo application is not
included. Its initial setup commit has Ruyo's existing `main` commit as its Git
parent, so it shares earlier history. It is not an orphan branch. Future work
remains separate and must never be merged back.

## Dedicated checkout

```sh
git clone --single-branch --branch multiplayer-bridge https://github.com/Ruyolidus/Ruyo.git multiplayer-bridge
cd multiplayer-bridge
./scripts/setup-workspace.sh
```

The setup installs local hooks that reject commits on another branch, pushes to
another branch or repository, and merge commits. It also sets the default push
destination to this branch. These are local checks, not server-side branch
protection; GitHub API operations must follow the same rules explicitly.

## First feasibility milestone

1. Select an actual offline Ludo or draughts/checkers app with a two-human mode.
2. Prove execution in the selected Android hosting/runtime approach.
3. Show one running game session to two connected users.
4. Route input from the user who currently has control and support an explicit
   handoff between turns.
5. Add private rooms and text-only chat around the shared session.
6. Finish a match across separate networks and measure reconnect behavior,
   input delay, and bandwidth before expanding compatibility or adding ads.

The original game handles rules and randomness. A game with only a computer
opponent needs an additional integration; screen sharing alone does not turn
that opponent into a human player. Importing arbitrary APKs and automatically
adding multiplayer is not an implemented or promised capability.
