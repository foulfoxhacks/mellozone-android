//! Converts a mellozone.site HTML document into native content blocks.
//!
//! The site is semantic, server-rendered HTML, so the parser walks `<main>`
//! and maps elements to [`Block`]s by tag and structure rather than by
//! page-specific class names. That keeps the app working when pages change.

use crate::model::{Action, Block, Definition, DirEntry, DirSection, Page, Span};
use scraper::{ElementRef, Html, Node, Selector};
use url::Url;

/// Marks a `<br>` while inline text is being collected.
const BR: char = '\u{1}';
/// Placeholder href for links that only point inside the current page.
const FRAGMENT: &str = "#";

#[derive(Clone, Default)]
struct Style {
    bold: bool,
    italic: bool,
    code: bool,
    small: bool,
    href: Option<String>,
}

struct Ctx<'a> {
    base: &'a Url,
}

fn sel(s: &str) -> Selector {
    Selector::parse(s).expect("static selector")
}

fn elems<'a>(el: ElementRef<'a>) -> impl Iterator<Item = ElementRef<'a>> {
    el.children().filter_map(ElementRef::wrap)
}

fn class_contains(el: ElementRef, needle: &str) -> bool {
    el.value().classes().any(|c| c.contains(needle))
}

fn is_hidden(el: ElementRef) -> bool {
    let v = el.value();
    if v.attr("aria-hidden") == Some("true") || v.attr("hidden").is_some() {
        return true;
    }
    matches!(
        v.name(),
        "script"
            | "style"
            | "svg"
            | "template"
            | "noscript"
            | "iframe"
            | "button"
            | "input"
            | "select"
            | "textarea"
            | "label"
            | "source"
            | "link"
            | "meta"
    )
}

fn is_inline_tag(name: &str) -> bool {
    matches!(
        name,
        "a" | "span"
            | "strong"
            | "b"
            | "em"
            | "i"
            | "u"
            | "s"
            | "code"
            | "kbd"
            | "samp"
            | "small"
            | "time"
            | "br"
            | "abbr"
            | "sub"
            | "sup"
            | "mark"
            | "cite"
            | "q"
            | "wbr"
            | "data"
            | "var"
            | "del"
            | "ins"
    )
}

fn is_block_tag(name: &str) -> bool {
    matches!(
        name,
        "p" | "div"
            | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "ul"
            | "ol"
            | "li"
            | "figure"
            | "img"
            | "picture"
            | "table"
            | "blockquote"
            | "section"
            | "article"
            | "details"
            | "dl"
            | "form"
            | "header"
            | "footer"
            | "aside"
            | "nav"
            | "pre"
            | "hr"
            | "address"
    )
}

fn contains_block(el: ElementRef) -> bool {
    el.descendants()
        .skip(1)
        .filter_map(ElementRef::wrap)
        .any(|d| is_block_tag(d.value().name()))
}

fn has_direct_text(el: ElementRef) -> bool {
    el.children().any(|c| match c.value() {
        Node::Text(t) => !t.trim().is_empty(),
        _ => false,
    })
}

/// An `<a>` that acts as a button or wraps a whole card, not a link in a sentence.
fn is_block_anchor(el: ElementRef) -> bool {
    if el.value().name() != "a" {
        return false;
    }
    if class_contains(el, "button") || contains_block(el) {
        return true;
    }
    let visible = elems(el).filter(|c| !is_hidden(*c)).count();
    visible >= 2 && !has_direct_text(el)
}

fn is_inline(el: ElementRef) -> bool {
    let name = el.value().name();
    is_inline_tag(name) && !is_block_anchor(el) && !contains_block(el)
}

impl Ctx<'_> {
    fn resolve(&self, href: &str) -> Option<String> {
        let h = href.trim();
        if h.is_empty() {
            return None;
        }
        if h.starts_with('#') {
            return Some(FRAGMENT.to_string());
        }
        let mut u = self.base.join(h).ok()?;
        match u.scheme() {
            "http" | "https" => {}
            "mailto" | "tel" => return Some(u.to_string()),
            _ => return None,
        }
        let same_site = u.host_str() == self.base.host_str();
        if same_site {
            if u.fragment().is_some() && u.path() == self.base.path() {
                return Some(FRAGMENT.to_string());
            }
            u.set_fragment(None);
        }
        Some(u.to_string())
    }
}

// ---------------------------------------------------------------- inline --

fn push_text(out: &mut Vec<Span>, text: &str, st: &Style) {
    if text.is_empty() {
        return;
    }
    if let Some(last) = out.last_mut() {
        if last.bold == st.bold
            && last.italic == st.italic
            && last.code == st.code
            && last.small == st.small
            && last.href == st.href
        {
            last.text.push_str(text);
            return;
        }
    }
    out.push(Span {
        text: text.to_string(),
        bold: st.bold,
        italic: st.italic,
        code: st.code,
        small: st.small,
        href: st.href.clone(),
    });
}

