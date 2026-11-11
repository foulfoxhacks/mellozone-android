//! Host-side smoke test: runs the engine against the live site.
//!
//!     cargo run --release --example crawl
//!
//! Downloads every public page through the same code the app uses, then
//! reports block statistics and runs a few searches.

use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::time::{Duration, Instant};

fn call(method: &str, payload: Value) -> Value {
    let raw = mellozone_core::dispatch(method, &payload.to_string());
    let v: Value = serde_json::from_str(&raw).expect("envelope");
    if v["ok"] != true {
        panic!("{method} failed: {}", v["error"]);
    }
    v["data"].clone()
}

fn count_blocks(blocks: &Value, histogram: &mut BTreeMap<String, usize>) {
    for b in blocks.as_array().into_iter().flatten() {
        *histogram.entry(b["type"].as_str().unwrap_or("?").to_string()).or_default() += 1;
        count_blocks(&b["blocks"], histogram);
        for item in b["items"].as_array().into_iter().flatten() {
            if item.is_array() {
                count_blocks(item, histogram);
            }
        }
    }
}

fn main() {
    let dir = std::env::temp_dir().join(format!("mz-crawl-{}", std::process::id()));
    let files = dir.join("files");
    let cache = dir.join("cache");
    let info = call(
        "init",
        json!({ "files_dir": files, "cache_dir": cache, "app_version": "crawl" }),
    );
    println!("core {} / {} / {}", info["core_version"], info["rustc"], info["target"]);

    let directory = call("directory", json!({ "mode": "network" }));
    let sections = directory["sections"].as_array().unwrap();
    let entries: usize = sections.iter().map(|s| s["entries"].as_array().unwrap().len()).sum();
    println!("directory: {} sections, {entries} pages", sections.len());

    let journal = call("journal", json!({ "mode": "network" }));
    println!("journal: {} articles", journal["items"].as_array().unwrap().len());

    let started = Instant::now();
    call("sync_start", json!({}));
    let status = loop {
        std::thread::sleep(Duration::from_millis(500));
        let s = call("sync_status", json!({}));
        if s["state"] != "running" {
            break s;
        }
    };
    println!(
        "sync: {} — {}/{} pages, {} failed, {:.1}s",
        status["state"],
        status["done"],
        status["total"],
        status["failed"],
        started.elapsed().as_secs_f32()
    );

    let mut histogram = BTreeMap::new();
    let mut thin: Vec<String> = Vec::new();
    let mut paths: Vec<String> = vec!["/".into()];
    for s in sections {
        for e in s["entries"].as_array().unwrap() {
            paths.push(e["path"].as_str().unwrap().to_string());
        }
    }
    let mut loaded = 0;
    for path in &paths {
        let raw = mellozone_core::dispatch("page", &json!({ "path": path, "mode": "cache_only" }).to_string());
        let v: Value = serde_json::from_str(&raw).unwrap();
        if v["ok"] != true {
            continue;
        }
        loaded += 1;
        let blocks = &v["data"]["page"]["blocks"];
        let n = blocks.as_array().map(Vec::len).unwrap_or(0);
        if n < 4 {
            thin.push(format!("{path} ({n} blocks)"));
        }
        count_blocks(blocks, &mut histogram);
    }
    println!("pages readable offline: {loaded}/{}", paths.len());
    println!("block types: {histogram:?}");
    if !thin.is_empty() {
        println!("thin pages: {thin:?}");
    }

    for q in ["vrchat safety", "age verification", "anthrocon", "join"] {
        let r = call("search", json!({ "query": q, "limit": 3 }));
        let titles: Vec<&str> = r["hits"]
            .as_array()
            .unwrap()
            .iter()
            .map(|h| h["title"].as_str().unwrap())
            .collect();
        println!("search \"{q}\" ({} µs over {} docs): {titles:?}", r["micros"], r["documents"]);
    }

    if std::env::args().any(|a| a == "--dump") {
        let home = call("page", json!({ "path": "/", "mode": "cache_only" }));
        println!("{}", serde_json::to_string_pretty(&home["page"]).unwrap());
    }
    let test = call("self_test", json!({}));
    for r in test["results"].as_array().unwrap() {
        println!("self-test {:<20} ok={} {}", r["name"].as_str().unwrap(), r["ok"], r["detail"]);
    }
    println!("storage: {}", call("cache_stats", json!({}))["storage"]);
    let _ = std::fs::remove_dir_all(dir);
}
