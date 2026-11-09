//! Terminals on this computer, streamed to the client that started them.
//!
//! Two kinds share one PTY pool and the same `termInput` / `termResize` /
//! `termClose` / `/term/{id}` (and channel `sub: term`) plumbing:
//! - **Setup** (`agentSetup`): install or sign in — the host picks the command.
//! - **Shell** (`openShell`): an interactive project/task shell. The cwd must
//!   resolve under a saved project, bot folder, or task worktree (and under
//!   `allowedFolders` when that team rule is set). Clients type; the host never
//!   runs a caller-supplied command string.

use crate::LockExt;
use crate::agent::backends;
use anyhow::{Result, anyhow, bail};
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use portable_pty::{Child, CommandBuilder, MasterPty, PtySize, native_pty_system};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::io::{Read as _, Write as _};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{broadcast, mpsc};

/// Output kept for a client that (re)attaches mid-run.
const SCROLLBACK: usize = 256 * 1024;
/// A finished terminal stays readable this long.
const LINGER: Duration = Duration::from_secs(10 * 60);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Step {
    Install,
    Login,
}

enum Input {
    Data(Vec<u8>),
    Resize(u16, u16),
    Kill,
}

struct Output {
    scrollback: Vec<u8>,
    exit: Option<i32>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum TermKey {
    Setup {
        backend: String,
        step: Step,
    },
    /// Interactive shell; `cwd` is the canonical path shown in the banner.
    Shell {
        cwd: String,
    },
}

pub struct Term {
    key: TermKey,
    input: mpsc::UnboundedSender<Input>,
    output: Mutex<Output>,
    /// `{"type":"output","data":<base64>}` and `{"type":"exit","code":n}`.
    events: broadcast::Sender<Value>,
}

impl Term {
    /// The scrollback so far plus a live feed, taken atomically so nothing is lost or doubled.
    pub fn attach(&self) -> (Vec<Value>, broadcast::Receiver<Value>) {
        let out = self.output.locked();
        let rx = self.events.subscribe();
        let mut head = vec![];
        if !out.scrollback.is_empty() {
            head.push(json!({"type": "output", "data": B64.encode(&out.scrollback)}));
        }
        if let Some(code) = out.exit {
            head.push(json!({"type": "exit", "code": code}));
        }
        (head, rx)
    }

    fn push(&self, bytes: &[u8]) {
        let mut out = self.output.locked();
        out.scrollback.extend_from_slice(bytes);
        if out.scrollback.len() > SCROLLBACK {
            let cut = out.scrollback.len() - SCROLLBACK;
            out.scrollback.drain(..cut);
        }
        let _ = self.events.send(json!({"type": "output", "data": B64.encode(bytes)}));
    }

    fn finish(&self, code: i32) {
        // An install may have added a folder to PATH; pick it up for the next detection.
        std::thread::spawn(backends::hydrate_path);
        self.output.locked().exit = Some(code);
        let _ = self.events.send(json!({"type": "exit", "code": code}));
    }
}

#[derive(Default)]
pub struct Terms(Mutex<HashMap<String, Arc<Term>>>);

impl Terms {
    pub fn busy(&self) -> bool {
        self.0.locked().values().any(|term| term.output.locked().exit.is_none())
    }

    pub fn get(&self, id: &str) -> Option<Arc<Term>> {
        self.0.locked().get(id).cloned()
    }

    /// Starts `step` for `backend` (a sign-in `method` the agent advertised, or
    /// Hypurr's own command for it), or returns the one already running.
    pub async fn start(
        self: &Arc<Self>,
        backend: &str,
        step: Step,
        method: Option<&str>,
        cols: u16,
        rows: u16,
    ) -> Result<String> {
        let h = backends::harness(backend);
        let name = h.map_or(backend, |h| h.name);
        if step == Step::Install && matches!(h.and_then(|h| h.install), Some(backends::Install::Managed)) {
            return Ok(self.spawn_managed(backend));
        }
        let key = TermKey::Setup { backend: backend.to_owned(), step };
        let command = match step {
            Step::Install => {
                let h = h.ok_or_else(|| anyhow!("Hypurr downloads {name} by itself"))?;
                let c = h
                    .install
                    .ok_or_else(|| anyhow!("{}", h.setup))?
                    .command(h.id)
                    .map_err(|e| anyhow!("{name}: {e}"))?;
                return self.spawn(
                    &key,
                    &SpawnKind::Command { text: c.text, powershell: c.powershell, cwd: None },
                    cols,
                    rows,
                );
            }
            // Some CLIs (codex) delete the current credentials the moment a new sign-in starts.
            Step::Login if backends::signed_in(backend) == Some(true) => bail!("{name} is already signed in"),
            Step::Login => match method {
                Some(m) => crate::agent::auth::terminal_command(backend, m)
                    .ok_or_else(|| anyhow!("That sign-in option is gone; check {name} again"))?,
                None => backends::login_command(backend).await?,
            },
        };
        self.spawn(&key, &SpawnKind::Command { text: command, powershell: false, cwd: None }, cols, rows)
    }

