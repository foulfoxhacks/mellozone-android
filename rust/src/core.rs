//! The engine behind the app. The UI calls [`dispatch`] with a method name
//! and a JSON payload and gets a JSON envelope back:
//! `{"ok":true,"data":…}` or `{"ok":false,"error":{"code":…,"message":…}}`.
//!
//! Every method is blocking and thread-safe; the UI calls from background
//! threads so the main thread never waits on storage, parsing or the network.

use crate::diag::{now_secs, Diag};
use crate::error::{CoreError, Result};
use crate::model::{
    Cached, CachedPage, DirSection, FeedItem, SavedItem, Settings,
};
use crate::net::{Fetched, Net};
use crate::parser;
use crate::search::Index;
use crate::store::Store;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::Instant;
use url::Url;

pub const SITE: &str = "https://mellozone.site";
const HOSTS: [&str; 2] = ["mellozone.site", "www.mellozone.site"];
/// Private or staff-only areas the site asks clients not to request.
const PRIVATE_PREFIXES: [&str; 6] = [
    "/api",
    "/sitemanager",
    "/article-assistant",
    "/keystatic",
    "/preview",
    "/draft",
];

const TTL_PAGE: u64 = 30 * 60;
const TTL_FEED: u64 = 15 * 60;
const TTL_DIRECTORY: u64 = 6 * 60 * 60;
const MAX_IMAGE_BYTES: u64 = 12 * 1024 * 1024;
const SYNC_WORKERS: usize = 3;

static CORE: OnceLock<Arc<Core>> = OnceLock::new();

#[derive(Deserialize)]
struct InitArgs {
    files_dir: String,
    cache_dir: String,
    #[serde(default)]
    app_version: String,
}

#[derive(Default)]
struct SyncState {
    running: AtomicBool,
    cancel: AtomicBool,
    total: AtomicUsize,
    done: AtomicUsize,
    failed: AtomicUsize,
    finished_at: AtomicU64,
    /// `idle`, `running`, `done`, `cancelled` or `failed`.
    state: Mutex<String>,
    message: Mutex<String>,
}

struct Loaded<T> {
    value: Arc<Cached<T>>,
    source: &'static str,
    stale: bool,
    warning: Option<String>,
}

struct PageOutcome {
    cached: Arc<CachedPage>,
    source: &'static str,
    stale: bool,
    changed: bool,
    warning: Option<String>,
}

pub struct Core {
    app_version: String,
    started: Instant,
    diag: Arc<Diag>,
    net: Net,
    store: Store,
    settings: RwLock<Settings>,
    saved: RwLock<Vec<SavedItem>>,
    feed: RwLock<Option<Arc<Cached<Vec<FeedItem>>>>>,
    directory: RwLock<Option<Arc<Cached<Vec<DirSection>>>>>,
    index: RwLock<Option<(u64, Arc<Index>)>>,
    sync: SyncState,
}

fn read<T>(lock: &RwLock<T>) -> std::sync::RwLockReadGuard<'_, T> {
    lock.read().unwrap_or_else(|e| e.into_inner())
}

fn write<T>(lock: &RwLock<T>) -> std::sync::RwLockWriteGuard<'_, T> {
    lock.write().unwrap_or_else(|e| e.into_inner())
}

fn arg_str<'a>(payload: &'a Value, key: &str) -> Result<&'a str> {
    payload
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| CoreError::invalid(format!("missing \"{key}\"")))
}

fn opt_str<'a>(payload: &'a Value, key: &str) -> Option<&'a str> {
    payload.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

fn is_private(path: &str) -> bool {
    PRIVATE_PREFIXES
        .iter()
        .any(|p| path == *p || path.starts_with(&format!("{p}/")))
}

/// Accepts a path or a full mellozone.site URL and returns the canonical path.
pub fn normalize_path(input: &str) -> Result<String> {
    let t = input.trim();
    let base = Url::parse(SITE).expect("site url");
    let url = if t.starts_with("http://") || t.starts_with("https://") {
        Url::parse(t)
    } else {
        base.join(t)
    }
    .map_err(|_| CoreError::invalid("That link is not valid."))?;
    match url.host_str() {
        Some(h) if HOSTS.contains(&h) => {}
        _ => return Err(CoreError::invalid("That link is not a Mello Zone page.")),
    }
    let mut path = url.path().to_string();
    while path.len() > 1 && path.ends_with('/') {
        path.pop();
    }
    if path.is_empty() {
        path.push('/');
    }
    if is_private(&path) {
        return Err(CoreError::invalid("That area is not available in the app."));
    }
    Ok(path)
}

