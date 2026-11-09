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
| **Android** | Bot settings → **Terminal**. Live scrollback (ANSI stripped for readability), line input, Esc/Tab/^C/^D/arrows, resize, reconnect, close on leave. |
| **iPhone / Mac (kit)** | `HostClient.openShell` API wired. Full VT UI for **setup** terminals already exists (`SetupTerminal` + SwiftTerm). Project-shell screen entry is not in this PR. |
| **Linux** | VTE UI exists for **setup** terminals. Project-shell entry not in this PR. |

## API

| Method | Body |
|--------|------|
| `openShell` | `cwd` **or** `botId` **or** `taskId`, optional `cols`/`rows` → `{ term, cwd }` |
| `termInput` | `term`, `data` (base64) |
| `termResize` | `term`, `cols`, `rows` |
| `termClose` | `term` |
| stream | channel `sub: term` with `{ term }`, or HTTP `GET /term/{id}` on loopback |

## Tests

- Host: `shell_cwd_must_be_under_a_known_project`, `open_shell_runs_interactive_pwd` (temp dir + `pwd; exit` only)
- Android: `TerminalScreenTest` (ANSI strip / base64), `TerminalScreenshotTest` (connecting / live / ended)

## Limitations

- Android shows stripped scrollback, not a full VT (colors / cursor addressing / full-screen TUIs are degraded). iPhone/Mac setup terminals use SwiftTerm for full VT; project shell UI there is still pending.
- Not phone-verified on a real device in this PR (emulator / Roborazzi + host unit tests only).
- No automatic sample commands are run from the UI.
