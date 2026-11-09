# Phone terminal (project / task shell)

## What this is

An interactive shell on the paired computer, opened from the phone in a **scoped
project folder**. It reuses the host PTY pool and the same `termInput` /
`termResize` / `termClose` / channel `sub: term` streaming as setup terminals.

It is **not** the agent install/login setup terminal (`agentSetup`). Setup
terminals run a host-chosen one-shot command. Project shells start an interactive
`$SHELL` (PowerShell on Windows) with cwd set by the host.

## Access scoping

`openShell` accepts `cwd`, or resolves a folder from `botId` / `taskId`.

The canonical path must sit under one of:

- a saved project (`tasks` projects list)
- a bot's working folder
- a task worktree or task project path

When the team's `allowedFolders` rule is set, the path must also sit under that
list. Viewers cannot open a shell (method is not in `VIEWER_OK`). The host never
accepts a caller-supplied command string — only keystrokes after the shell starts.

## Clients

| Client | Coverage |
|--------|----------|
| **Android** | Bot settings → **Terminal**. Full VT100/xterm via vendored Termux `terminal-emulator` + `terminal-view` (Apache-2.0): 16/256/truecolor, cursor, alternate screen, wrap, scroll regions, touch selection/copy, paste, extra keys (Esc/Tab/Ctrl/Alt/arrows/PgUp/PgDn/Home/End), pinch or A± font size, IME + safe-area padding, Hypurr light/dark. Connection states, reconnect, close on leave. |
| **iPhone / Mac (kit)** | `HostClient.openShell` API wired. Full VT UI for **setup** terminals already exists (`SetupTerminal` + SwiftTerm). Project-shell screen entry is not in this PR. |
| **Linux** | VTE UI exists for **setup** terminals. Project-shell entry not in this PR. |

## Third-party (Android VT)

Vendored under `apps/android/terminal-emulator` and `apps/android/terminal-view`
from [termux/termux-app](https://github.com/termux/termux-app) tag `v0.118.0`
(originally jackpal Android Terminal Emulator). Apache-2.0 only — **not** Termux
app GPLv3 code. Local JNI PTY removed; bytes bridge to the host `openShell` PTY.
See `NOTICE` and each module's `LICENSE` / `README.md`.

## API

| Method | Body |
|--------|------|
| `openShell` | `cwd` **or** `botId` **or** `taskId`, optional `cols`/`rows` → `{ term, cwd }` |
| `termInput` | `term`, `data` (base64) |
| `termResize` | `term`, `cols`, `rows` |
| `termClose` | `term` |
| stream | channel `sub: term` with `{ term }`, or HTTP `GET /term/{id}` on loopback |

## Tests

- Host: `shell_cwd_must_be_under_a_known_project`, `open_shell_runs_interactive_pwd`, `open_shell_benign_commands_in_temp_dir` (temp dir only; `pwd` / `echo` / `ls` / `exit`)
- Android unit: `TerminalScreenTest` (VT colors/alt/cursor/wrap fixtures + base64)
- Android screenshots (Roborazzi / **emulator**, not a real phone): connecting, TUI colors, alt screen, ended, small phone, keyboard-open — light/dark
- Vendored Termux emulator tests under `:terminal-emulator:testDebugUnitTest`

## Limitations

- iPhone/Mac/Linux project-shell UI still pending (setup terminals already have full VT).
- Not phone-verified on a **real device** in this PR (Android emulator / Roborazzi + host unit tests only). Label: **emulator**.
- No automatic sample commands are run from the UI.
- Mouse reporting / some rare DEC modes follow Termux coverage; no local on-device shell.
