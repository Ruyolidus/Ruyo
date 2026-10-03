# Multiplayer Bridge

A separate Android project for playing supported offline games together through
shared rooms and text chat. This branch contains the initial project setup and
working rules; there is no playable application or APK yet.

## Dedicated branch

Repository: **Ruyolidus/Ruyo**
Permanent project branch: **multiplayer-bridge**

All work for this project stays on this branch. Do not create more project
branches or merge this project into Ruyo. See [AGENTS.md](AGENTS.md) for the
standing instructions.

This branch starts with a separate file tree; the Ruyo application is not
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