/// Decides where a tapped link goes: a native page, or out to another app.
pub fn route(href: &str) -> Value {
    let h = href.trim();
    let external = |url: &str| json!({ "kind": "external", "url": url });
    if h.starts_with("mailto:") || h.starts_with("tel:") {
        return external(h);
    }
    let Ok(path) = normalize_path(h) else {
        return external(h);
    };
    // Downloads and feeds (/blog/feed.xml, *.csv) belong in the browser.
    let last = path.rsplit('/').next().unwrap_or("");
    if last.contains('.') {
        return external(&format!("{SITE}{path}"));
    }
    json!({ "kind": "page", "path": path, "url": format!("{SITE}{path}") })
}

impl Core {
    fn new(args: InitArgs) -> Result<Core> {
        let diag = Arc::new(Diag::default());
        let store = Store::new(&args.files_dir, &args.cache_dir)?;
        let settings: Settings = store.read_json("settings.json").unwrap_or_default();
        let saved: Vec<SavedItem> = store.read_json("saved.json").unwrap_or_default();
        let user_agent = format!(
            "MelloZoneAndroid/{} (mellozone-core {}; +https://mellozone.site)",
            if args.app_version.is_empty() { "dev" } else { &args.app_version },
            env!("CARGO_PKG_VERSION"),
        );
        let net = Net::new(diag.clone(), &user_agent);
        net.simulate_offline
            .store(settings.dev_simulate_offline, Ordering::Relaxed);
        diag.verbose.store(settings.dev_verbose_log, Ordering::Relaxed);
        let core = Core {
            app_version: args.app_version,
            started: Instant::now(),
            diag,
            net,
            store,
            settings: RwLock::new(settings),
            saved: RwLock::new(saved),
            feed: RwLock::new(None),
            directory: RwLock::new(None),
            index: RwLock::new(None),
            sync: SyncState::default(),
        };
        *core.sync.state.lock().unwrap_or_else(|e| e.into_inner()) = "idle".into();
        Ok(core)
    }

    // ------------------------------------------------------------ pages --

    fn fetch_page(
        &self,
        path: &str,
        cached: Option<Arc<CachedPage>>,
    ) -> Result<(Arc<CachedPage>, bool)> {
        let url = format!("{SITE}{path}");
        let (etag, last_modified) = match &cached {
            Some(c) => (c.etag.as_deref(), c.last_modified.as_deref()),
            None => (None, None),
        };
        match self.net.get_text(&url, etag, last_modified)? {
            Fetched::NotModified => {
                let old = cached.ok_or_else(|| CoreError::new("internal", "unexpected 304"))?;
                let mut fresh = (*old).clone();
                fresh.fetched_at = now_secs();
                Ok((self.store.put(fresh)?, false))
            }
            Fetched::Fresh(resp) => {
                let landed = Url::parse(&resp.final_url)
                    .map_err(|_| CoreError::new("network", "Unexpected redirect."))?;
                if !landed.host_str().is_some_and(|h| HOSTS.contains(&h)) {
                    return Err(CoreError::new(
                        "network",
                        "The page redirected away from mellozone.site.",
                    ));
                }
                let base = Url::parse(&url).map_err(|_| CoreError::invalid("bad url"))?;
                let started = Instant::now();
                let page = parser::parse_page(&resp.body, &base);
                self.diag.pages_parsed.fetch_add(1, Ordering::Relaxed);
                self.diag.debug(
                    "parse",
                    format!(
                        "{path}: {} blocks in {} µs",
                        page.blocks.len(),
                        started.elapsed().as_micros()
                    ),
                );
                if page.blocks.is_empty() {
                    return Err(CoreError::new(
                        "parse",
                        "This page has no readable content yet.",
                    ));
                }
                let changed = cached.as_ref().map(|c| c.page != page).unwrap_or(true);
                let text = parser::plain_text(&page.blocks);
                let cp = CachedPage {
                    page,
                    text,
                    etag: resp.etag,
                    last_modified: resp.last_modified,
                    fetched_at: now_secs(),
                };
                Ok((self.store.put(cp)?, changed))
            }
        }
    }

