//! Small helpers: truncation per the schema limits, time conversions.

use chrono::{DateTime, TimeZone, Utc};
use serde_json::Value;

/// Tool input strings longer than this are truncated (schema: EventPayloadToolCall.input).
pub const TOOL_INPUT_STRING_MAX: usize = 4 * 1024;
/// Tool output text limit (schema: EventPayloadToolCallUpdate.output).
pub const TOOL_OUTPUT_MAX: usize = 16 * 1024;
/// Diff texts are capped like tool output so one edit cannot produce a huge frame.
pub const DIFF_TEXT_MAX: usize = 16 * 1024;
/// Summary limit (schema: RunFinished.summary).
pub const SUMMARY_MAX: usize = 64 * 1024;
/// A coalesced text event is flushed early when it reaches this size.
pub const TEXT_EVENT_MAX: usize = 32 * 1024;
/// LOG message limit.
pub const LOG_MESSAGE_MAX: usize = 4 * 1024;
/// Serialized size limit of one event payload; the server closes frames over 1 MiB.
pub const EVENT_PAYLOAD_MAX: usize = 256 * 1024;

/// Largest char boundary `<= max`.
pub fn floor_char_boundary(s: &str, max: usize) -> usize {
    if max >= s.len() {
        return s.len();
    }
    let mut i = max;
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    i
}

/// Truncates to at most `max` bytes of content plus a trailing marker that says how much
/// was cut.
pub fn truncate_with_marker(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_string();
    }
    let cut = floor_char_boundary(s, max);
    format!("{}{}", &s[..cut], truncation_marker(s.len() - cut))
}

/// The marker that ends a text cut short, naming the bytes that are gone.
pub fn truncation_marker(removed: usize) -> String {
    format!("\n…[truncated {removed} bytes]")
}

/// Truncates every string inside a JSON value that is longer than `max` bytes.
pub fn truncate_json_strings(value: &mut Value, max: usize) {
    match value {
        Value::String(s) if s.len() > max => {
            let cut = floor_char_boundary(s, max);
            let removed = s.len() - cut;
            s.truncate(cut);
            s.push_str(&format!("…[truncated {removed} bytes]"));
        }
        Value::Array(items) => items.iter_mut().for_each(|v| truncate_json_strings(v, max)),
        Value::Object(map) => map.values_mut().for_each(|v| truncate_json_strings(v, max)),
        _ => {}
    }
}

/// Shrinks a JSON value until its serialized form fits `max_bytes`, by truncating strings
/// and arrays in steps. The structure (keys, types) is kept, so a schema-valid payload stays
/// valid. Returns `false` if it still does not fit.
pub fn shrink_json(value: &mut Value, max_bytes: usize) -> bool {
    let size = |v: &Value| {
        serde_json::to_string(v)
            .map(|s| s.len())
            .unwrap_or(usize::MAX)
    };
    if size(value) <= max_bytes {
        return true;
    }
    for (max_str, max_items) in [(4096, 200), (1024, 50), (256, 20), (64, 5)] {
        truncate_json_strings(value, max_str);
        truncate_json_arrays(value, max_items);
        if size(value) <= max_bytes {
            return true;
        }
    }
    false
}

fn truncate_json_arrays(value: &mut Value, max_items: usize) {
    match value {
        Value::Array(items) => {
            items.truncate(max_items);
            items
                .iter_mut()
                .for_each(|v| truncate_json_arrays(v, max_items));
        }
        Value::Object(map) => map
            .values_mut()
            .for_each(|v| truncate_json_arrays(v, max_items)),
        _ => {}
    }
}

/// Bytes of the files under `path`, without following symlinks; 0 when it does not exist.
pub fn dir_size(path: &std::path::Path) -> u64 {
    let mut total = 0u64;
    let mut stack = vec![path.to_path_buf()];
    while let Some(next) = stack.pop() {
        let Ok(meta) = std::fs::symlink_metadata(&next) else {
            continue;
        };
        if meta.is_dir() {
            if let Ok(entries) = std::fs::read_dir(&next) {
                stack.extend(entries.filter_map(|e| e.ok()).map(|e| e.path()));
            }
        } else {
            total = total.saturating_add(meta.len());
        }
    }
    total
}

/// `12.3 MiB`: a byte count for people.
pub fn format_bytes(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    if bytes < 1024 {
        return format!("{bytes} B");
    }
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    format!("{value:.1} {}", UNITS[unit])
}

