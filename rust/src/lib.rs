//! mellozone-core: the native engine of the Mello Zone Android app.
//!
//! It owns networking (HTTPS via rustls), converts mellozone.site pages into
//! native content blocks, keeps an offline cache, and answers search queries.
//! The Kotlin layer only draws what this crate returns.

mod android;
pub mod core;
mod diag;
mod error;
pub mod model;
mod net;
pub mod parser;
mod search;
mod store;

pub use crate::core::dispatch;
