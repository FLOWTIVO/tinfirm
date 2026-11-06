//! The versions this binary reports (ADR-0109).
//!
//! - [`RUNNER_VERSION`]: the product version, the workspace's `version` in `runner/Cargo.toml`,
//!   which the release sets to the release's version (`set-version.sh`, `cargo:runner`) and the
//!   next-version commit to the development version after it (`1.0.1-SNAPSHOT`). It is what
//!   `hello.runnerVersion` says: always a semantic version. The server stores and shows it and
//!   decides nothing from it; compatibility is `hello.protocolVersion` and `hello.features`.
//! - [`BUILD`]: what the build was published as, when it says (`TINFIRM_RUNNER_BUILD` at build
//!   time; `deploy/release/assets.sh` sets it to the asset's version, `rolling-<sha7>` for the
//!   rolling prerelease). Only `--version` and the startup log show it, next to the version.

/// The product version of this binary: `hello.runnerVersion`.
pub const RUNNER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// The asset version this binary was built as (`TINFIRM_RUNNER_BUILD` at build time), if any.
pub const BUILD: Option<&str> = option_env!("TINFIRM_RUNNER_BUILD");

/// The version as `--version` and the startup log show it: `1.0.1-SNAPSHOT (rolling-abc1234)`
/// for a rolling build, the version alone for a release or a local build.
pub fn display() -> &'static str {
    static DISPLAY: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    DISPLAY.get_or_init(|| display_of(RUNNER_VERSION, BUILD))
}

/// [`display`] of a version and a build: the build in parentheses when it is set and says more
/// than the version (a release's asset version is the version itself).
pub fn display_of(version: &str, build: Option<&str>) -> String {
    match build
        .map(str::trim)
        .filter(|b| !b.is_empty() && *b != version)
    {
        Some(build) => format!("{version} ({build})"),
        None => version.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_runner_version_is_a_semantic_version() {
        let (core, _) = RUNNER_VERSION
            .split_once(['-', '+'])
            .unwrap_or((RUNNER_VERSION, ""));
        let parts: Vec<&str> = core.split('.').collect();
        assert_eq!(parts.len(), 3, "{RUNNER_VERSION}");
        assert!(
            parts
                .iter()
                .all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())),
            "{RUNNER_VERSION}"
        );
    }

    #[test]
    fn a_rolling_build_shows_its_asset_version_next_to_the_version() {
        assert_eq!(
            display_of("1.0.1-SNAPSHOT", Some("rolling-abc1234")),
            "1.0.1-SNAPSHOT (rolling-abc1234)"
        );
        // A release's asset version is its version: shown once.
        assert_eq!(display_of("1.0.0", Some("1.0.0")), "1.0.0");
        assert_eq!(display_of("1.0.0", None), "1.0.0");
        assert_eq!(display_of("1.0.0", Some(" ")), "1.0.0");
    }
}