fn inline_children(el: ElementRef, st: &Style, cx: &Ctx, out: &mut Vec<Span>) {
    for child in el.children() {
        match child.value() {
            Node::Text(t) => push_text(out, t, st),
            Node::Element(_) => {
                if let Some(c) = ElementRef::wrap(child) {
                    inline_element(c, st, cx, out);
                }
            }
            _ => {}
        }
    }
}

fn ends_with_space(out: &[Span]) -> bool {
    out.last()
        .and_then(|s| s.text.chars().last())
        .map(|c| c.is_whitespace() || c == BR)
        .unwrap_or(true)
}

fn inline_element(c: ElementRef, st: &Style, cx: &Ctx, out: &mut Vec<Span>) {
    if is_hidden(c) {
        return;
    }
    let mut next = st.clone();
    match c.value().name() {
        "br" => {
            push_text(out, &BR.to_string(), st);
            return;
        }
        "img" | "picture" | "wbr" => return,
        "strong" | "b" => next.bold = true,
        "em" | "i" | "cite" | "var" => next.italic = true,
        "code" | "kbd" | "samp" => next.code = true,
        "small" => {
            // "18,357<small>Organizer-reported</small>" reads as two lines.
            if !ends_with_space(out) {
                push_text(out, &BR.to_string(), st);
            }
            next.small = true;
        }
        "a" => {
            if let Some(href) = c.value().attr("href").and_then(|h| cx.resolve(h)) {
                next.href = Some(href);
            }
        }
        _ => {}
    }
    inline_children(c, &next, cx, out);
}

/// Collapses whitespace the way a browser would and trims the run.
fn normalize(spans: Vec<Span>) -> Vec<Span> {
    let mut out: Vec<Span> = Vec::with_capacity(spans.len());
    let mut pending_space = false;
    let mut line_start = true;
    let mut any = false;
    let mut newlines = 0u8;
    for s in spans {
        let mut buf = String::with_capacity(s.text.len());
        for ch in s.text.chars() {
            if ch == BR {
                pending_space = false;
                if any && newlines < 2 {
                    buf.push('\n');
                    newlines += 1;
                }
                line_start = true;
            } else if ch.is_whitespace() {
                if !line_start {
                    pending_space = true;
                }
            } else {
                if pending_space {
                    // Keep the space out of a styled span when it can sit
                    // at the end of the previous one.
                    match out.last_mut() {
                        Some(prev) if buf.is_empty() => prev.text.push(' '),
                        _ => buf.push(' '),
                    }
                    pending_space = false;
                }
                buf.push(ch);
                line_start = false;
                any = true;
                newlines = 0;
            }
        }
        if buf.is_empty() {
            continue;
        }
        match out.last_mut() {
            Some(prev)
                if prev.bold == s.bold
                    && prev.italic == s.italic
                    && prev.code == s.code
                    && prev.small == s.small
                    && prev.href == s.href =>
            {
                prev.text.push_str(&buf)
            }
            _ => out.push(Span { text: buf, ..s }),
        }
    }
    while let Some(last) = out.last_mut() {
        let trimmed = last.text.trim_end().len();
        last.text.truncate(trimmed);
        if last.text.is_empty() {
            out.pop();
        } else {
            break;
        }
    }
    out
}

fn spans_of(el: ElementRef, cx: &Ctx) -> Vec<Span> {
    let mut out = Vec::new();
    inline_children(el, &Style::default(), cx, &mut out);
    normalize(out)
}

pub fn spans_text(spans: &[Span]) -> String {
    spans.iter().map(|s| s.text.as_str()).collect()
}

fn text_of(el: ElementRef, cx: &Ctx) -> String {
    spans_text(&spans_of(el, cx)).replace('\n', " ")
}

fn strip_arrows(label: &str) -> String {
    label
        .trim()
        .trim_end_matches(|c: char| matches!(c, '→' | '↗' | '›' | '»' | '➜') || c.is_whitespace())
        .to_string()
}

/// A run made only of links ("Join Discord", a row of nav links) is drawn as
/// buttons instead of underlined text.
fn links_only(spans: &[Span]) -> bool {
    let mut any = false;
    for s in spans {
        if s.href.is_some() {
            any = true;
        } else if s.text.chars().any(|c| c.is_alphanumeric()) {
            return false;
        }
    }
    any
}

