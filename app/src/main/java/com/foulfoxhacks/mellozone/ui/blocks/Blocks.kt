package com.foulfoxhacks.mellozone.ui.blocks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.foulfoxhacks.mellozone.R
import com.foulfoxhacks.mellozone.core.Action
import com.foulfoxhacks.mellozone.core.Block
import com.foulfoxhacks.mellozone.core.Span
import com.foulfoxhacks.mellozone.ui.components.RemoteImage
import com.foulfoxhacks.mellozone.ui.theme.Brand
import com.foulfoxhacks.mellozone.ui.theme.LeadStyle

/** Developer option: draw each block's bounds and type. */
val LocalBlockOutlines = compositionLocalOf { false }

private val CardShape = RoundedCornerShape(20.dp)
private val ImageShape = RoundedCornerShape(16.dp)

private fun isInternal(href: String): Boolean =
    href.startsWith("https://mellozone.site") || href.startsWith("https://www.mellozone.site")

/** Builds styled, tappable text from engine spans. */
@Composable
fun spansToText(
    spans: List<Span>,
    onLink: (String) -> Unit,
    emphasis: Color = Color.Unspecified,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val open by rememberUpdatedState(onLink)
    return remember(spans, linkColor, emphasis) {
        buildAnnotatedString {
            for (span in spans) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic && emphasis == Color.Unspecified) FontStyle.Italic else null,
                    color = if (span.italic) emphasis else Color.Unspecified,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                    fontSize = if (span.small) 0.85.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
                val href = span.href
                if (href != null) {
                    val link = LinkAnnotation.Clickable(
                        tag = href,
                        styles = TextLinkStyles(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        ),
                    ) { open(href) }
                    withLink(link) { withStyle(style) { append(span.text) } }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.displaySmall
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    else -> MaterialTheme.typography.titleMedium
}

/** Space above a block, so headings breathe and related text stays together. */
private fun topSpace(block: Block, first: Boolean) = when {
    first -> 0.dp
    block is Block.Heading && block.level <= 2 -> 26.dp
    block is Block.Heading -> 20.dp
    block is Block.Eyebrow -> 26.dp
    block is Block.Divider -> 10.dp
    else -> 14.dp
}

@Composable
fun BlockColumn(
    blocks: List<Block>,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val space = when {
                index == 0 -> 0.dp
                previous is Block.Eyebrow -> 6.dp
                compact -> 10.dp
                else -> topSpace(block, false)
            }
            if (space > 0.dp) Spacer(Modifier.height(space))
            BlockView(block, onLink)
        }
    }
}

/** Padding above a top-level block in a page list. */
fun pageSpacing(blocks: List<Block>, index: Int) =
    if (index > 0 && blocks[index - 1] is Block.Eyebrow) 6.dp else topSpace(blocks[index], index == 0)