/// A byte count with an optional `K`, `M`, `G` or `T` suffix (powers of 1024; `KiB`, `KB`
/// and `B` are accepted too), e.g. `50G`. `None` when it is not one.
pub fn parse_bytes(value: &str) -> Option<u64> {
    let value = value.trim();
    let split = value
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(value.len());
    let (number, unit) = value.split_at(split);
    let number: u64 = number.parse().ok()?;
    let multiplier: u64 = match unit.trim().to_ascii_uppercase().as_str() {
        "" | "B" => 1,
        "K" | "KB" | "KIB" => 1 << 10,
        "M" | "MB" | "MIB" => 1 << 20,
        "G" | "GB" | "GIB" => 1 << 30,
        "T" | "TB" | "TIB" => 1 << 40,
        _ => return None,
    };
    number.checked_mul(multiplier)
}

/// Unix seconds (as sent by the Claude SDK) to an instant.
pub fn unix_seconds(value: &Value) -> Option<DateTime<Utc>> {
    let secs = value.as_f64()?;
    if !secs.is_finite() || secs <= 0.0 {
        return None;
    }
    // Milliseconds are sometimes sent instead of seconds; anything past year 5000 in
    // seconds is taken as milliseconds.
    let secs = if secs > 95_617_584_000.0 {
        secs / 1000.0
    } else {
        secs
    };
    Utc.timestamp_opt(secs as i64, 0).single()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncates_on_char_boundaries() {
        let s = "ééé"; // 6 bytes
        assert_eq!(floor_char_boundary(s, 3), 2);
        let t = truncate_with_marker(s, 3);
        assert!(t.starts_with('é'));
        assert!(t.contains("truncated 4 bytes"));
        assert_eq!(truncate_with_marker("abc", 3), "abc");
    }

    #[test]
    fn truncates_json_strings() {
        let mut v = serde_json::json!({"a": "x".repeat(10), "b": ["y".repeat(3)]});
        truncate_json_strings(&mut v, 5);
        assert!(
            v["a"]
                .as_str()
                .unwrap()
                .starts_with("xxxxx…[truncated 5 bytes]")
        );
        assert_eq!(v["b"][0], "yyy");
    }

    #[test]
    fn shrinks_large_json() {
        let big = serde_json::json!({
            "entries": (0..5000).map(|i| serde_json::json!({"content": "x".repeat(300), "status": "pending", "n": i})).collect::<Vec<_>>()
        });
        let mut v = big.clone();
        assert!(shrink_json(&mut v, 64 * 1024));
        assert!(serde_json::to_string(&v).unwrap().len() <= 64 * 1024);
        assert_eq!(v["entries"][0]["status"], "pending");
        let mut small = serde_json::json!({"a": "b"});
        assert!(shrink_json(&mut small, 100));
        assert_eq!(small, serde_json::json!({"a": "b"}));
    }

    #[test]
    fn formats_and_parses_byte_counts() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(1023), "1023 B");
        assert_eq!(format_bytes(1536), "1.5 KiB");
        assert_eq!(format_bytes(5 * 1024 * 1024), "5.0 MiB");
        assert_eq!(format_bytes(3 << 30), "3.0 GiB");
        assert_eq!(parse_bytes("123"), Some(123));
        assert_eq!(parse_bytes(" 50G "), Some(50 << 30));
        assert_eq!(parse_bytes("2 GiB"), Some(2 << 30));
        assert_eq!(parse_bytes("10m"), Some(10 << 20));
        assert_eq!(parse_bytes("1TB"), Some(1 << 40));
        assert_eq!(parse_bytes("7KiB"), Some(7 << 10));
        for bad in ["", "G", "-1", "1.5G", "10X", "99999999999999999999"] {
            assert_eq!(parse_bytes(bad), None, "{bad}");
        }
    }

    #[test]
    fn sums_file_sizes_without_following_symlinks() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(tmp.path().join("a/b")).unwrap();
        std::fs::write(tmp.path().join("a/one"), [0u8; 10]).unwrap();
        std::fs::write(tmp.path().join("a/b/two"), [0u8; 32]).unwrap();
        let outside = tempfile::tempdir().unwrap();
        std::fs::write(outside.path().join("big"), [0u8; 4096]).unwrap();
        std::os::unix::fs::symlink(outside.path(), tmp.path().join("a/link")).unwrap();
        let link_len = std::fs::symlink_metadata(tmp.path().join("a/link"))
            .unwrap()
            .len();
        assert_eq!(dir_size(tmp.path()), 42 + link_len);
        assert_eq!(dir_size(&tmp.path().join("missing")), 0);
    }

    #[test]
    fn converts_unix_seconds() {
        let t = unix_seconds(&serde_json::json!(1790000000)).unwrap();
        assert_eq!(t.timestamp(), 1790000000);
        let t = unix_seconds(&serde_json::json!(1790000000000u64)).unwrap();
        assert_eq!(t.timestamp(), 1790000000);
        assert!(unix_seconds(&serde_json::json!("x")).is_none());
    }
}