    fn load_page(&self, path: &str, mode: &str) -> Result<PageOutcome> {
        let cached = self.store.get(path);
        let is_stale = |c: &CachedPage| now_secs().saturating_sub(c.fetched_at) > TTL_PAGE;
        let bypass = read(&self.settings).dev_bypass_cache;
        let mode = if bypass && mode == "cache_first" { "network" } else { mode };
        match mode {
            "cache_only" => cached
                .map(|c| PageOutcome {
                    stale: is_stale(&c),
                    cached: c,
                    source: "cache",
                    changed: false,
                    warning: None,
                })
                .ok_or_else(|| CoreError::new("not_cached", "This page is not saved on this device.")),
            "cache_first" if cached.is_some() => {
                let c = cached.expect("checked");
                self.diag.cache_hits.fetch_add(1, Ordering::Relaxed);
                Ok(PageOutcome {
                    stale: is_stale(&c),
                    cached: c,
                    source: "cache",
                    changed: false,
                    warning: None,
                })
            }
            _ => match self.fetch_page(path, cached.clone()) {
                Ok((c, changed)) => Ok(PageOutcome {
                    cached: c,
                    source: "network",
                    stale: false,
                    changed,
                    warning: None,
                }),
                Err(e) => match cached {
                    Some(c) if e.is_connectivity() => Ok(PageOutcome {
                        cached: c,
                        source: "cache",
                        stale: true,
                        changed: false,
                        warning: Some(e.message),
                    }),
                    _ => Err(e),
                },
            },
        }
    }

    fn page(&self, payload: &Value) -> Result<Value> {
        let path = normalize_path(arg_str(payload, "path")?)?;
        let mode = opt_str(payload, "mode").unwrap_or("cache_first");
        let out = self.load_page(&path, mode)?;
        let saved = read(&self.saved).iter().any(|s| s.path == path);
        Ok(json!({
            "page": &out.cached.page,
            "source": out.source,
            "stale": out.stale,
            "changed": out.changed,
            "fetched_at": out.cached.fetched_at,
            "warning": out.warning,
            "saved": saved,
        }))
    }

    // ------------------------------------------------ feed and directory --

    fn cached_resource<T>(
        &self,
        slot: &RwLock<Option<Arc<Cached<T>>>>,
        file: &str,
        ttl: u64,
        mode: &str,
        fetch: impl Fn() -> Result<T>,
    ) -> Result<Loaded<T>>
    where
        T: Serialize + DeserializeOwned,
    {
        let mut current = read(slot).clone();
        if current.is_none() {
            if let Some(disk) = self.store.read_json::<Cached<T>>(file) {
                let disk = Arc::new(disk);
                *write(slot) = Some(disk.clone());
                current = Some(disk);
            }
        }
        let is_stale = |c: &Cached<T>| now_secs().saturating_sub(c.fetched_at) > ttl;
        let from_cache = |c: Arc<Cached<T>>, warning: Option<String>| Loaded {
            stale: is_stale(&c) || warning.is_some(),
            value: c,
            source: "cache",
            warning,
        };
        let bypass = read(&self.settings).dev_bypass_cache;
        let mode = if bypass && mode == "cache_first" { "network" } else { mode };
        match (mode, current) {
            ("cache_only", Some(c)) | ("cache_first", Some(c)) => {
                self.diag.cache_hits.fetch_add(1, Ordering::Relaxed);
                Ok(from_cache(c, None))
            }
            ("cache_only", None) => Err(CoreError::new("not_cached", "Nothing saved yet.")),
            (_, current) => match fetch() {
                Ok(data) => {
                    let fresh = Arc::new(Cached {
                        fetched_at: now_secs(),
                        data,
                    });
                    self.store.write_json(file, &*fresh)?;
                    *write(slot) = Some(fresh.clone());
                    self.store.generation.fetch_add(1, Ordering::Relaxed);
                    Ok(Loaded {
                        value: fresh,
                        source: "network",
                        stale: false,
                        warning: None,
                    })
                }
                Err(e) => match current {
                    Some(c) if e.is_connectivity() => Ok(from_cache(c, Some(e.message))),
                    _ => Err(e),
                },
            },
        }
    }