    /// Interactive shell in a scoped project/task folder. Never takes a command string from the client.
    pub fn open_shell(
        self: &Arc<Self>,
        store: &crate::store::Store,
        cwd: &str,
        cols: u16,
        rows: u16,
    ) -> Result<String> {
        let cwd = resolve_shell_cwd(store, Path::new(cwd))?;
        let key = TermKey::Shell { cwd: cwd.display().to_string() };
        self.spawn(&key, &SpawnKind::Interactive { cwd }, cols, rows)
    }

    /// Streams `builtin::install` progress into a setup terminal (no PTY).
    fn spawn_managed(self: &Arc<Self>, backend: &str) -> String {
        let key = TermKey::Setup { backend: backend.to_owned(), step: Step::Install };
        {
            let map = self.0.locked();
            if let Some((id, _)) = map.iter().find(|(_, t)| t.key == key && t.output.locked().exit.is_none()) {
                return id.clone();
            }
        }
        let (tx, _rx) = mpsc::unbounded_channel();
        let term = Arc::new(Term {
            key,
            input: tx,
            output: Mutex::new(Output { scrollback: vec![], exit: None }),
            events: broadcast::channel(256).0,
        });
        let id = uuid::Uuid::new_v4().to_string();
        self.0.locked().insert(id.clone(), term.clone());
        let terms = Arc::clone(self);
        let tid = id.clone();
        tokio::spawn(async move {
            let progress = {
                let term = Arc::clone(&term);
                move |line: &str| {
                    term.push(
                        format!(
                            "{line}
"
                        )
                        .as_bytes(),
                    );
                }
            };
            let code = match crate::agent::builtin::install(progress).await {
                Ok(_) => 0,
                Err(e) => {
                    term.push(
                        format!(
                            "Error: {e:#}
"
                        )
                        .as_bytes(),
                    );
                    1
                }
            };
            term.finish(code);
            tokio::time::sleep(LINGER).await;
            terms.0.locked().remove(&tid);
        });
        id
    }

    fn spawn(self: &Arc<Self>, key: &TermKey, kind: &SpawnKind, cols: u16, rows: u16) -> Result<String> {
        let mut map = self.0.locked();
        // Setup steps reuse an in-flight terminal; each shell open is a fresh PTY.
        if matches!(key, TermKey::Setup { .. })
            && let Some((id, _)) = map.iter().find(|(_, t)| t.key == *key && t.output.locked().exit.is_none())
        {
            return Ok(id.clone());
        }
        let pair = native_pty_system().openpty(size(cols, rows))?;
        let (banner, mut cmd) = match kind {
            SpawnKind::Command { text, powershell, cwd } => {
                // `NAME=value cmd …` is Unix shell syntax; set those through the process environment
                // instead so the same command works under cmd.exe and PowerShell on Windows.
                let (env, rest) = split_env(text);
                let mut cmd = shell_command(rest, *powershell);
                for (k, v) in env {
                    cmd.env(k, v);
                }
                cmd.cwd(cwd.clone().unwrap_or_else(|| dirs::home_dir().unwrap_or_else(|| "/".into())));
                (format!("\x1b[2m$ {text}\x1b[0m\r\n"), cmd)
            }
            SpawnKind::Interactive { cwd } => {
                let mut cmd = interactive_shell();
                cmd.cwd(cwd);
                (format!("\x1b[2mHypurr shell · {}\x1b[0m\r\n", cwd.display()), cmd)
            }
        };
        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        let child = pair.slave.spawn_command(cmd)?;
        // The child holds its own copy; keeping ours would keep the reader open after exit.
        drop(pair.slave);
        let pty = pair.master;
        let (tx, rx) = mpsc::unbounded_channel();
        let term = Arc::new(Term {
            key: key.clone(),
            input: tx,
            output: Mutex::new(Output { scrollback: vec![], exit: None }),
            events: broadcast::channel(256).0,
        });
        term.push(banner.as_bytes());
        let id = uuid::Uuid::new_v4().to_string();
        map.insert(id.clone(), term.clone());
        match &key {
            TermKey::Setup { backend, step } => {
                tracing::info!(%backend, ?step, term = %id, "setup terminal started");
            }
            TermKey::Shell { cwd } => {
                tracing::info!(%cwd, term = %id, "project shell started");
            }
        }
        tokio::spawn(run(self.clone(), id.clone(), term, pty, child, rx));
        Ok(id)
    }

