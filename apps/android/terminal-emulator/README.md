# terminal-emulator (vendored)

VT100/xterm terminal emulator from [termux/termux-app](https://github.com/termux/termux-app)
`terminal-emulator` module (tag `v0.118.0`), originally from
[jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator).

- **License:** Apache License 2.0 (see `LICENSE`)
- **Hypurr change:** local JNI PTY subprocess removed; `TerminalSession` feeds a remote
  host PTY over the existing `openShell` / `termInput` / `termEvents` channel.

Do not pull in Termux app code (GPLv3). Only this Apache-2.0 library (and `terminal-view`).
