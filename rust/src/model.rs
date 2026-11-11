//! Content model shared with the Kotlin UI as JSON.
//!
//! A page from mellozone.site is converted into a flat list of [`Block`]s
//! that the app draws with native widgets. Nothing here is HTML.

use serde::{Deserialize, Serialize};

fn is_false(b: &bool) -> bool {
    !*b
}

/// A run of inline text with one style.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Span {
    pub text: String,
    #[serde(default, skip_serializing_if = "is_false")]
    pub bold: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub italic: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub code: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub small: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub href: Option<String>,
}

/// A tappable call to action.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Action {
    pub label: String,
    pub href: String,
    /// `primary`, `secondary` or `link`.
    pub style: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Definition {
    pub term: String,
    pub spans: Vec<Span>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Block {
    Heading {
        level: u8,
        spans: Vec<Span>,
    },
    Eyebrow {
        text: String,
    },
    Paragraph {
        spans: Vec<Span>,
        /// Empty, `lead` or `meta`.
        #[serde(default)]
        role: String,
    },
    List {
        ordered: bool,
        items: Vec<Vec<Block>>,
    },
    Quote {
        blocks: Vec<Block>,
    },
    Image {
        url: String,
        alt: String,
        caption: Vec<Span>,
        width: u32,
        height: u32,
    },
    Card {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        href: Option<String>,
        blocks: Vec<Block>,
    },
    Details {
        summary: Vec<Span>,
        blocks: Vec<Block>,
    },
    Table {
        caption: String,
        headers: Vec<Vec<Span>>,
        rows: Vec<Vec<Vec<Span>>>,
    },
    Buttons {
        actions: Vec<Action>,
    },
    Definitions {
        items: Vec<Definition>,
    },
    Form {
        title: String,
        description: String,
        url: String,
    },
    Code {
        text: String,
    },
    Divider,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Page {
    pub url: String,
    pub path: String,
    /// Cleaned `<title>`.
    pub title: String,
    /// Text of the first `<h1>`, when there is one.
    pub heading: String,
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    /// `home`, `article` or `page`.
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modified: Option<String>,
    pub has_form: bool,
    pub blocks: Vec<Block>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CachedPage {
    pub page: Page,
    /// Plain text of the page body, used for search.
    pub text: String,
    #[serde(default)]
    pub etag: Option<String>,
    #[serde(default)]
    pub last_modified: Option<String>,
    pub fetched_at: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FeedItem {
    pub path: String,
    pub url: String,
    pub title: String,
    pub summary: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub date_published: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub author: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DirEntry {
    pub path: String,
    pub title: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DirSection {
    pub title: String,
    pub entries: Vec<DirEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Cached<T> {
    pub fetched_at: u64,
    pub data: T,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedItem {
    pub path: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    pub saved_at: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub text_scale: f32,
    /// Skip image downloads on metered connections.
    pub data_saver: bool,
    pub pure_black: bool,
    pub dev_bypass_cache: bool,
    pub dev_simulate_offline: bool,
    pub dev_block_outlines: bool,
    pub dev_verbose_log: bool,
    pub dev_source_badge: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            text_scale: 1.0,
            data_saver: false,
            pure_black: false,
            dev_bypass_cache: false,
            dev_simulate_offline: false,
            dev_block_outlines: false,
            dev_verbose_log: false,
            dev_source_badge: false,
        }
    }
}
