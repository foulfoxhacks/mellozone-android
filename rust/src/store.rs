//! On-device storage: parsed pages (app files) and images (app cache).

use crate::model::CachedPage;
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Once, RwLock};

pub fn fnv1a64(input: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for b in input.as_bytes() {
        hash ^= u64::from(*b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

#[derive(Debug, Default, Clone, Copy, Serialize)]
pub struct StoreStats {
    pub pages: u64,
    pub page_bytes: u64,
    pub images: u64,
    pub image_bytes: u64,
}

pub struct Store {
    root: PathBuf,
    pages_dir: PathBuf,
    images_dir: PathBuf,
    pages: RwLock<HashMap<String, Arc<CachedPage>>>,
    loaded: Once,
    tmp_counter: AtomicU64,
    /// Bumped whenever searchable content changes.
    pub generation: AtomicU64,
}

fn dir_stats(dir: &Path) -> (u64, u64) {
    let mut count = 0;
    let mut bytes = 0;
    if let Ok(read) = fs::read_dir(dir) {
        for entry in read.flatten() {
            if let Ok(meta) = entry.metadata() {
                if meta.is_file() {
                    count += 1;
                    bytes += meta.len();
                }
            }
        }
    }
    (count, bytes)
}

fn clear_dir(dir: &Path) {
    if let Ok(read) = fs::read_dir(dir) {
        for entry in read.flatten() {
            let _ = fs::remove_file(entry.path());
        }
    }
}

impl Store {
    pub fn new(files_dir: &str, cache_dir: &str) -> io::Result<Self> {
        let root = Path::new(files_dir).join("core");
        let pages_dir = root.join("pages");
        let images_dir = Path::new(cache_dir).join("core").join("images");
        fs::create_dir_all(&pages_dir)?;
        fs::create_dir_all(&images_dir)?;
        Ok(Store {
            root,
            pages_dir,
            images_dir,
            pages: RwLock::new(HashMap::new()),
            loaded: Once::new(),
            tmp_counter: AtomicU64::new(0),
            generation: AtomicU64::new(1),
        })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn page_file(&self, path: &str) -> PathBuf {
        self.pages_dir.join(format!("{:016x}.json", fnv1a64(path)))
    }

    /// Writes through a temporary file so a crash never leaves a torn file.
    fn write_atomic(&self, target: &Path, bytes: &[u8]) -> io::Result<()> {
        let n = self.tmp_counter.fetch_add(1, Ordering::Relaxed);
        let tmp = target.with_extension(format!("tmp{n}"));
        fs::write(&tmp, bytes)?;
        match fs::rename(&tmp, target) {
            Ok(()) => Ok(()),
            Err(e) => {
                let _ = fs::remove_file(&tmp);
                Err(e)
            }
        }
    }

    /// Loads every cached page into memory once. Safe to call from any thread;
    /// concurrent callers wait for the first load to finish.
    pub fn ensure_loaded(&self) {
        self.loaded.call_once(|| {
            let mut found: Vec<CachedPage> = Vec::new();
            if let Ok(read) = fs::read_dir(&self.pages_dir) {
                for entry in read.flatten() {
                    let p = entry.path();
                    if p.extension().and_then(|e| e.to_str()) != Some("json") {
                        // Leftover temporary file from an interrupted write.
                        let _ = fs::remove_file(&p);
                        continue;
                    }
                    match fs::read(&p).ok().and_then(|b| serde_json::from_slice(&b).ok()) {
                        Some(cp) => found.push(cp),
                        None => {
                            let _ = fs::remove_file(&p);
                        }
                    }
                }
            }
            let mut map = self.pages.write().unwrap_or_else(|e| e.into_inner());
            for cp in found {
                map.entry(cp.page.path.clone()).or_insert_with(|| Arc::new(cp));
            }
        });
    }

    pub fn get(&self, path: &str) -> Option<Arc<CachedPage>> {
        if let Some(hit) = self
            .pages
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(path)
        {
            return Some(hit.clone());
        }
        if self.loaded.is_completed() {
            return None;
        }
        // Startup: the bulk load is still running, read this one directly.
        let bytes = fs::read(self.page_file(path)).ok()?;
        let cp: CachedPage = serde_json::from_slice(&bytes).ok()?;
        if cp.page.path != path {
            return None;
        }
        let cp = Arc::new(cp);
        self.pages
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .insert(path.to_string(), cp.clone());
        Some(cp)
    }

    pub fn put(&self, cp: CachedPage) -> io::Result<Arc<CachedPage>> {
        let bytes = serde_json::to_vec(&cp).map_err(io::Error::other)?;
        self.write_atomic(&self.page_file(&cp.page.path), &bytes)?;
        let cp = Arc::new(cp);
        self.pages
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .insert(cp.page.path.clone(), cp.clone());
        self.generation.fetch_add(1, Ordering::Relaxed);
        Ok(cp)
    }

    pub fn all(&self) -> Vec<Arc<CachedPage>> {
        self.ensure_loaded();
        self.pages
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .values()
            .cloned()
            .collect()
    }

    pub fn read_json<T: DeserializeOwned>(&self, name: &str) -> Option<T> {
        let bytes = fs::read(self.root.join(name)).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    pub fn write_json<T: Serialize>(&self, name: &str, value: &T) -> io::Result<()> {
        let bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
        self.write_atomic(&self.root.join(name), &bytes)
    }

    pub fn remove(&self, name: &str) {
        let _ = fs::remove_file(self.root.join(name));
    }

    pub fn image_file(&self, url: &str, ext: &str) -> PathBuf {
        self.images_dir.join(format!("{:016x}.{ext}", fnv1a64(url)))
    }

    pub fn write_image(&self, target: &Path, bytes: &[u8]) -> io::Result<()> {
        fs::create_dir_all(&self.images_dir)?;
        self.write_atomic(target, bytes)
    }

    pub fn clear_pages(&self) {
        self.ensure_loaded();
        self.pages.write().unwrap_or_else(|e| e.into_inner()).clear();
        clear_dir(&self.pages_dir);
        self.generation.fetch_add(1, Ordering::Relaxed);
    }

    pub fn clear_images(&self) {
        clear_dir(&self.images_dir);
    }

    pub fn stats(&self) -> StoreStats {
        let (pages, page_bytes) = dir_stats(&self.pages_dir);
        let (images, image_bytes) = dir_stats(&self.images_dir);
        StoreStats {
            pages,
            page_bytes,
            images,
            image_bytes,
        }
    }
}