fn spans_to_actions(spans: &[Span]) -> Vec<Action> {
    let mut actions: Vec<Action> = Vec::new();
    let mut prev_href: Option<&str> = None;
    for s in spans {
        match s.href.as_deref() {
            Some(href) => {
                if prev_href == Some(href) {
                    if let Some(last) = actions.last_mut() {
                        last.label.push_str(&s.text);
                    }
                } else {
                    actions.push(Action {
                        label: s.text.clone(),
                        href: href.to_string(),
                        style: "link".into(),
                    });
                }
                prev_href = Some(href);
            }
            None => prev_href = None,
        }
    }
    actions
        .into_iter()
        .filter(|a| a.href != FRAGMENT)
        .map(|a| Action {
            label: strip_arrows(&a.label.replace('\n', " ")),
            ..a
        })
        .filter(|a| !a.label.is_empty())
        .collect()
}

fn clear_fragment_links(mut spans: Vec<Span>) -> Vec<Span> {
    for s in &mut spans {
        if s.href.as_deref() == Some(FRAGMENT) {
            s.href = None;
        }
    }
    spans
}

/// Turns a finished inline run into a paragraph or a button row.
fn emit_run(spans: Vec<Span>, role: &str, out: &mut Vec<Block>) {
    let spans = normalize(spans);
    if spans.is_empty() {
        return;
    }
    if links_only(&spans) {
        let actions = spans_to_actions(&spans);
        if !actions.is_empty() {
            out.push(Block::Buttons { actions });
        }
        return;
    }
    out.push(Block::Paragraph {
        spans: clear_fragment_links(spans),
        role: role.to_string(),
    });
}

// ----------------------------------------------------------------- blocks --

fn walk_children(el: ElementRef, cx: &Ctx, out: &mut Vec<Block>, skip_tag: Option<&str>) {
    let mut run: Vec<Span> = Vec::new();
    let plain = Style::default();
    for child in el.children() {
        match child.value() {
            Node::Text(t) => push_text(&mut run, t, &plain),
            Node::Element(_) => {
                let Some(c) = ElementRef::wrap(child) else { continue };
                if is_hidden(c) || Some(c.value().name()) == skip_tag {
                    continue;
                }
                if is_inline(c) {
                    inline_element(c, &plain, cx, &mut run);
                } else {
                    emit_run(std::mem::take(&mut run), "", out);
                    walk_element(c, cx, out);
                }
            }
            _ => {}
        }
    }
    emit_run(run, "", out);
}

fn walk_element(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let name = c.value().name();
    match name {
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            let spans = clear_fragment_links(spans_of(c, cx));
            if !spans.is_empty() {
                let level = name.as_bytes()[1] - b'0';
                out.push(Block::Heading { level, spans });
            }
        }
        "p" => paragraph(c, cx, out),
        "ul" | "ol" => list(c, cx, out),
        "blockquote" => {
            let mut blocks = Vec::new();
            walk_children(c, cx, &mut blocks, None);
            if !blocks.is_empty() {
                out.push(Block::Quote { blocks });
            }
        }
        "figure" => figure(c, cx, out),
        "picture" | "img" => {
            if let Some(b) = image_block(c, cx, Vec::new()) {
                out.push(b);
            }
        }
        "article" | "aside" => card(c, cx, out),
        "a" => anchor(c, cx, out),
        "details" => details(c, cx, out),
        "table" => table(c, cx, out),
        "dl" => definitions(c, cx, out),
        "form" => form(c, cx, out),
        "hr" => out.push(Block::Divider),
        "pre" => {
            let text: String = c.text().collect();
            if !text.trim().is_empty() {
                out.push(Block::Code {
                    text: text.trim_end().to_string(),
                });
            }
        }
        "nav" => nav(c, cx, out),
        "section" => {
            if !out.is_empty() {
                out.push(Block::Divider);
            }
            walk_children(c, cx, out, None);
        }
        "address" => {
            let spans = clear_fragment_links(spans_of(c, cx));
            if !spans.is_empty() {
                out.push(Block::Paragraph {
                    spans,
                    role: String::new(),
                });
            }
        }
        _ => {
            if is_label_row(c) {
                let parts: Vec<String> = elems(c)
                    .map(|s| text_of(s, cx))
                    .filter(|t| !t.is_empty())
                    .collect();
                if !parts.is_empty() {
                    out.push(Block::Paragraph {
                        spans: vec![Span {
                            text: parts.join(" · "),
                            small: true,
                            ..Span::default()
                        }],
                        role: "meta".into(),
                    });
                }
            } else {
                walk_children(c, cx, out, None);
            }
        }
    }
}

/// `<div><span>Destination</span><span>Action</span></div>`: column labels
/// built from bare spans, which would otherwise run together.
fn is_label_row(c: ElementRef) -> bool {
    if has_direct_text(c) {
        return false;
    }
    let mut n = 0;
    for e in elems(c) {
        if e.value().name() != "span" || contains_block(e) || is_hidden(e) {
            return false;
        }
        n += 1;
    }
    n >= 2
}