@Composable
fun BlockView(block: Block, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val outlined = LocalBlockOutlines.current
    if (outlined) {
        Column(modifier.border(1.dp, Brand.Violet.copy(alpha = 0.7f))) {
            Text(
                text = block::class.simpleName.orEmpty().lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.background(Brand.Violet).padding(horizontal = 4.dp),
            )
            BlockBody(block, onLink, Modifier)
        }
    } else {
        BlockBody(block, onLink, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockBody(block: Block, onLink: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is Block.Heading -> Text(
            text = spansToText(block.spans, onLink, emphasis = if (block.level == 1) Brand.Pink else Color.Unspecified),
            style = headingStyle(block.level),
            color = colors.onSurface,
            modifier = modifier.fillMaxWidth().semantics { heading() },
        )

        is Block.Eyebrow -> Text(
            text = block.text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.Sky,
            modifier = modifier.fillMaxWidth(),
        )

        is Block.Paragraph -> Text(
            text = spansToText(block.spans, onLink),
            style = when (block.role) {
                "lead" -> LeadStyle
                "meta" -> MaterialTheme.typography.bodyMedium
                else -> MaterialTheme.typography.bodyLarge
            },
            color = if (block.role == "meta") colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.92f),
            modifier = modifier.fillMaxWidth(),
        )

        is Block.ListBlock -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${index + 1}." else "•",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Pink,
                        modifier = Modifier.widthIn(min = 26.dp),
                    )
                    BlockColumn(item, onLink, Modifier.weight(1f), compact = true)
                }
            }
        }

        is Block.Quote -> Row(
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainer),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Brand.Pink))
            BlockColumn(block.blocks, onLink, Modifier.weight(1f).padding(16.dp), compact = true)
        }

        is Block.Image -> Column(modifier.fillMaxWidth()) {
            val ratio = if (block.width > 0 && block.height > 0) {
                (block.width.toFloat() / block.height).coerceIn(0.6f, 3f)
            } else {
                16f / 9f
            }
            RemoteImage(
                url = block.url,
                contentDescription = block.alt.ifBlank { null },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clip(ImageShape),
            )
            if (block.caption.isNotEmpty()) {
                Text(
                    text = spansToText(block.caption, onLink),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        is Block.Card -> {
            val href = block.href
            Surface(
                shape = CardShape,
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .then(if (href != null) Modifier.clickable(role = Role.Button) { onLink(href) } else Modifier),
            ) {
                Column(Modifier.padding(18.dp)) {
                    // Inside a linked card the whole surface is the link.
                    BlockColumn(block.blocks, onLink, compact = true)
                    if (href != null) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isInternal(href)) "Open" else "Opens outside the app",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            if (isInternal(href)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                    tint = colors.primary, modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    painterResource(R.drawable.ic_open_in_new), null,
                                    tint = colors.primary, modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        is Block.Details -> {
            var open by rememberSaveable(block.summary.firstOrNull()?.text) { mutableStateOf(false) }
            Surface(
                shape = CardShape,
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") {
                                open = !open
                            }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spansToText(block.summary, onLink),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.primary,
                        )
                    }
                    AnimatedVisibility(open) {
                        BlockColumn(
                            block.blocks, onLink,
                            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                            compact = true,
                        )
                    }
                }
            }
        }

        is Block.Table -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (block.caption.isNotBlank()) {
                Text(block.caption, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            // Each row becomes a card: wide tables do not fit a phone.
            block.rows.forEach { row ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceContainer,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { column, cell ->
                            if (cell.isEmpty()) return@forEachIndexed
                            val label = block.headers.getOrNull(column)?.joinToString("") { it.text }.orEmpty()
                            if (column == 0) {
                                Text(spansToText(cell, onLink), style = MaterialTheme.typography.titleMedium)
                            } else {
                                Row {
                                    if (label.isNotBlank()) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.width(104.dp),
                                        )
                                    }
                                    Text(
                                        text = spansToText(cell, onLink),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        is Block.Buttons -> FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            block.actions.forEach { action -> ActionButton(action, onLink) }
        }

        is Block.Definitions -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            block.items.forEach { item ->
                Column {
                    if (item.term.isNotBlank()) {
                        Text(
                            text = item.term,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(spansToText(item.spans, onLink), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        is Block.Form -> Surface(
            shape = CardShape,
            color = colors.secondaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = Brand.Sky, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SECURE FORM", style = MaterialTheme.typography.labelMedium, color = Brand.Sky)
                }
                if (block.title.isNotBlank()) {
                    Text(block.title, style = MaterialTheme.typography.titleLarge)
                }
                if (block.description.isNotBlank()) {
                    Text(block.description, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = "This form is protected by a browser security check, so it opens in your " +
                        "browser. Nothing you type is stored by this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Button(onClick = { onLink(FORM_PREFIX + block.url) }) {
                    Text("Open the form")
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.ic_open_in_new), null, modifier = Modifier.size(16.dp))
                }
            }
        }

        is Block.Code -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        )

        Block.Divider -> HorizontalDivider(
            modifier = modifier.padding(vertical = 6.dp),
            color = colors.outlineVariant,
        )
    }
}

/** Prefix that tells the link handler to always use the browser. */
const val FORM_PREFIX = "browser:"

@Composable
private fun ActionButton(action: Action, onLink: (String) -> Unit) {
    val external = !isInternal(action.href)
    val content: @Composable () -> Unit = {
        Text(action.label)
        if (external) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = "opens outside the app",
                modifier = Modifier.size(15.dp),
            )
        }
    }
    when (action.style) {
        "primary" -> Button(onClick = { onLink(action.href) }) { content() }
        "secondary" -> OutlinedButton(onClick = { onLink(action.href) }) { content() }
        else -> TextButton(
            onClick = { onLink(action.href) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) { content() }
    }
}
