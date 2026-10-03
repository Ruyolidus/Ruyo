# Multiplayer Bridge project instructions

## Permanent branch contract

The user established these rules on 2026-10-03:

- Repository: `Ruyolidus/Ruyo`.
- The only branch for this project is `multiplayer-bridge`.
- All development, fixes, experiments, commits, and pushes for this project stay
  on that same branch. Do not create additional remote or local project branches.
- Never merge this project into Ruyo's `main` or any other branch. Never create a
  pull request for such a merge, enable auto-merge, or change the default branch.
- Keep this project's files and application identity separate from the Ruyo
  reader. Do not merge or rebase other Ruyo branches into this project.
- Do not force-push, delete, or recreate the permanent branch.

Before editing, committing, or pushing, run `./scripts/check-branch.sh`. Work in a
dedicated checkout of the existing `multiplayer-bridge` branch. If the checkout is
on another branch or detached, establish the correct checkout before proceeding;
do not create a differently named branch as a workaround.

Run `./scripts/setup-workspace.sh` in a fresh checkout to install the local Git
hooks and configure the single push destination. GitHub API writes must also
explicitly target `multiplayer-bridge`; local hooks do not apply to API writes.

## Product scope

This is a separate Android project for bringing supported offline games into
shared rooms, with nearby or internet play and text-only chat. The long-term
goal includes importing an existing game into a compatible runtime. It is not
a Ruyo feature and must not replace or modify the reader app.

The first feasibility target is one actual offline Ludo or draughts/checkers app
with a two-human, pass-and-play mode. Use one running game session, remote input,
and an explicit control handoff. The original game remains responsible for its
rules. No test APK has been selected or supplied yet.

Do not silently replace the imported-game goal with a collection of newly built
board games. Do not claim universal APK compatibility, automatic understanding
of arbitrary game code, or replacement of computer opponents before proving it
with specific integrations.

Chat supports text only: no photo, image, audio, or video attachments. Captured
gameplay needed to display the shared session is separate from chat attachments.
Advertising is a future business goal, not an implemented capability.

## Implementation and validation

- Use a distinct application ID, app name, storage, and signing setup when the
  Android application is created. Do not use `com.ruyo`.
- Keep all new automation scoped to `multiplayer-bridge`; do not reuse Ruyo
  publishing or release automation.
- Establish APK execution and authorized remote input on actual Android devices
  before claiming that an imported game is playable online.
- Validate turn handoff, reconnects, input ordering, and completion of a match on
  separate networks. Report what was actually tested.
- Preserve the user's single-branch instructions in this file as work continues.