    fn fetch_text(&self, path: &str) -> Result<String> {
        match self.net.get_text(&format!("{SITE}{path}"), None, None)? {
            Fetched::Fresh(r) => Ok(r.body),
            Fetched::NotModified => Err(CoreError::new("internal", "unexpected 304")),
        }
    }

    fn load_feed(&self, mode: &str) -> Result<Loaded<Vec<FeedItem>>> {
        #[derive(Deserialize)]
        struct RawAuthor {
            #[serde(default)]
            name: String,
        }
        #[derive(Deserialize)]
        struct RawItem {
            url: String,
            #[serde(default)]
            title: String,
            #[serde(default)]
            summary: String,
            #[serde(default)]
            image: Option<String>,
            #[serde(default)]
            date_published: Option<String>,
            #[serde(default)]
            tags: Vec<String>,
            #[serde(default)]
            authors: Vec<RawAuthor>,
        }
        #[derive(Deserialize)]
        struct RawFeed {
            #[serde(default)]
            items: Vec<RawItem>,
        }
        self.cached_resource(&self.feed, "feed.json", TTL_FEED, mode, || {
            let raw: RawFeed = serde_json::from_str(&self.fetch_text("/blog/feed.json")?)?;
            let items: Vec<FeedItem> = raw
                .items
                .into_iter()
                .filter_map(|i| {
                    let path = normalize_path(&i.url).ok()?;
                    if i.title.trim().is_empty() {
                        return None;
                    }
                    Some(FeedItem {
                        url: format!("{SITE}{path}"),
                        path,
                        title: i.title.trim().to_string(),
                        summary: i.summary.trim().to_string(),
                        image: i.image.filter(|u| u.starts_with("https://")),
                        date_published: i.date_published,
                        tags: i.tags,
                        author: i.authors.into_iter().next().map(|a| a.name).unwrap_or_default(),
                    })
                })
                .collect();
            if items.is_empty() {
                return Err(CoreError::new("parse", "The journal feed was empty."));
            }
            Ok(items)
        })
    }

    fn load_directory(&self, mode: &str) -> Result<Loaded<Vec<DirSection>>> {
        self.cached_resource(&self.directory, "directory.json", TTL_DIRECTORY, mode, || {
            let html = self.fetch_text("/sitemap")?;
            let base = Url::parse(&format!("{SITE}/sitemap")).expect("directory url");
            let sections: Vec<DirSection> = parser::parse_directory(&html, &base)
                .into_iter()
                .map(|mut s| {
                    s.entries.retain(|e| !is_private(&e.path));
                    for e in &mut s.entries {
                        if let Ok(p) = normalize_path(&e.path) {
                            e.path = p;
                        }
                    }
                    s
                })
                .filter(|s| !s.entries.is_empty())
                .collect();
            if sections.is_empty() {
                return Err(CoreError::new("parse", "The page directory was empty."));
            }
            Ok(sections)
        })
    }

    fn listing<T: Serialize>(key: &str, loaded: &Loaded<T>) -> Value {
        json!({
            key: &loaded.value.data,
            "source": loaded.source,
            "stale": loaded.stale,
            "fetched_at": loaded.value.fetched_at,
            "warning": loaded.warning,
        })
    }

    // ----------------------------------------------------------- search --

    fn search_index(&self) -> Arc<Index> {
        let generation = self.store.generation.load(Ordering::Relaxed);
        if let Some((g, idx)) = read(&self.index).as_ref() {
            if *g == generation {
                return idx.clone();
            }
        }
        let started = Instant::now();
        let directory = self.load_directory("cache_only").ok();
        let feed = self.load_feed("cache_only").ok();
        let pages = self.store.all();
        let idx = Arc::new(Index::build(
            directory.as_ref().map(|d| d.value.data.as_slice()).unwrap_or(&[]),
            feed.as_ref().map(|f| f.value.data.as_slice()).unwrap_or(&[]),
            &pages,
        ));
        self.diag.debug(
            "search",
            format!("indexed {} documents in {} ms", idx.len(), started.elapsed().as_millis()),
        );
        *write(&self.index) = Some((generation, idx.clone()));
        idx
    }

