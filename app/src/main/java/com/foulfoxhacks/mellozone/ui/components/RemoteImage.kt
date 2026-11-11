package com.foulfoxhacks.mellozone.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foulfoxhacks.mellozone.core.Core
import com.foulfoxhacks.mellozone.core.CoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** False when data saver is on and the connection is metered. */
val LocalAllowImageDownloads = compositionLocalOf { true }

private object ImageMemory {
    private val budgetKb = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
    private val cache = object : LruCache<String, Bitmap>(budgetKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    val downloads = Semaphore(4)

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** Decodes at roughly the size it will be shown, straight to a GPU bitmap. */
private fun decode(path: String, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.HARDWARE
    }
    return BitmapFactory.decodeFile(path, options)
}

private sealed interface ImageState {
    data object Loading : ImageState
    data object Blocked : ImageState
    data object Failed : ImageState
    data class Ready(val bitmap: Bitmap) : ImageState
}

/**
 * Shows an image fetched and cached by the Rust engine. The caller sets the
 * size (usually an aspect ratio) so the layout never jumps when it arrives.
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxWidthFraction: Float = 1f,
) {
    val allowDownloads = LocalAllowImageDownloads.current
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val targetWidth = (screenWidthPx * maxWidthFraction).toInt().coerceIn(96, 1440)
    val key = "$url@$targetWidth"

    var userAllowed by remember(url) { mutableStateOf(false) }
    var state by remember(key) {
        mutableStateOf(ImageMemory.get(key)?.let { ImageState.Ready(it) } ?: ImageState.Loading)
    }

    LaunchedEffect(key, allowDownloads, userAllowed) {
        if (state is ImageState.Ready) return@LaunchedEffect
        state = ImageState.Loading
        state = try {
            val bitmap = withContext(Dispatchers.IO) {
                val path = ImageMemory.downloads.withPermit {
                    Core.imagePath(url, allowNetwork = allowDownloads || userAllowed)
                }
                decode(path, targetWidth)
            }
            if (bitmap != null) {
                ImageMemory.put(key, bitmap)
                ImageState.Ready(bitmap)
            } else {
                ImageState.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreException) {
            if (e.code == "not_cached") ImageState.Blocked else ImageState.Failed
        } catch (e: Exception) {
            ImageState.Failed
        }
    }

    val current = state
    val alpha by animateFloatAsState(
        targetValue = if (current is ImageState.Ready) 1f else 0f,
        animationSpec = tween(220),
        label = "image-fade",
    )
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (current) {
            is ImageState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
            ImageState.Blocked -> Text(
                text = "Tap to load image",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { userAllowed = true }.padding(16.dp),
            )
            ImageState.Failed -> if (!contentDescription.isNullOrBlank()) {
                Text(
                    text = contentDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
            ImageState.Loading -> {}
        }
    }
}