fn paragraph(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    if class_contains(c, "eyebrow") {
        let text = text_of(c, cx);
        if !text.is_empty() {
            out.push(Block::Eyebrow { text });
        }
        return;
    }
    let role = if ["dates", "credits", "byline", "meta"]
        .iter()
        .any(|k| class_contains(c, k))
    {
        "meta"
    } else {
        ""
    };
    let mut run = Vec::new();
    inline_children(c, &Style::default(), cx, &mut run);
    emit_run(run, role, out);
}

fn list(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let ordered = c.value().name() == "ol";
    let mut items: Vec<Vec<Block>> = Vec::new();
    for li in elems(c).filter(|e| e.value().name() == "li") {
        let mut blocks = Vec::new();
        if contains_block(li) || elems(li).any(is_block_anchor) {
            walk_children(li, cx, &mut blocks, None);
        } else {
            let spans = clear_fragment_links(spans_of(li, cx));
            if !spans.is_empty() {
                blocks.push(Block::Paragraph {
                    spans,
                    role: String::new(),
                });
            }
        }
        let blocks = refine(blocks);
        if !blocks.is_empty() {
            items.push(blocks);
        }
    }
    if !items.is_empty() {
        out.push(Block::List { ordered, items });
    }
}

fn attr_u32(el: ElementRef, name: &str) -> u32 {
    el.value()
        .attr(name)
        .and_then(|v| v.trim().parse().ok())
        .unwrap_or(0)
}

/// Picks a phone-sized source for an `<img>`.
fn pick_image_url(img: ElementRef, cx: &Ctx) -> Option<String> {
    let v = img.value();
    let mut chosen: Option<String> = None;
    if let Some(srcset) = v.attr("srcset") {
        let mut candidates: Vec<(u32, &str)> = srcset
            .split(',')
            .filter_map(|part| {
                let mut it = part.split_whitespace();
                let url = it.next()?;
                let w = it.next()?.strip_suffix('w')?.parse().ok()?;
                Some((w, url))
            })
            .collect();
        candidates.sort_by_key(|c| c.0);
        chosen = candidates
            .iter()
            .find(|c| c.0 >= 800)
            .or(candidates.last())
            .map(|c| c.1.to_string());
    }
    if chosen.is_none() {
        // <picture><source media="(max-width: 720px)" srcset="mobile.webp">
        if let Some(parent) = img.parent().and_then(ElementRef::wrap) {
            if parent.value().name() == "picture" {
                chosen = elems(parent)
                    .filter(|s| s.value().name() == "source")
                    .filter(|s| s.value().attr("media").is_some_and(|m| m.contains("max-width")))
                    .filter_map(|s| s.value().attr("srcset"))
                    .filter_map(|s| s.split(',').next())
                    .filter_map(|s| s.split_whitespace().next())
                    .map(str::to_string)
                    .next();
            }
        }
    }
    let raw = chosen.or_else(|| v.attr("src").or(v.attr("data-src")).map(str::to_string))?;
    let u = cx.base.join(raw.trim()).ok()?;
    if u.scheme() != "https" {
        return None;
    }
    Some(u.to_string())
}

fn image_block(c: ElementRef, cx: &Ctx, caption: Vec<Span>) -> Option<Block> {
    let img = if c.value().name() == "img" {
        c
    } else {
        c.select(&sel("img")).next()?
    };
    let url = pick_image_url(img, cx)?;
    Some(Block::Image {
        url,
        alt: img.value().attr("alt").unwrap_or("").trim().to_string(),
        caption,
        width: attr_u32(img, "width"),
        height: attr_u32(img, "height"),
    })
}

fn figure(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let caption = c
        .select(&sel("figcaption"))
        .next()
        .map(|f| clear_fragment_links(spans_of(f, cx)))
        .unwrap_or_default();
    match image_block(c, cx, caption) {
        Some(b) => out.push(b),
        None => walk_children(c, cx, out, Some("figcaption")),
    }
}

fn card(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let visible: Vec<ElementRef> = elems(c).filter(|e| !is_hidden(*e)).collect();
    // <article><a href>…whole card…</a></article>
    if visible.len() == 1 && visible[0].value().name() == "a" && !has_direct_text(c) {
        anchor(visible[0], cx, out);
        return;
    }
    let mut blocks = Vec::new();
    walk_children(c, cx, &mut blocks, None);
    let blocks = refine(blocks);
    if !blocks.is_empty() {
        out.push(Block::Card { href: None, blocks });
    }
}

fn button_style(c: ElementRef) -> &'static str {
    if class_contains(c, "hot") || class_contains(c, "primary") {
        "primary"
    } else if class_contains(c, "button") {
        "secondary"
    } else {
        "link"
    }
}