    fn search(&self, payload: &Value) -> Result<Value> {
        let query = arg_str(payload, "query")?;
        let limit = payload.get("limit").and_then(Value::as_u64).unwrap_or(40) as usize;
        let started = Instant::now();
        let index = self.search_index();
        let hits = index.search(query, limit.clamp(1, 100));
        Ok(json!({
            "query": query,
            "hits": hits,
            "documents": index.len(),
            "micros": started.elapsed().as_micros() as u64,
        }))
    }

    // ----------------------------------------------------------- images --

    fn image(&self, payload: &Value) -> Result<Value> {
        let raw = arg_str(payload, "url")?;
        let allow_network = payload
            .get("allow_network")
            .and_then(Value::as_bool)
            .unwrap_or(true);
        let url = Url::parse(raw).map_err(|_| CoreError::invalid("bad image url"))?;
        if url.scheme() != "https" {
            return Err(CoreError::invalid("Images must load over HTTPS."));
        }
        let ext = url
            .path()
            .rsplit('.')
            .next()
            .map(str::to_ascii_lowercase)
            .filter(|e| matches!(e.as_str(), "webp" | "png" | "jpg" | "jpeg" | "gif" | "avif"))
            .unwrap_or_else(|| "img".into());
        let file = self.store.image_file(raw, &ext);
        if file.is_file() {
            self.diag.cache_hits.fetch_add(1, Ordering::Relaxed);
            return Ok(json!({ "path": file.to_string_lossy(), "source": "cache" }));
        }
        if !allow_network {
            return Err(CoreError::new("not_cached", "Image not downloaded (data saver)."));
        }
        let (bytes, content_type) = self.net.get_bytes(raw, MAX_IMAGE_BYTES)?;
        if !content_type.starts_with("image/") || content_type.contains("svg") {
            return Err(CoreError::invalid("That link is not a supported image."));
        }
        self.store.write_image(&file, &bytes)?;
        Ok(json!({ "path": file.to_string_lossy(), "source": "network" }))
    }

    // ------------------------------------------------------------ saved --

    fn saved_toggle(&self, payload: &Value) -> Result<Value> {
        let path = normalize_path(arg_str(payload, "path")?)?;
        let mut saved = write(&self.saved);
        let now_saved = if let Some(i) = saved.iter().position(|s| s.path == path) {
            saved.remove(i);
            false
        } else {
            let cached = self.store.get(&path);
            let page = cached.as_ref().map(|c| &c.page);
            let title = opt_str(payload, "title")
                .map(str::to_string)
                .or_else(|| {
                    page.map(|p| if p.heading.is_empty() { p.title.clone() } else { p.heading.clone() })
                })
                .unwrap_or_else(|| path.clone());
            saved.insert(
                0,
                SavedItem {
                    title,
                    description: page.map(|p| p.description.clone()).unwrap_or_default(),
                    image: page.and_then(|p| p.image.clone()),
                    path,
                    saved_at: now_secs(),
                },
            );
            true
        };
        self.store.write_json("saved.json", &*saved)?;
        Ok(json!({ "saved": now_saved, "items": &*saved }))
    }

    // --------------------------------------------------------- settings --

    fn settings_set(&self, payload: &Value) -> Result<Value> {
        let mut settings = write(&self.settings);
        let mut merged = serde_json::to_value(&*settings)?;
        if let (Some(target), Some(patch)) = (merged.as_object_mut(), payload.as_object()) {
            for (k, v) in patch {
                if target.contains_key(k) {
                    target.insert(k.clone(), v.clone());
                }
            }
        }
        let mut next: Settings = serde_json::from_value(merged)
            .map_err(|_| CoreError::invalid("Invalid settings value."))?;
        next.text_scale = if next.text_scale.is_finite() {
            next.text_scale.clamp(0.85, 1.6)
        } else {
            1.0
        };
        self.net
            .simulate_offline
            .store(next.dev_simulate_offline, Ordering::Relaxed);
        self.diag.verbose.store(next.dev_verbose_log, Ordering::Relaxed);
        self.store.write_json("settings.json", &next)?;
        *settings = next;
        Ok(serde_json::to_value(&*settings)?)
    }

    // ------------------------------------------------------ offline sync --