    pub fn write(&self, id: &str, data: &str) -> Result<()> {
        let bytes = B64.decode(data)?;
        self.send(id, Input::Data(bytes))
    }

    pub fn resize(&self, id: &str, cols: u16, rows: u16) -> Result<()> {
        self.send(id, Input::Resize(cols, rows))
    }

    pub fn close(&self, id: &str) {
        if let Some(t) = self.0.locked().remove(id) {
            let _ = t.input.send(Input::Kill);
        }
    }

    fn send(&self, id: &str, input: Input) -> Result<()> {
        let t = self.get(id).ok_or_else(|| anyhow!("that terminal is gone"))?;
        // Input after exit has nowhere to go; not an error for the typist.
        let _ = t.input.send(input);
        Ok(())
    }
}

enum SpawnKind {
    /// One-shot host-chosen setup command (install / login).
    Command { text: String, powershell: bool, cwd: Option<PathBuf> },
    /// Interactive login shell in a scoped folder.
    Interactive { cwd: PathBuf },
}

fn interactive_shell() -> CommandBuilder {
    if cfg!(windows) {
        let mut c = CommandBuilder::new("powershell.exe");
        c.arg("-NoLogo");
        c
    } else {
        let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/bash".into());
        CommandBuilder::new(shell)
    }
}

/// Folders a phone shell may start in: saved projects, bot working folders, and task worktrees.
fn shell_roots(store: &crate::store::Store) -> Vec<PathBuf> {
    let mut roots = Vec::new();
    let push = |roots: &mut Vec<PathBuf>, p: &Path| {
        if let Ok(c) = p.canonicalize()
            && c.is_dir()
            && !roots.iter().any(|r| r == &c)
        {
            roots.push(c);
        }
    };
    for p in crate::tasks::projects(store) {
        push(&mut roots, Path::new(&p.path));
    }
    if let Ok(bots) = store.bots() {
        for row in bots {
            if row.deleted || row.config.is_group() || row.config.cwd.is_empty() {
                continue;
            }
            push(&mut roots, Path::new(&row.config.cwd));
        }
    }
    if let Ok(tasks) = store.tasks_data(500) {
        for t in tasks {
            if let Some(p) = t.get("worktree").and_then(|v| v.as_str()) {
                push(&mut roots, Path::new(p));
            }
            if let Some(p) = t.get("project").and_then(|v| v.as_str()) {
                push(&mut roots, Path::new(p));
            }
        }
    }
    roots
}

/// Canonical cwd under a known project root (and under `allowedFolders` when set).
pub fn resolve_shell_cwd(store: &crate::store::Store, cwd: &Path) -> Result<PathBuf> {
    if cwd.as_os_str().is_empty() {
        bail!("Pick a project folder for the terminal.");
    }
    let canon = cwd.canonicalize().map_err(|_| anyhow!("That folder isn't on this computer."))?;
    if !canon.is_dir() {
        bail!("That path isn't a folder.");
    }
    let roots = shell_roots(store);
    if roots.is_empty() {
        bail!("Add a project folder first, then open a terminal there.");
    }
    if !roots.iter().any(|r| canon == *r || canon.starts_with(r)) {
        bail!(
            "Terminals only open in a project Hypurr already knows (a saved project, a bot's folder, or a task worktree)."
        );
    }
    let policies = crate::admin::Policies::load(store);
    if !policies.allowed_folders.is_empty() {
        let allowed: Vec<PathBuf> =
            policies.allowed_folders.iter().filter_map(|p| Path::new(p).canonicalize().ok()).collect();
        if !allowed.iter().any(|r| canon == *r || canon.starts_with(r)) {
            bail!("Your team's folder rules don't allow a terminal there.");
        }
    }
    Ok(canon)
}

/// Leading `NAME=value` assignments of a setup command, and the command after them.
fn split_env(command: &str) -> (Vec<(&str, &str)>, &str) {
    let mut env = Vec::new();
    let mut rest = command.trim_start();
    while let Some((word, tail)) = rest.split_once(char::is_whitespace) {
        let Some((k, v)) = word.split_once('=') else { break };
        let name_ok = !k.is_empty()
            && k.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_')
            && !k.starts_with(|c: char| c.is_ascii_digit());
        if !name_ok || v.contains(['"', '\'', '$', '`']) {
            break;
        }
        env.push((k, v));
        rest = tail.trim_start();
    }
    (env, rest)
}

fn size(cols: u16, rows: u16) -> PtySize {
    PtySize { rows: rows.max(4), cols: cols.max(20), pixel_width: 0, pixel_height: 0 }
}

/// The platform shell running a host-chosen setup command. Windows installs are PowerShell
/// scripts (`irm … | iex`), run without the user's profile and with this run's policy bypassed.
fn shell_command(command: &str, powershell: bool) -> CommandBuilder {
    if powershell {
        let mut c = CommandBuilder::new(if cfg!(windows) { "powershell.exe" } else { "pwsh" });
        c.args(["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command]);
        c
    } else if cfg!(windows) {
        let mut c = CommandBuilder::new("cmd.exe");
        c.args(["/D", "/S", "/C", command]);
        c
    } else {
        let mut c = CommandBuilder::new("/bin/sh");
        c.args(["-c", command]);
        c
    }
}

async fn run(
    terms: Arc<Terms>,
    id: String,
    term: Arc<Term>,
    pty: Box<dyn MasterPty + Send>,
    mut child: Box<dyn Child + Send + Sync>,
    mut rx: mpsc::UnboundedReceiver<Input>,
) {
    let (reader, writer) = (pty.try_clone_reader(), pty.take_writer());
    let (Ok(mut reader), Ok(mut writer)) = (reader, writer) else {
        let _ = child.kill();
        term.finish(-1);
        terms.0.locked().remove(&id);
        return;
    };
    let pump = {
        let term = term.clone();
        tokio::task::spawn_blocking(move || {
            let mut buf = vec![0u8; 16 * 1024];
            let mut answered = !cfg!(windows);
            // EOF or EIO once the child and its children are gone.
            while let Ok(n @ 1..) = reader.read(&mut buf) {
                // ConPTY asks where the cursor is (`ESC[6n`) and starts nothing until it hears
                // back. Answer once ourselves so a command runs even before a client attaches.
                if !answered && buf[..n].windows(4).any(|w| w == b"\x1b[6n") {
                    answered = true;
                    let _ = term.input.send(Input::Data(b"\x1b[1;1R".to_vec()));
                }
                term.push(&buf[..n]);
            }
        })
    };
    let mut killer = child.clone_killer();
    let mut wait = tokio::task::spawn_blocking(move || child.wait());
    let status = loop {
        tokio::select! {
            status = &mut wait => break status.ok().and_then(Result::ok),
            input = rx.recv() => match input {
                Some(Input::Data(bytes)) => {
                    if let Err(error) = writer.write_all(&bytes).and_then(|()| writer.flush()) {
                        tracing::info!(%error, "terminal write failed");
                    }
                }
                Some(Input::Resize(cols, rows)) => {
                    let _ = pty.resize(size(cols, rows));
                }
                Some(Input::Kill) | None => {
                    let _ = killer.kill();
                }
            },
        }
    };
    // ConPTY keeps the reader open until the pseudo console is closed, and closing it can
    // block while output is pending, so close it off this task and give the reader a moment.
    let _close = tokio::task::spawn_blocking(move || {
        drop(writer);
        drop(pty);
    });
    let _ = tokio::time::timeout(Duration::from_secs(1), pump).await;
    let code = status.map_or(-1, |s| i32::try_from(s.exit_code()).unwrap_or(-1));
    tracing::info!(term = %id, code, "terminal finished");
    term.finish(code);
    tokio::time::sleep(LINGER).await;
    terms.0.locked().remove(&id);
}

#[cfg(test)]
mod tests {

    #[test]
    fn unix_env_prefixes_become_process_env() {
        assert_eq!(
            split_env("NO_OPEN_BROWSER=1 C:\\a\\agent.exe login"),
            (vec![("NO_OPEN_BROWSER", "1")], "C:\\a\\agent.exe login")
        );
        assert_eq!(split_env("NO_BROWSER=true gemini"), (vec![("NO_BROWSER", "true")], "gemini"));
        assert_eq!(split_env("A=1 B_2=x run --x=y"), (vec![("A", "1"), ("B_2", "x")], "run --x=y"));
        assert_eq!(split_env("codex login"), (vec![], "codex login"));
        assert_eq!(split_env("irm https://x/install.ps1 | iex"), (vec![], "irm https://x/install.ps1 | iex"));
        assert_eq!(split_env("X=$(evil) y"), (vec![], "X=$(evil) y"));
        // Every agent's sign-in: nothing Unix-only left once the env is split off.
        for h in backends::HARNESSES {
            let (_, rest) = split_env(h.login);
            assert!(!rest.split_whitespace().next().unwrap_or("").contains('='), "{}: {}", h.id, h.login);
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn env_prefix_reaches_the_process() {
        let terms = Arc::new(Terms(Mutex::new(HashMap::new())));
        let id = terms
            .spawn(
                &TermKey::Setup { backend: "test".into(), step: Step::Login },
                &SpawnKind::Command {
                    text: "HYPURR_T=ok sh -c 'echo got-$HYPURR_T'".into(),
                    powershell: false,
                    cwd: None,
                },
                80,
                24,
            )
            .unwrap();
        let mut out = String::new();
        for _ in 0..50 {
            tokio::time::sleep(Duration::from_millis(100)).await;
            out = terms
                .get(&id)
                .map(|t| String::from_utf8_lossy(&t.output.locked().scrollback).into_owned())
                .unwrap_or_default();
            if out.contains("got-ok") {
                break;
            }
        }
        assert!(out.contains("got-ok"), "{out}");
    }
    use super::*;

    #[cfg(unix)]
    #[tokio::test]
    async fn runs_in_a_pty_and_takes_input() {
        let terms = Arc::new(Terms::default());
        let id = terms
            .spawn(
                &TermKey::Setup { backend: "test".into(), step: Step::Login },
                &SpawnKind::Command {
                    text: "[ -t 0 ] && echo tty; read x; echo got-$x".into(),
                    powershell: false,
                    cwd: None,
                },
                80,
                24,
            )
            .unwrap();
        let term = terms.get(&id).unwrap();
        let (_, mut rx) = term.attach();
        terms.write(&id, &B64.encode("hi\n")).unwrap();
        let code = tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let v = rx.recv().await.unwrap();
                if v["type"] == "exit" {
                    return v["code"].as_i64().unwrap();
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(code, 0);
        let (head, _) = term.attach();
        let text = String::from_utf8(B64.decode(head[0]["data"].as_str().unwrap()).unwrap()).unwrap();
        assert!(text.contains("tty") && text.contains("got-hi"), "{text}");
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn runs_in_conpty() {
        let terms = Arc::new(Terms::default());
        let id = terms
            .spawn(
                &TermKey::Setup { backend: "test".into(), step: Step::Login },
                &SpawnKind::Command { text: "echo got-hi".into(), powershell: false, cwd: None },
                80,
                24,
            )
            .unwrap();
        let term = terms.get(&id).unwrap();
        // It can finish before anyone attaches, so poll instead of waiting for the event.
        let code = tokio::time::timeout(Duration::from_secs(20), async {
            loop {
                if let Some(c) = term.output.locked().exit {
                    return i64::from(c);
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .unwrap_or_else(|_| {
            let out = String::from_utf8_lossy(&term.output.locked().scrollback).into_owned();
            panic!("no exit after 20s; output so far: {out:?}")
        });
        assert_eq!(code, 0);
        let (head, _) = term.attach();
        let text: String = head
            .iter()
            .filter_map(|v| v["data"].as_str())
            .map(|d| String::from_utf8_lossy(&B64.decode(d).unwrap()).into_owned())
            .collect();
        assert!(text.contains("got-hi"), "{text}");
    }

    #[test]
    fn shell_cwd_must_be_under_a_known_project() {
        let dir = std::env::temp_dir().join(format!("hypurr-shell-scope-{}", uuid::Uuid::new_v4()));
        let project = dir.join("app");
        std::fs::create_dir_all(&project).unwrap();
        let outside = dir.join("other");
        std::fs::create_dir_all(&outside).unwrap();
        let store = crate::store::Store::open(&dir.join("db.sqlite")).unwrap();
        crate::tasks::save_project(&store, &serde_json::json!({"path": project.to_string_lossy(), "name": "app"}))
            .unwrap();
        let ok = resolve_shell_cwd(&store, &project).unwrap();
        assert_eq!(ok, project.canonicalize().unwrap());
        let err = resolve_shell_cwd(&store, &outside).unwrap_err().to_string();
        assert!(err.contains("project"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn open_shell_runs_interactive_pwd() {
        let dir = std::env::temp_dir().join(format!("hypurr-shell-pwd-{}", uuid::Uuid::new_v4()));
        let project = dir.join("proj");
        std::fs::create_dir_all(&project).unwrap();
        let store = crate::store::Store::open(&dir.join("db.sqlite")).unwrap();
        crate::tasks::save_project(&store, &serde_json::json!({"path": project.to_string_lossy(), "name": "proj"}))
            .unwrap();
        let terms = Arc::new(Terms::default());
        let id = terms.open_shell(&store, &project.to_string_lossy(), 80, 24).unwrap();
        let term = terms.get(&id).unwrap();
        let (_, mut rx) = term.attach();
        // Benign fixture only: print cwd then exit. No secrets, no real user projects.
        terms.write(&id, &B64.encode("pwd; exit\n")).unwrap();
        let code = tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let v = rx.recv().await.unwrap();
                if v["type"] == "exit" {
                    return v["code"].as_i64().unwrap();
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(code, 0);
        let (head, _) = term.attach();
        let text: String = head
            .iter()
            .filter_map(|v| v["data"].as_str())
            .map(|d| String::from_utf8_lossy(&B64.decode(d).unwrap()).into_owned())
            .collect();
        let expect = project.canonicalize().unwrap();
        assert!(text.contains(&*expect.to_string_lossy()), "{text}");
        assert!(text.contains("Hypurr shell"), "{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn open_shell_benign_commands_in_temp_dir() {
        let dir = std::env::temp_dir().join(format!("hypurr-shell-benign-{}", uuid::Uuid::new_v4()));
        let project = dir.join("proj");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(project.join("hello.txt"), b"hi").unwrap();
        let store = crate::store::Store::open(&dir.join("db.sqlite")).unwrap();
        crate::tasks::save_project(&store, &serde_json::json!({"path": project.to_string_lossy(), "name": "proj"}))
            .unwrap();
        let terms = Arc::new(Terms::default());
        let id = terms.open_shell(&store, &project.to_string_lossy(), 80, 24).unwrap();
        let term = terms.get(&id).unwrap();
        let (_, mut rx) = term.attach();
        // Isolated temp dir only. Benign builtins — no network, no secrets, no user paths.
        terms.write(&id, &B64.encode("pwd; echo hypurr-ok; ls; exit\n")).unwrap();
        let code = tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let v = rx.recv().await.unwrap();
                if v["type"] == "exit" {
                    return v["code"].as_i64().unwrap();
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(code, 0);
        let (head, _) = term.attach();
        let text: String = head
            .iter()
            .filter_map(|v| v["data"].as_str())
            .map(|d| String::from_utf8_lossy(&B64.decode(d).unwrap()).into_owned())
            .collect();
        assert!(text.contains("hypurr-ok"), "{text}");
        assert!(text.contains("hello.txt"), "{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