/// A block-level `<a>`: either a button or a card that links somewhere.
fn anchor(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let href = c
        .value()
        .attr("href")
        .and_then(|h| cx.resolve(h))
        .filter(|h| h != FRAGMENT);
    let simple = !contains_block(c) && (has_direct_text(c) || class_contains(c, "button"));
    if simple {
        let label = strip_arrows(&text_of(c, cx));
        if let (Some(href), false) = (href, label.is_empty()) {
            out.push(Block::Buttons {
                actions: vec![Action {
                    label,
                    href,
                    style: button_style(c).into(),
                }],
            });
        }
        return;
    }
    let mut blocks = Vec::new();
    let plain = Style::default();
    for child in c.children() {
        match child.value() {
            Node::Text(t) => {
                let mut run = Vec::new();
                push_text(&mut run, t, &plain);
                emit_run(run, "", &mut blocks);
            }
            Node::Element(_) => {
                let Some(e) = ElementRef::wrap(child) else { continue };
                if is_hidden(e) {
                    continue;
                }
                if is_inline(e) {
                    let mut run = Vec::new();
                    inline_element(e, &plain, cx, &mut run);
                    let spans: Vec<Span> = normalize(run)
                        .into_iter()
                        .map(|s| Span { href: None, ..s })
                        .collect();
                    if spans.is_empty() {
                        continue;
                    }
                    if matches!(e.value().name(), "strong" | "b") {
                        let spans = spans.into_iter().map(|s| Span { bold: false, ..s }).collect();
                        blocks.push(Block::Heading { level: 3, spans });
                    } else {
                        let spans = spans.into_iter().map(|s| Span { small: false, ..s }).collect();
                        blocks.push(Block::Paragraph {
                            spans,
                            role: String::new(),
                        });
                    }
                } else {
                    walk_element(e, cx, &mut blocks);
                }
            }
            _ => {}
        }
    }
    let blocks = refine(blocks);
    if blocks.is_empty() {
        return;
    }
    match href {
        Some(href) => out.push(Block::Card {
            href: Some(href),
            blocks,
        }),
        None => out.extend(blocks),
    }
}

fn details(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let summary = elems(c)
        .find(|e| e.value().name() == "summary")
        .map(|s| clear_fragment_links(spans_of(s, cx)))
        .unwrap_or_default();
    let mut blocks = Vec::new();
    walk_children(c, cx, &mut blocks, Some("summary"));
    let blocks = refine(blocks);
    if summary.is_empty() && blocks.is_empty() {
        return;
    }
    out.push(Block::Details { summary, blocks });
}

fn table(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let caption = c
        .select(&sel("caption"))
        .next()
        .map(|cap| text_of(cap, cx))
        .unwrap_or_default();
    let mut headers: Vec<Vec<Span>> = Vec::new();
    let mut rows: Vec<Vec<Vec<Span>>> = Vec::new();
    for tr in c.select(&sel("tr")) {
        let cells: Vec<ElementRef> = elems(tr)
            .filter(|e| matches!(e.value().name(), "th" | "td"))
            .collect();
        if cells.is_empty() {
            continue;
        }
        let all_th = cells.iter().all(|e| e.value().name() == "th");
        let spans: Vec<Vec<Span>> = cells
            .iter()
            .map(|e| clear_fragment_links(spans_of(*e, cx)))
            .collect();
        if all_th && headers.is_empty() && rows.is_empty() {
            headers = spans;
        } else {
            rows.push(spans);
        }
    }
    if !rows.is_empty() {
        out.push(Block::Table {
            caption,
            headers,
            rows,
        });
    }
}

fn definitions(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let mut items: Vec<Definition> = Vec::new();
    for e in c.select(&sel("dt, dd")) {
        if e.value().name() == "dt" {
            items.push(Definition {
                term: text_of(e, cx),
                spans: Vec::new(),
            });
        } else {
            let spans = clear_fragment_links(spans_of(e, cx));
            match items.last_mut() {
                Some(last) if last.spans.is_empty() => last.spans = spans,
                Some(last) => {
                    last.spans.push(Span {
                        text: "\n".into(),
                        ..Span::default()
                    });
                    last.spans.extend(spans);
                }
                None => items.push(Definition {
                    term: String::new(),
                    spans,
                }),
            }
        }
    }
    if !items.is_empty() {
        out.push(Block::Definitions { items });
    }
}