    fn sync_status(&self) -> Value {
        let s = &self.sync;
        json!({
            "state": &*s.state.lock().unwrap_or_else(|e| e.into_inner()),
            "message": &*s.message.lock().unwrap_or_else(|e| e.into_inner()),
            "total": s.total.load(Ordering::Relaxed),
            "done": s.done.load(Ordering::Relaxed),
            "failed": s.failed.load(Ordering::Relaxed),
            "finished_at": s.finished_at.load(Ordering::Relaxed),
        })
    }

    fn set_sync_state(&self, state: &str, message: impl Into<String>) {
        *self.sync.state.lock().unwrap_or_else(|e| e.into_inner()) = state.to_string();
        *self.sync.message.lock().unwrap_or_else(|e| e.into_inner()) = message.into();
    }

    /// Downloads every public page so the whole site reads offline.
    fn sync_start(self: &Arc<Self>) -> Value {
        if self.sync.running.swap(true, Ordering::SeqCst) {
            return self.sync_status();
        }
        let s = &self.sync;
        s.cancel.store(false, Ordering::SeqCst);
        s.total.store(0, Ordering::Relaxed);
        s.done.store(0, Ordering::Relaxed);
        s.failed.store(0, Ordering::Relaxed);
        self.set_sync_state("running", "Finding pages…");
        let core = self.clone();
        let spawned = std::thread::Builder::new()
            .name("mz-sync".into())
            .spawn(move || {
                core.run_sync();
                core.sync.finished_at.store(now_secs(), Ordering::Relaxed);
                core.sync.running.store(false, Ordering::SeqCst);
            });
        if spawned.is_err() {
            self.set_sync_state("failed", "Could not start the download.");
            self.sync.running.store(false, Ordering::SeqCst);
        }
        self.sync_status()
    }

    fn run_sync(&self) {
        let directory = self.load_directory("network");
        let feed = self.load_feed("network");
        let mut paths: BTreeSet<String> = BTreeSet::new();
        paths.insert("/".into());
        if let Ok(d) = &directory {
            for section in &d.value.data {
                paths.extend(section.entries.iter().map(|e| e.path.clone()));
            }
        }
        if let Ok(f) = &feed {
            paths.extend(f.value.data.iter().map(|i| i.path.clone()));
        }
        if let (Err(e), true) = (&directory, paths.len() <= 1) {
            self.set_sync_state("failed", e.message.clone());
            return;
        }
        // Skip downloads and the search page; they have no article content.
        let paths: Vec<String> = paths
            .into_iter()
            .filter(|p| !p.rsplit('/').next().unwrap_or("").contains('.') && p != "/search")
            .collect();
        let s = &self.sync;
        s.total.store(paths.len(), Ordering::Relaxed);
        self.set_sync_state("running", "Saving pages for offline reading…");
        let next = AtomicUsize::new(0);
        std::thread::scope(|scope| {
            for _ in 0..SYNC_WORKERS {
                scope.spawn(|| loop {
                    if s.cancel.load(Ordering::Relaxed) {
                        break;
                    }
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    let Some(path) = paths.get(i) else { break };
                    let cached = self.store.get(path);
                    let fresh = cached
                        .as_ref()
                        .is_some_and(|c| now_secs().saturating_sub(c.fetched_at) <= TTL_PAGE);
                    if !fresh {
                        if let Err(e) = self.fetch_page(path, cached) {
                            s.failed.fetch_add(1, Ordering::Relaxed);
                            self.diag.warn("sync", format!("{path}: {}", e.message));
                        }
                    }
                    s.done.fetch_add(1, Ordering::Relaxed);
                });
            }
        });
        let failed = s.failed.load(Ordering::Relaxed);
        if s.cancel.load(Ordering::Relaxed) {
            self.set_sync_state("cancelled", "Download stopped.");
        } else if failed > 0 && failed == paths.len() {
            self.set_sync_state("failed", "Could not reach mellozone.site.");
        } else if failed > 0 {
            self.set_sync_state("done", format!("Saved, but {failed} pages could not be downloaded."));
        } else {
            self.set_sync_state("done", "Everything is saved for offline reading.");
        }
        self.diag.info(
            "sync",
            format!("finished: {} pages, {failed} failed", paths.len()),
        );
    }

    // ------------------------------------------------------ diagnostics --

