use serde::Serialize;

/// Error returned across the bridge. `code` is stable and machine readable:
/// `offline`, `network`, `http`, `not_found`, `not_cached`, `parse`,
/// `invalid`, `internal`, `panic`.
#[derive(Debug, Clone, Serialize)]
pub struct CoreError {
    pub code: String,
    pub message: String,
}

impl CoreError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        CoreError {
            code: code.to_string(),
            message: message.into(),
        }
    }

    pub fn invalid(message: impl Into<String>) -> Self {
        Self::new("invalid", message)
    }

    /// True when a cached copy is an acceptable substitute.
    pub fn is_connectivity(&self) -> bool {
        matches!(self.code.as_str(), "offline" | "network" | "http")
    }
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::new("internal", format!("storage error: {e}"))
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::new("parse", format!("data error: {e}"))
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;