/// Forms on the site are protected by a browser challenge, so the app
/// describes the form and hands off to the browser rather than imitating it.
fn form(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let title = c
        .select(&sel("h1, h2, h3, legend"))
        .next()
        .map(|h| text_of(h, cx))
        .unwrap_or_default();
    let description = c
        .select(&sel("p[data-form-description]"))
        .next()
        .or_else(|| c.select(&sel("p")).find(|p| p.value().attr("data-form-badge").is_none()))
        .map(|p| text_of(p, cx))
        .unwrap_or_default();
    out.push(Block::Form {
        title,
        description,
        url: cx.base.to_string(),
    });
}

fn nav(c: ElementRef, cx: &Ctx, out: &mut Vec<Block>) {
    let label = c.value().attr("aria-label").unwrap_or("").to_ascii_lowercase();
    if label.contains("breadcrumb") || class_contains(c, "trail") {
        return;
    }
    let mut blocks = Vec::new();
    walk_children(c, cx, &mut blocks, None);
    // Keep real destinations; drop "on this page" jump lists.
    out.extend(blocks.into_iter().filter(|b| matches!(b, Block::Buttons { .. })));
}

// ----------------------------------------------------------------- refine --

fn looks_like_eyebrow(spans: &[Span]) -> bool {
    if spans.is_empty() || spans.iter().any(|s| s.href.is_some() || s.code) {
        return false;
    }
    let text = spans_text(spans);
    let t = text.trim();
    let chars = t.chars().count();
    chars > 0
        && chars <= 48
        && !t.contains('\n')
        && !t.ends_with(|c: char| matches!(c, '.' | '?' | '!' | ':' | ';' | ','))
}

/// Tidies a block list: merges button rows, removes stray dividers, and
/// marks kickers ("Answers" above "FAQ") and lead paragraphs.
fn refine(blocks: Vec<Block>) -> Vec<Block> {
    let mut out: Vec<Block> = Vec::with_capacity(blocks.len());
    for b in blocks {
        match (out.last_mut(), b) {
            (Some(Block::Buttons { actions }), Block::Buttons { actions: more }) => {
                for a in more {
                    if !actions.iter().any(|x| x.href == a.href && x.label == a.label) {
                        actions.push(a);
                    }
                }
            }
            (Some(Block::Divider), Block::Divider) => {}
            (None, Block::Divider) => {}
            (_, b) => out.push(b),
        }
    }
    while matches!(out.last(), Some(Block::Divider)) {
        out.pop();
    }
    for i in 0..out.len() {
        let next_is_heading = matches!(out.get(i + 1), Some(Block::Heading { .. }));
        let prev_is_text = i > 0
            && matches!(
                out[i - 1],
                Block::Paragraph { .. } | Block::List { .. } | Block::Quote { .. }
            );
        let after_h1 = i > 0 && matches!(out[i - 1], Block::Heading { level: 1, .. });
        if let Block::Paragraph { spans, role } = &mut out[i] {
            if role.is_empty() && next_is_heading && !prev_is_text && looks_like_eyebrow(spans) {
                let text = spans_text(spans);
                out[i] = Block::Eyebrow { text };
            } else if role.is_empty() && after_h1 {
                *role = "lead".into();
            }
        }
    }
    out
}

// ------------------------------------------------------------------- page --

fn clean_title(raw: &str) -> String {
    let mut t = raw.trim().to_string();
    for suffix in [
        " | Mello Zone Furs",
        " | Mello Zone Furry Community",
        " | Mello Zone",
    ] {
        if let Some(stripped) = t.strip_suffix(suffix) {
            t = stripped.trim().to_string();
        }
    }
    t
}