    fn diag_info(&self) -> Value {
        let stats = self.store.stats();
        let d = &self.diag;
        json!({
            "core_version": env!("CARGO_PKG_VERSION"),
            "app_version": &self.app_version,
            "rustc": env!("MZ_RUSTC"),
            "target": env!("MZ_TARGET"),
            "arch": std::env::consts::ARCH,
            "profile": if cfg!(debug_assertions) { "debug" } else { "release" },
            "http": "ureq 2 (blocking)",
            "tls": "rustls + ring, bundled web PKI roots",
            "html": "scraper / html5ever",
            "site": SITE,
            "uptime_secs": self.started.elapsed().as_secs(),
            "requests": d.requests.load(Ordering::Relaxed),
            "failures": d.failures.load(Ordering::Relaxed),
            "bytes_downloaded": d.bytes.load(Ordering::Relaxed),
            "cache_hits": d.cache_hits.load(Ordering::Relaxed),
            "pages_parsed": d.pages_parsed.load(Ordering::Relaxed),
            "storage": stats,
            "data_dir": self.store.root().to_string_lossy(),
        })
    }

    fn self_test(&self) -> Value {
        let mut results: Vec<Value> = Vec::new();
        let mut check = |name: &str, f: &dyn Fn() -> Result<String>| {
            let started = Instant::now();
            let outcome = f();
            let ms = started.elapsed().as_secs_f64() * 1000.0;
            results.push(match outcome {
                Ok(detail) => json!({ "name": name, "ok": true, "detail": detail, "ms": ms }),
                Err(e) => json!({ "name": name, "ok": false, "detail": e.message, "ms": ms }),
            });
        };
        check("Storage read/write", &|| {
            let probe = json!({ "probe": now_secs() });
            self.store.write_json("selftest.json", &probe)?;
            let back: Value = self
                .store
                .read_json("selftest.json")
                .ok_or_else(|| CoreError::new("internal", "probe file could not be read back"))?;
            self.store.remove("selftest.json");
            if back == probe {
                Ok("Wrote and verified a probe file.".into())
            } else {
                Err(CoreError::new("internal", "probe file did not match"))
            }
        });
        check("HTML parser", &|| {
            let html = "<main><section><p>Kicker</p><h1>Title</h1><p>Lead with a <a href=\"/join\">link</a>.</p>\
                        <ul><li>One</li><li>Two</li></ul></section></main>";
            let base = Url::parse(SITE).expect("site url");
            let page = parser::parse_page(html, &base);
            if page.blocks.len() == 4 && page.heading == "Title" {
                Ok(format!("{} blocks from the sample document.", page.blocks.len()))
            } else {
                Err(CoreError::new("parse", format!("unexpected output: {} blocks", page.blocks.len())))
            }
        });
        check("Network and TLS", &|| {
            let body = self.fetch_text("/robots.txt")?;
            Ok(format!("HTTPS round trip OK ({} bytes).", body.len()))
        });
        check("Journal feed", &|| {
            let feed = self.load_feed("network")?;
            match feed.warning {
                Some(w) => Err(CoreError::new("network", w)),
                None => Ok(format!("{} articles.", feed.value.data.len())),
            }
        });
        check("Search index", &|| {
            let index = self.search_index();
            let hits = index.search("community", 10).len();
            Ok(format!("{} documents indexed, {hits} hits for \"community\".", index.len()))
        });
        json!({ "results": results })
    }

    fn cache_clear(&self, payload: &Value) -> Value {
        let what = opt_str(payload, "what").unwrap_or("all");
        if matches!(what, "pages" | "all") {
            self.store.clear_pages();
            self.store.remove("feed.json");
            self.store.remove("directory.json");
            *write(&self.feed) = None;
            *write(&self.directory) = None;
            *write(&self.index) = None;
        }
        if matches!(what, "images" | "all") {
            self.store.clear_images();
        }
        self.diag.info("cache", format!("cleared: {what}"));
        json!({ "storage": self.store.stats() })
    }

    // ---------------------------------------------------------- routing --

