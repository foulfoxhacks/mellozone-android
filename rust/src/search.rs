//! Offline full-text search over everything the app knows about:
//! the page directory, the journal feed and every cached page body.

use crate::model::{CachedPage, DirSection, FeedItem};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::Arc;

struct Doc {
    path: String,
    title: String,
    description: String,
    body: String,
    kind: &'static str,
    image: Option<String>,
    title_lc: String,
    desc_lc: String,
    tags_lc: String,
    body_lc: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Hit {
    pub path: String,
    pub title: String,
    pub snippet: String,
    /// `article` or `page`.
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    pub score: u32,
}

pub struct Index {
    docs: Vec<Doc>,
}

fn tokens(query: &str) -> Vec<String> {
    let mut out: Vec<String> = query
        .to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| !t.is_empty())
        .map(str::to_string)
        .collect();
    out.dedup();
    out.truncate(8);
    out
}

fn floor_boundary(s: &str, mut i: usize) -> usize {
    i = i.min(s.len());
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    i
}

fn ceil_boundary(s: &str, mut i: usize) -> usize {
    i = i.min(s.len());
    while i < s.len() && !s.is_char_boundary(i) {
        i += 1;
    }
    i
}

/// Counts whole-word, prefix and substring matches of `term` in `hay`.
fn word_score(hay: &str, term: &str, exact: u32, prefix: u32, inner: u32) -> u32 {
    let mut best = 0;
    for word in hay.split(|c: char| !c.is_alphanumeric()) {
        if word.is_empty() {
            continue;
        }
        let s = if word == term {
            exact
        } else if word.starts_with(term) {
            prefix
        } else if word.contains(term) {
            inner
        } else {
            0
        };
        best = best.max(s);
        if best == exact {
            break;
        }
    }
    best
}

impl Index {
    pub fn build(directory: &[DirSection], feed: &[FeedItem], pages: &[Arc<CachedPage>]) -> Index {
        struct Draft {
            title: String,
            description: String,
            tags: String,
            body: String,
            kind: &'static str,
            image: Option<String>,
        }
        let mut drafts: HashMap<String, Draft> = HashMap::new();
        let blank = |kind| Draft {
            title: String::new(),
            description: String::new(),
            tags: String::new(),
            body: String::new(),
            kind,
            image: None,
        };
        for section in directory {
            for e in &section.entries {
                let d = drafts.entry(e.path.clone()).or_insert_with(|| blank("page"));
                d.title = e.title.clone();
                d.description = e.description.clone();
            }
        }
        for item in feed {
            let d = drafts.entry(item.path.clone()).or_insert_with(|| blank("article"));
            d.kind = "article";
            d.title = item.title.clone();
            if !item.summary.is_empty() {
                d.description = item.summary.clone();
            }
            d.tags = item.tags.join(" ");
            d.image = item.image.clone();
        }
        for cp in pages {
            let p = &cp.page;
            let d = drafts.entry(p.path.clone()).or_insert_with(|| blank("page"));
            if p.kind == "article" {
                d.kind = "article";
            }
            if d.title.is_empty() {
                d.title = if p.heading.is_empty() {
                    p.title.clone()
                } else {
                    p.heading.clone()
                };
            }
            if d.description.is_empty() {
                d.description = p.description.clone();
            }
            d.body = cp.text.clone();
        }
        let mut docs: Vec<Doc> = drafts
            .into_iter()
            .filter(|(_, d)| !d.title.is_empty())
            .map(|(path, d)| Doc {
                title_lc: d.title.to_lowercase(),
                desc_lc: d.description.to_lowercase(),
                tags_lc: d.tags.to_lowercase(),
                body_lc: d.body.to_lowercase(),
                path,
                title: d.title,
                description: d.description,
                body: d.body,
                kind: d.kind,
                image: d.image,
            })
            .collect();
        docs.sort_by(|a, b| a.path.cmp(&b.path));
        Index { docs }
    }

    pub fn len(&self) -> usize {
        self.docs.len()
    }

    pub fn search(&self, query: &str, limit: usize) -> Vec<Hit> {
        let terms = tokens(query);
        if terms.is_empty() {
            return Vec::new();
        }
        let phrase = query.trim().to_lowercase();
        let mut hits: Vec<Hit> = Vec::new();
        for doc in &self.docs {
            let mut score = 0u32;
            let mut all = true;
            for term in &terms {
                let mut s = word_score(&doc.title_lc, term, 12, 7, 3);
                s += word_score(&doc.tags_lc, term, 6, 4, 2);
                s += word_score(&doc.desc_lc, term, 4, 3, 1);
                let body_hits = doc.body_lc.matches(term.as_str()).take(6).count() as u32;
                s += body_hits;
                if s == 0 {
                    all = false;
                    break;
                }
                score += s;
            }
            if !all {
                continue;
            }
            if terms.len() > 1 {
                if doc.title_lc.contains(&phrase) {
                    score += 15;
                } else if doc.body_lc.contains(&phrase) || doc.desc_lc.contains(&phrase) {
                    score += 5;
                }
            }
            hits.push(Hit {
                path: doc.path.clone(),
                title: doc.title.clone(),
                snippet: snippet(doc, &terms),
                kind: doc.kind.to_string(),
                image: doc.image.clone(),
                score,
            });
        }
        hits.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.title.cmp(&b.title)));
        hits.truncate(limit);
        hits
    }
}

/// A short excerpt around the first matching term.
fn snippet(doc: &Doc, terms: &[String]) -> String {
    const BEFORE: usize = 60;
    const AFTER: usize = 150;
    // Lowercasing can change byte lengths for a few scripts; only slice the
    // original text when offsets are guaranteed to line up.
    if doc.body.len() == doc.body_lc.len() {
        let first = terms.iter().filter_map(|t| doc.body_lc.find(t.as_str())).min();
        if let Some(pos) = first {
            let mut start = floor_boundary(&doc.body, pos.saturating_sub(BEFORE));
            if start > 0 {
                // Start on a word, not in the middle of one.
                if let Some(ws) = doc.body[start..pos].find(char::is_whitespace) {
                    start = ceil_boundary(&doc.body, start + ws + 1);
                }
            }
            let end = floor_boundary(&doc.body, pos + AFTER);
            let mut text: String = doc.body[start..end]
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ");
            if start > 0 {
                text.insert(0, '…');
            }
            if end < doc.body.len() {
                text.push('…');
            }
            return text;
        }
    }
    doc.description.clone()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::DirEntry;

    #[test]
    fn ranks_title_matches_first_and_requires_all_terms() {
        let dir = vec![DirSection {
            title: "x".into(),
            entries: vec![
                DirEntry {
                    path: "/safety".into(),
                    title: "Safety Center".into(),
                    description: "Report and get help.".into(),
                },
                DirEntry {
                    path: "/events".into(),
                    title: "Community Events".into(),
                    description: "VRChat meetups and safety notes.".into(),
                },
            ],
        }];
        let idx = Index::build(&dir, &[], &[]);
        let hits = idx.search("safety", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].path, "/safety");
        assert_eq!(idx.search("safety vrchat", 10).len(), 1);
        assert!(idx.search("zzz", 10).is_empty());
        assert!(idx.search("  ", 10).is_empty());
    }
}