fn meta(doc: &Html, selector: &str) -> Option<String> {
    doc.select(&sel(selector))
        .next()
        .and_then(|m| m.value().attr("content"))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

fn contains_form(blocks: &[Block]) -> bool {
    blocks.iter().any(|b| match b {
        Block::Form { .. } => true,
        Block::Card { blocks, .. } | Block::Quote { blocks } | Block::Details { blocks, .. } => {
            contains_form(blocks)
        }
        Block::List { items, .. } => items.iter().any(|i| contains_form(i)),
        _ => false,
    })
}

pub fn parse_page(html: &str, url: &Url) -> Page {
    let doc = Html::parse_document(html);
    let cx = Ctx { base: url };
    let root = doc
        .select(&sel("main"))
        .next()
        .or_else(|| doc.select(&sel("body")).next());
    let mut blocks = Vec::new();
    if let Some(root) = root {
        walk_children(root, &cx, &mut blocks, None);
    }
    let blocks = refine(blocks);

    let heading = blocks
        .iter()
        .find_map(|b| match b {
            Block::Heading { level: 1, spans } => Some(spans_text(spans).replace('\n', " ")),
            _ => None,
        })
        .unwrap_or_default();
    let title = doc
        .select(&sel("title"))
        .next()
        .map(|t| clean_title(&t.text().collect::<String>()))
        .filter(|t| !t.is_empty())
        .unwrap_or_else(|| heading.clone());
    let path = url.path().to_string();
    let kind = if path == "/" {
        "home"
    } else if path.starts_with("/blog/") && !path.starts_with("/blog/topics") {
        "article"
    } else {
        "page"
    };
    let time_attr = |selector: &str| {
        doc.select(&sel(selector))
            .next()
            .and_then(|t| t.value().attr("datetime"))
            .map(str::to_string)
    };
    Page {
        url: url.to_string(),
        path,
        title,
        heading,
        description: meta(&doc, "meta[name=\"description\"]").unwrap_or_default(),
        image: meta(&doc, "meta[property=\"og:image\"]"),
        kind: kind.into(),
        published: time_attr("time[data-published]"),
        modified: time_attr("time[data-modified]"),
        has_form: contains_form(&blocks),
        blocks,
    }
}

/// Parses the site's own page directory (`/sitemap`) into titled groups.
pub fn parse_directory(html: &str, url: &Url) -> Vec<DirSection> {
    let doc = Html::parse_document(html);
    let cx = Ctx { base: url };
    let entry = |li: ElementRef| -> Option<DirEntry> {
        let a = li.select(&sel("a[href]")).next()?;
        let href = cx.resolve(a.value().attr("href")?)?;
        let u = Url::parse(&href).ok()?;
        if u.host_str() != url.host_str() {
            return None;
        }
        let title = text_of(a, &cx);
        if title.is_empty() {
            return None;
        }
        let description = li
            .select(&sel("p"))
            .next()
            .map(|p| text_of(p, &cx))
            .or_else(|| a.value().attr("title").map(str::to_string))
            .unwrap_or_default();
        Some(DirEntry {
            path: u.path().to_string(),
            title: clean_title(&title),
            description,
        })
    };
    let mut sections: Vec<DirSection> = Vec::new();
    for section in doc.select(&sel("main section")) {
        let title = section
            .select(&sel("h2, h3"))
            .next()
            .map(|h| text_of(h, &cx))
            .unwrap_or_default();
        let entries: Vec<DirEntry> = section.select(&sel("li")).filter_map(entry).collect();
        if !entries.is_empty() {
            sections.push(DirSection { title, entries });
        }
    }
    if sections.is_empty() {
        let entries: Vec<DirEntry> = doc.select(&sel("main li")).filter_map(entry).collect();
        if !entries.is_empty() {
            sections.push(DirSection {
                title: "All pages".into(),
                entries,
            });
        }
    }
    sections
}

// ------------------------------------------------------------- plain text --

fn push_line(out: &mut String, text: &str) {
    let t = text.trim();
    if !t.is_empty() {
        out.push_str(t);
        out.push('\n');
    }
}

fn collect_text(blocks: &[Block], out: &mut String) {
    for b in blocks {
        match b {
            Block::Heading { spans, .. } | Block::Paragraph { spans, .. } => {
                push_line(out, &spans_text(spans))
            }
            Block::Eyebrow { text } | Block::Code { text } => push_line(out, text),
            Block::List { items, .. } => items.iter().for_each(|i| collect_text(i, out)),
            Block::Quote { blocks } | Block::Card { blocks, .. } => collect_text(blocks, out),
            Block::Image { alt, caption, .. } => {
                push_line(out, alt);
                push_line(out, &spans_text(caption));
            }
            Block::Details { summary, blocks } => {
                push_line(out, &spans_text(summary));
                collect_text(blocks, out);
            }
            Block::Table {
                caption,
                headers,
                rows,
            } => {
                push_line(out, caption);
                for cell in headers.iter().chain(rows.iter().flatten()) {
                    push_line(out, &spans_text(cell));
                }
            }
            Block::Buttons { actions } => actions.iter().for_each(|a| push_line(out, &a.label)),
            Block::Definitions { items } => {
                for d in items {
                    push_line(out, &d.term);
                    push_line(out, &spans_text(&d.spans));
                }
            }
            Block::Form { title, description, .. } => {
                push_line(out, title);
                push_line(out, description);
            }
            Block::Divider => {}
        }
    }
}

/// Flattens blocks into searchable text.
pub fn plain_text(blocks: &[Block]) -> String {
    let mut out = String::new();
    collect_text(blocks, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page(html: &str) -> Page {
        parse_page(html, &Url::parse("https://mellozone.site/faq").unwrap())
    }

    #[test]
    fn hero_kicker_heading_and_lead() {
        let p = page(
            r#"<html><head><title>FAQ | Mello Zone Furs</title></head><body><main>
            <nav aria-label="Breadcrumb"><a href="/">Home</a><span aria-current="page">FAQ</span></nav>
            <section><div><p>Answers</p><h1>FAQ</h1><p>Get clear answers about joining.</p></div></section>
            </main></body></html>"#,
        );
        assert_eq!(p.title, "FAQ");
        assert_eq!(p.heading, "FAQ");
        assert!(matches!(&p.blocks[0], Block::Eyebrow { text } if text == "Answers"));
        assert!(matches!(&p.blocks[1], Block::Heading { level: 1, .. }));
        assert!(matches!(&p.blocks[2], Block::Paragraph { role, .. } if role == "lead"));
        assert_eq!(p.blocks.len(), 3);
    }

    #[test]
    fn inline_links_resolve_and_whitespace_collapses() {
        let p = page(
            "<main><p>Read the\n   <a href=\"/join\">joining   guide</a>, then <strong>say hi</strong>.</p></main>",
        );
        let Block::Paragraph { spans, .. } = &p.blocks[0] else { panic!("{:?}", p.blocks) };
        assert_eq!(spans_text(spans), "Read the joining guide, then say hi.");
        assert_eq!(spans[1].href.as_deref(), Some("https://mellozone.site/join"));
        assert!(spans[3].bold);
    }

    #[test]
    fn buttons_cards_and_details() {
        let p = page(
            r##"<main>
            <div class="action-row">
              <a class="button button--hot" href="/join">How to Join <span aria-hidden="true">↗</span></a>
              <a class="button button--ghost" href="/community">Our Community</a>
            </div>
            <a href="https://discord.com/invite/x" class="platform-portal"><strong>Discord</strong><small>Onboarding and roles.</small><b aria-hidden="true">↗</b></a>
            <article><div><p>Platform</p><h3>VRChat</h3></div><p>The official group.</p><a href="https://vrc.group/M">Join VRChat</a></article>
            <details><summary>Is it 18+?</summary><p>Yes.</p></details>
            <a class="world-next" href="#portals">Jump down</a>
            </main>"##,
        );
        let Block::Buttons { actions } = &p.blocks[0] else { panic!("{:?}", p.blocks[0]) };
        assert_eq!(actions.len(), 2);
        assert_eq!(actions[0].label, "How to Join");
        assert_eq!(actions[0].style, "primary");
        assert_eq!(actions[1].style, "secondary");
        let Block::Card { href, blocks } = &p.blocks[1] else { panic!("{:?}", p.blocks[1]) };
        assert_eq!(href.as_deref(), Some("https://discord.com/invite/x"));
        assert!(matches!(&blocks[0], Block::Heading { level: 3, .. }));
        let Block::Card { href: None, blocks } = &p.blocks[2] else { panic!("{:?}", p.blocks[2]) };
        assert!(matches!(&blocks[0], Block::Eyebrow { .. }));
        assert!(matches!(blocks.last(), Some(Block::Buttons { .. })));
        assert!(matches!(&p.blocks[3], Block::Details { .. }));
        assert_eq!(p.blocks.len(), 4, "fragment-only link is dropped");
    }

    #[test]
    fn images_tables_and_forms() {
        let p = page(
            r#"<main>
            <figure><img src="/a.webp" alt="Art" width="1280" height="720"
              srcset="/a-480w.webp 480w, /a-800w.webp 800w, /a.webp 1280w"><figcaption>Credit</figcaption></figure>
            <table><caption>Editions</caption><thead><tr><th>Con</th><th>Attendance</th></tr></thead>
              <tbody><tr><th>Anthrocon</th><td>18,357<small>Reported</small></td></tr></tbody></table>
            <form method="post" action="/api/x"><h2>Contact the team</h2><input name="a"><button>Send</button></form>
            </main>"#,
        );
        let Block::Image { url, width, height, .. } = &p.blocks[0] else { panic!() };
        assert_eq!(url, "https://mellozone.site/a-800w.webp");
        assert_eq!((*width, *height), (1280, 720));
        let Block::Table { headers, rows, .. } = &p.blocks[1] else { panic!() };
        assert_eq!(headers.len(), 2);
        assert_eq!(spans_text(&rows[0][1]), "18,357\nReported");
        assert!(matches!(&p.blocks[2], Block::Form { title, .. } if title == "Contact the team"));
        assert!(p.has_form);
    }

    #[test]
    fn directory_groups() {
        let html = r#"<main><section class="directory-section"><h2>Community and website</h2><ul>
            <li><a href="/about" title="t">About Mello Zone | Belonging</a><p>Discover why.</p></li>
            <li><a href="https://example.com/">Elsewhere</a></li></ul></section></main>"#;
        let d = parse_directory(html, &Url::parse("https://mellozone.site/sitemap").unwrap());
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].entries.len(), 1);
        assert_eq!(d[0].entries[0].path, "/about");
        assert_eq!(d[0].entries[0].description, "Discover why.");
    }
}
