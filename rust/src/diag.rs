//! In-memory diagnostics shown under Developer options, mirrored to logcat.

use serde::Serialize;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

const CAPACITY: usize = 400;

#[derive(Debug, Clone, Serialize)]
pub struct LogEntry {
    /// Milliseconds since the Unix epoch.
    pub at: u64,
    /// `debug`, `info`, `warn` or `error`.
    pub level: String,
    pub tag: String,
    pub message: String,
}

#[derive(Default)]
pub struct Diag {
    entries: Mutex<VecDeque<LogEntry>>,
    pub verbose: AtomicBool,
    pub requests: AtomicU64,
    pub failures: AtomicU64,
    pub bytes: AtomicU64,
    pub cache_hits: AtomicU64,
    pub pages_parsed: AtomicU64,
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn now_secs() -> u64 {
    now_ms() / 1000
}

impl Diag {
    pub fn log(&self, level: &str, tag: &str, message: impl Into<String>) {
        if level == "debug" && !self.verbose.load(Ordering::Relaxed) {
            return;
        }
        let message = message.into();
        platform_log(level, tag, &message);
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if entries.len() >= CAPACITY {
            entries.pop_front();
        }
        entries.push_back(LogEntry {
            at: now_ms(),
            level: level.to_string(),
            tag: tag.to_string(),
            message,
        });
    }

    pub fn info(&self, tag: &str, message: impl Into<String>) {
        self.log("info", tag, message)
    }

    pub fn warn(&self, tag: &str, message: impl Into<String>) {
        self.log("warn", tag, message)
    }

    pub fn debug(&self, tag: &str, message: impl Into<String>) {
        self.log("debug", tag, message)
    }

    /// Newest first.
    pub fn entries(&self) -> Vec<LogEntry> {
        let entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.iter().rev().cloned().collect()
    }

    pub fn clear(&self) {
        self.entries.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}

#[cfg(target_os = "android")]
fn platform_log(level: &str, tag: &str, message: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    let prio = match level {
        "debug" => 3,
        "warn" => 5,
        "error" => 6,
        _ => 4,
    };
    let tag = CString::new(format!("MelloCore/{tag}")).unwrap_or_default();
    let text = CString::new(message.replace('\0', " ")).unwrap_or_default();
    // SAFETY: both pointers are valid NUL-terminated strings for the call.
    unsafe {
        __android_log_write(prio, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn platform_log(level: &str, tag: &str, message: &str) {
    if std::env::var_os("MZ_LOG").is_some() {
        eprintln!("[{level}] {tag}: {message}");
    }
}
