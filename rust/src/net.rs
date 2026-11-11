//! HTTPS client. All of the app's network traffic goes through here
//! (rustls with bundled web PKI roots; no system WebView or Java HTTP stack).

use crate::diag::Diag;
use crate::error::{CoreError, Result};
use std::io::Read;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAX_TEXT_BYTES: u64 = 8 * 1024 * 1024;

pub struct TextResponse {
    pub body: String,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub final_url: String,
}

pub enum Fetched {
    NotModified,
    Fresh(TextResponse),
}

pub struct Net {
    agent: ureq::Agent,
    diag: Arc<Diag>,
    pub simulate_offline: AtomicBool,
}

impl Net {
    pub fn new(diag: Arc<Diag>, user_agent: &str) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(10))
            .timeout_read(Duration::from_secs(20))
            .timeout_write(Duration::from_secs(10))
            .redirects(5)
            .https_only(true)
            .user_agent(user_agent)
            .build();
        Net {
            agent,
            diag,
            simulate_offline: AtomicBool::new(false),
        }
    }

    fn guard(&self) -> Result<()> {
        if self.simulate_offline.load(Ordering::Relaxed) {
            return Err(CoreError::new(
                "offline",
                "Simulated offline mode is on (Developer options).",
            ));
        }
        Ok(())
    }

    fn map_err(&self, url: &str, started: Instant, e: ureq::Error) -> CoreError {
        self.diag.failures.fetch_add(1, Ordering::Relaxed);
        let ms = started.elapsed().as_millis();
        match e {
            ureq::Error::Status(code, _) => {
                self.diag.warn("net", format!("{code} {url} ({ms} ms)"));
                if code == 404 || code == 410 {
                    CoreError::new("not_found", "This page is no longer available.")
                } else {
                    CoreError::new("http", format!("The server answered with HTTP {code}."))
                }
            }
            ureq::Error::Transport(t) => {
                self.diag.warn("net", format!("FAIL {url} ({ms} ms): {t}"));
                CoreError::new("network", "Could not reach mellozone.site. Check your connection.")
            }
        }
    }

    /// Conditional GET for HTML or JSON.
    pub fn get_text(
        &self,
        url: &str,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<Fetched> {
        self.guard()?;
        let started = Instant::now();
        self.diag.requests.fetch_add(1, Ordering::Relaxed);
        let mut req = self
            .agent
            .get(url)
            .set("Accept", "text/html,application/json;q=0.9,*/*;q=0.5")
            .set("Accept-Language", "en-US,en;q=0.8");
        if let Some(e) = etag {
            req = req.set("If-None-Match", e);
        }
        if let Some(lm) = last_modified {
            req = req.set("If-Modified-Since", lm);
        }
        let resp = req.call().map_err(|e| self.map_err(url, started, e))?;
        if resp.status() == 304 {
            self.diag.info(
                "net",
                format!("304 {url} ({} ms)", started.elapsed().as_millis()),
            );
            return Ok(Fetched::NotModified);
        }
        let status = resp.status();
        let final_url = resp.get_url().to_string();
        let etag = resp.header("ETag").map(str::to_string);
        let last_modified = resp.header("Last-Modified").map(str::to_string);
        let mut body = String::new();
        resp.into_reader()
            .take(MAX_TEXT_BYTES)
            .read_to_string(&mut body)
            .map_err(|e| {
                self.diag.failures.fetch_add(1, Ordering::Relaxed);
                CoreError::new("network", format!("The download was interrupted: {e}"))
            })?;
        self.diag.bytes.fetch_add(body.len() as u64, Ordering::Relaxed);
        self.diag.info(
            "net",
            format!(
                "{status} {url} ({} ms, {} KB)",
                started.elapsed().as_millis(),
                body.len() / 1024
            ),
        );
        Ok(Fetched::Fresh(TextResponse {
            body,
            etag,
            last_modified,
            final_url,
        }))
    }

    /// Downloads a binary resource (images), refusing anything over `max`.
    pub fn get_bytes(&self, url: &str, max: u64) -> Result<(Vec<u8>, String)> {
        self.guard()?;
        let started = Instant::now();
        self.diag.requests.fetch_add(1, Ordering::Relaxed);
        let resp = self
            .agent
            .get(url)
            .set("Accept", "image/webp,image/avif,image/png,image/jpeg,image/*;q=0.8")
            .call()
            .map_err(|e| self.map_err(url, started, e))?;
        let content_type = resp.content_type().to_string();
        let mut bytes = Vec::new();
        resp.into_reader()
            .take(max + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| {
                self.diag.failures.fetch_add(1, Ordering::Relaxed);
                CoreError::new("network", format!("The download was interrupted: {e}"))
            })?;
        if bytes.len() as u64 > max {
            return Err(CoreError::invalid("That file is too large to load."));
        }
        self.diag.bytes.fetch_add(bytes.len() as u64, Ordering::Relaxed);
        self.diag.debug(
            "net",
            format!(
                "200 {url} ({} ms, {} KB)",
                started.elapsed().as_millis(),
                bytes.len() / 1024
            ),
        );
        Ok((bytes, content_type))
    }
}
