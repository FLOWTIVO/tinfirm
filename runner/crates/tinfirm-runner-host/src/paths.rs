//! Config and data directories (docs/archive/v1/CONVENTIONS.md §15.2).
//!
//! - Config: `$XDG_CONFIG_HOME/tinfirm-runner`, default `~/.config/tinfirm-runner`.
//!   Server URL, host token and credential secrets, as 0600 files in a 0700 directory.
//! - Data: `$XDG_DATA_HOME/tinfirm-runner`, default `~/.local/share/tinfirm-runner`.
//!   Workspaces, the runs' `/output` folders, repository mirrors, downloaded task
//!   attachments, session homes, the cache of skills and Codex homes.
//!
//! The XDG layout is used on macOS too (not `~/Library`), so the paths are the same
//! everywhere and match the documentation.

use std::path::PathBuf;

use anyhow::{Context, Result};
use uuid::Uuid;

const APP_DIR: &str = "tinfirm-runner";

#[derive(Debug, Clone)]
pub struct Paths {
    pub config_dir: PathBuf,
    pub data_dir: PathBuf,
}

impl Paths {
    /// Resolves the directories from the XDG variables and the home directory.
    pub fn from_env() -> Result<Paths> {
        let home = dirs::home_dir().context("cannot determine the home directory")?;
        let config_base = xdg_dir("XDG_CONFIG_HOME").unwrap_or_else(|| home.join(".config"));
        let data_base = xdg_dir("XDG_DATA_HOME").unwrap_or_else(|| home.join(".local/share"));
        Ok(Paths {
            config_dir: config_base.join(APP_DIR),
            data_dir: data_base.join(APP_DIR),
        })
    }

    /// Explicit directories (tests, `--config-dir`/`--data-dir`).
    pub fn new(config_dir: impl Into<PathBuf>, data_dir: impl Into<PathBuf>) -> Paths {
        Paths {
            config_dir: config_dir.into(),
            data_dir: data_dir.into(),
        }
    }

    pub fn config_file(&self) -> PathBuf {
        self.config_dir.join("config.json")
    }

    /// Held by the `run` process of this config directory, which writes its PID into it.
    pub fn run_lock_file(&self) -> PathBuf {
        self.config_dir.join("run.lock")
    }

    pub fn state_file(&self) -> PathBuf {
        self.config_dir.join("state.json")
    }

    /// The build arguments and runtimes of `images build`, for the image rebuilds of `run`
    /// (ADR-0062).
    pub fn images_file(&self) -> PathBuf {
        self.config_dir.join("images.json")
    }

    pub fn credentials_dir(&self) -> PathBuf {
        self.config_dir.join("credentials")
    }

    pub fn credential_file(&self, label: &str) -> PathBuf {
        self.credentials_dir().join(format!("{label}.json"))
    }

    pub fn workspaces_dir(&self) -> PathBuf {
        self.data_dir.join("workspaces")
    }

    /// The workspace of a run, mounted at `/workspace`. Each repository is a directory
    /// `<dir>` in it.
    pub fn workspace(&self, run_id: Uuid) -> PathBuf {
        self.workspaces_dir().join(run_id.to_string())
    }

    pub fn outputs_dir(&self) -> PathBuf {
        self.data_dir.join("outputs")
    }

    /// Protocol v2: the documents folder of a run, mounted writable at `/output`. Never
    /// inside the workspace or another directory the sandbox writes to: the host reads it
    /// back when the run ends ([`crate::output`]), and a path the agent could replace
    /// (`mv output away; ln -s / output`) would be read instead.
    pub fn output(&self, run_id: Uuid) -> PathBuf {
        self.outputs_dir().join(run_id.to_string())
    }

    pub fn attachments_dir(&self) -> PathBuf {
        self.data_dir.join("attachments")
    }

    /// Downloaded task attachments of a run, mounted read-only at `/attachments`.
    pub fn attachments(&self, run_id: Uuid) -> PathBuf {
        self.attachments_dir().join(run_id.to_string())
    }

    /// Bare mirrors of the task repositories, one per repository URL. Never mounted into a
    /// sandbox.
    pub fn mirrors_dir(&self) -> PathBuf {
        self.data_dir.join("mirrors")
    }

    pub fn homes_dir(&self) -> PathBuf {
        self.data_dir.join("homes")
    }

    /// Session home of an agent on a task, mounted at `/home/agent`.
    pub fn session_home(&self, agent_id: Uuid, task_id: Uuid) -> PathBuf {
        self.homes_dir()
            .join(agent_id.to_string())
            .join(task_id.to_string())
    }

    /// The cache of unpacked skills (ADR-0064), by hash and name.
    pub fn skills_dir(&self) -> PathBuf {
        self.data_dir.join("skills")
    }

    /// An unpacked skill: the folder mounted read-only at the runtime's skill directory.
    pub fn skill(&self, sha256: &str, name: &str) -> PathBuf {
        self.skills_dir().join(sha256).join(name)
    }

    pub fn codex_homes_dir(&self) -> PathBuf {
        self.data_dir.join("codex-homes")
    }

    /// Dedicated `CODEX_HOME` of a Codex ChatGPT credential. Never the user's `~/.codex`.
    pub fn codex_home(&self, label: &str) -> PathBuf {
        self.codex_homes_dir().join(label)
    }

    pub fn claude_logins_dir(&self) -> PathBuf {
        self.data_dir.join("claude-logins")
    }

    /// The `CLAUDE_CONFIG_DIR` a Claude login credential signed in to (ADR-0085): Claude Code's
    /// own `.credentials.json` lives there. Never the user's `~/.claude`.
    pub fn claude_login(&self, label: &str) -> PathBuf {
        self.claude_logins_dir().join(label)
    }
}

fn xdg_dir(var: &str) -> Option<PathBuf> {
    let value = std::env::var_os(var)?;
    let path = PathBuf::from(value);
    // The XDG spec says relative paths are invalid and must be ignored.
    path.is_absolute().then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_output_folder_is_not_inside_the_workspace() {
        let paths = Paths::new("/cfg", "/data");
        let id = Uuid::new_v4();
        assert_eq!(
            paths.output(id),
            PathBuf::from(format!("/data/outputs/{id}"))
        );
        assert!(!paths.output(id).starts_with(paths.workspace(id)));
        assert!(!paths.output(id).starts_with(paths.homes_dir()));
    }
}
