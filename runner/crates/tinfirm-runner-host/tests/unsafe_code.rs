//! The workspace denies `unsafe` code (`runner/Cargo.toml`, `[workspace.lints.rust]`), and a
//! function that needs it carries an attribute that allows it there. This test keeps those
//! attributes to the functions that `runner/Cargo.toml` names: a new exception is a decision,
//! not a line that slips in with a change.

use std::path::{Path, PathBuf};

/// The files that may name the lint, and how often: one attribute per excepted function.
const EXCEPTIONS: &[(&str, usize)] = &[("ee/runner/tinfirm-runner-host/src/kubernetes/tap.rs", 2)];

/// The lint's name, put together here so that this file does not name it.
const LINT: &str = concat!("unsafe", "_code");

fn rust_files(dir: &Path, found: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            if path.file_name().is_some_and(|name| name != "target") {
                rust_files(&path, found);
            }
        } else if path.extension().is_some_and(|extension| extension == "rs") {
            found.push(path);
        }
    }
}

#[test]
fn only_the_functions_that_cargo_toml_names_are_excepted() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .unwrap();
    let mut files = Vec::new();
    for dir in ["runner/crates", "ee/runner"] {
        rust_files(&root.join(dir), &mut files);
    }
    assert!(files.len() > 50, "the sources were not found: {files:?}");
    let mut found: Vec<(String, usize)> = files
        .iter()
        .filter_map(|file| {
            let count = std::fs::read_to_string(file).unwrap().matches(LINT).count();
            let name = file.strip_prefix(&root).unwrap().to_str().unwrap();
            (count > 0).then(|| (name.replace('\\', "/"), count))
        })
        .collect();
    found.sort();
    let expected: Vec<(String, usize)> = EXCEPTIONS
        .iter()
        .map(|(file, count)| (file.to_string(), *count))
        .collect();
    assert_eq!(
        found, expected,
        "a file allows unsafe code that runner/Cargo.toml does not name, or a named one no longer does"
    );
}