    fn handle(self: &Arc<Self>, method: &str, payload: &Value) -> Result<Value> {
        match method {
            "page" => self.page(payload),
            "journal" => {
                let mode = opt_str(payload, "mode").unwrap_or("cache_first");
                Ok(Self::listing("items", &self.load_feed(mode)?))
            }
            "directory" => {
                let mode = opt_str(payload, "mode").unwrap_or("cache_first");
                Ok(Self::listing("sections", &self.load_directory(mode)?))
            }
            "search" => self.search(payload),
            "route" => Ok(route(arg_str(payload, "href")?)),
            "image" => self.image(payload),
            "saved_list" => Ok(json!({ "items": &*read(&self.saved) })),
            "saved_toggle" => self.saved_toggle(payload),
            "settings_get" => Ok(serde_json::to_value(&*read(&self.settings))?),
            "settings_set" => self.settings_set(payload),
            "sync_start" => Ok(self.sync_start()),
            "sync_status" => Ok(self.sync_status()),
            "sync_cancel" => {
                self.sync.cancel.store(true, Ordering::SeqCst);
                Ok(self.sync_status())
            }
            "cache_stats" => Ok(json!({ "storage": self.store.stats() })),
            "cache_clear" => Ok(self.cache_clear(payload)),
            "diag" => Ok(self.diag_info()),
            "logs" => Ok(json!({ "entries": self.diag.entries() })),
            "logs_clear" => {
                self.diag.clear();
                Ok(json!({ "entries": [] }))
            }
            "self_test" => Ok(self.self_test()),
            other => Err(CoreError::invalid(format!("unknown method \"{other}\""))),
        }
    }
}

fn init(payload: &Value) -> Result<Value> {
    if let Some(core) = CORE.get() {
        return Ok(core.diag_info());
    }
    let args: InitArgs = serde_json::from_value(payload.clone())
        .map_err(|_| CoreError::invalid("init needs files_dir and cache_dir"))?;
    let core = Arc::new(Core::new(args)?);
    let core = CORE.get_or_init(|| core).clone();
    core.diag.info(
        "core",
        format!("mellozone-core {} started ({})", env!("CARGO_PKG_VERSION"), env!("MZ_TARGET")),
    );
    // Warm the page cache off the caller's thread so first search is instant.
    let warm = core.clone();
    let _ = std::thread::Builder::new()
        .name("mz-warm".into())
        .spawn(move || warm.store.ensure_loaded());
    Ok(core.diag_info())
}

fn dispatch_inner(method: &str, payload: &str) -> Result<Value> {
    let payload: Value = if payload.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(payload).map_err(|_| CoreError::invalid("payload is not JSON"))?
    };
    if method == "init" {
        return init(&payload);
    }
    let core = CORE
        .get()
        .ok_or_else(|| CoreError::new("internal", "core is not initialised"))?;
    core.handle(method, &payload)
}

/// Single entry point used by the JNI bridge (and by tests on the host).
pub fn dispatch(method: &str, payload: &str) -> String {
    let outcome = std::panic::catch_unwind(|| dispatch_inner(method, payload));
    let envelope = match outcome {
        Ok(Ok(data)) => json!({ "ok": true, "data": data }),
        Ok(Err(error)) => json!({ "ok": false, "error": error }),
        Err(_) => {
            if let Some(core) = CORE.get() {
                core.diag.log("error", "core", format!("panic while handling \"{method}\""));
            }
            json!({
                "ok": false,
                "error": { "code": "panic", "message": "Something went wrong inside the app engine." },
            })
        }
    };
    envelope.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_normalise_and_private_areas_are_refused() {
        assert_eq!(normalize_path("/join/").unwrap(), "/join");
        assert_eq!(normalize_path("https://mellozone.site").unwrap(), "/");
        assert_eq!(normalize_path("https://mellozone.site/blog/x?y=1#z").unwrap(), "/blog/x");
        assert!(normalize_path("https://example.com/join").is_err());
        assert!(normalize_path("/api/community-submission").is_err());
        assert!(normalize_path("/sitemanager").is_err());
    }

    #[test]
    fn routing_separates_pages_from_external_links() {
        assert_eq!(route("https://mellozone.site/faq")["kind"], "page");
        assert_eq!(route("https://mellozone.site/faq")["path"], "/faq");
        assert_eq!(route("https://discord.com/invite/x")["kind"], "external");
        assert_eq!(route("mailto:contactus@mellozone.site")["kind"], "external");
        assert_eq!(route("https://mellozone.site/blog/feed.xml")["kind"], "external");
    }

    #[test]
    fn calls_before_init_fail_cleanly() {
        let out: Value = serde_json::from_str(&dispatch("nope", "not json")).unwrap();
        assert_eq!(out["ok"], false);
    }
}
