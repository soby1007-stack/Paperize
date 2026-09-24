package com.anthonyla.paperize.presentation.screens.wallpaper.components
import com.anthonyla.paperize.presentation.theme.AppMaxWidths
import com.anthonyla.paperize.presentation.theme.AppBorderWidths
import com.anthonyla.paperize.core.constants.Constants

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.exifinterface.media.ExifInterface
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.domain.model.WallpaperEffects
import com.anthonyla.paperize.presentation.theme.AppShapes
import com.anthonyla.paperize.presentation.theme.AppSpacing
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "WallpaperPreview"

/**
 * Displays the home and lock wallpapers Paperize last applied.
 *
 * The images are loaded from the recorded source URIs ([homeWallpaperUri]/[lockWallpaperUri]) via
 * Coil, rather than read back from WallpaperManager.getDrawable(): the latter is privacy-restricted
 * on modern Android and returns null without the optional all-files-access permission, which left
 * the preview blank. The URIs already carry the album's persisted read access, so no extra
 * permission is needed. They update reactively whenever Paperize changes the wallpaper.
 *
 * The preview mirrors the static renderer (WallpaperUtil) so that changing a setting is
 * visible immediately, even before the platform finishes applying the new wallpaper:
 * - scaling: FILL = center-crop, FIT = letterbox on black, STRETCH = distort to the screen,
 *   NONE = native pixels centered on black (capped at 2x the screen like the renderer)
 * - effects: darken, grayscale, blur (radius scaled to the preview) and vignette
 * Adaptive brightness is not mirrored because it depends on the current dark/light mode.
 *
 * Adapts to the device screen aspect ratio and respects the app's animate setting.
 */
@Composable
fun CurrentWallpaperPreview(
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    animate: Boolean = true,
    homeScalingType: ScalingType = ScalingType.FILL,
    lockScalingType: ScalingType = ScalingType.FILL,
    homeEffects: WallpaperEffects = WallpaperEffects.none(),
    lockEffects: WallpaperEffects = WallpaperEffects.none(),
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current

    // Portrait-oriented preview: shorter screen dimension as width.
    val screenAspectRatio = remember(configuration) {
        val screenWidth = configuration.screenWidthDp.toFloat()
        val screenHeight = configuration.screenHeightDp.toFloat()
        min(screenWidth, screenHeight) / max(screenWidth, screenHeight)
    }

    // Full display width in pixels (portrait), the canvas size the static renderer targets.
    val screenWidthPx = remember(configuration) { portraitScreenWidthPx(context) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = AppMaxWidths.contentMaxWidth)
            .padding(horizontal = AppSpacing.small, vertical = AppSpacing.extraSmall),
        shape = AppShapes.cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.large)
        ) {
            Text(
                text = stringResource(R.string.current_wallpapers),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AppSpacing.medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                // Lock wallpaper preview (on the left)
                WallpaperPreviewBox(
                    wallpaperUri = lockWallpaperUri,
                    aspectRatio = screenAspectRatio,
                    screenWidthPx = screenWidthPx,
                    scalingType = lockScalingType,
                    effects = lockEffects,
                    contentDescription = stringResource(R.string.content_desc_current_lock_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )

                // Home wallpaper preview (on the right)
                WallpaperPreviewBox(
                    wallpaperUri = homeWallpaperUri,
                    aspectRatio = screenAspectRatio,
                    screenWidthPx = screenWidthPx,
                    scalingType = homeScalingType,
                    effects = homeEffects,
                    contentDescription = stringResource(R.string.content_desc_current_home_wallpaper),
                    animate = animate,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Small always-visible copy of the preview, shown while the big preview is scrolled out of view
 * (e.g. while adjusting the effects further down), so changes can be seen without scrolling back.
 * Tapping it folds it into a small round button (and back) so it never blocks a setting.
 */
@Composable
fun FloatingWallpaperPreview(
    visible: Boolean,
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    homeScalingType: ScalingType,
    lockScalingType: ScalingType,
    homeEffects: WallpaperEffects,
    lockEffects: WallpaperEffects,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val screenAspectRatio = remember(configuration) {
        val w = configuration.screenWidthDp.toFloat()
        val h = configuration.screenHeightDp.toFloat()
        min(w, h) / max(w, h)
    }
    val screenWidthPx = remember(configuration) { portraitScreenWidthPx(context) }
    var folded by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible && (lockWallpaperUri != null || homeWallpaperUri != null),
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier
    ) {
        if (folded) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { folded = false }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = stringResource(R.string.current_wallpapers),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier.clickable { folded = true }
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (lockWallpaperUri != null) {
                        WallpaperPreviewBox(
                            wallpaperUri = lockWallpaperUri,
                            aspectRatio = screenAspectRatio,
                            screenWidthPx = screenWidthPx,
                            scalingType = lockScalingType,
                            effects = lockEffects,
                            contentDescription = stringResource(R.string.content_desc_current_lock_wallpaper),
                            animate = animate,
                            modifier = Modifier.width(FLOATING_PREVIEW_WIDTH)
                        )
                    }
                    if (homeWallpaperUri != null) {
                        WallpaperPreviewBox(
                            wallpaperUri = homeWallpaperUri,
                            aspectRatio = screenAspectRatio,
                            screenWidthPx = screenWidthPx,
                            scalingType = homeScalingType,
                            effects = homeEffects,
                            contentDescription = stringResource(R.string.content_desc_current_home_wallpaper),
                            animate = animate,
                            modifier = Modifier.width(FLOATING_PREVIEW_WIDTH)
                        )
                    }
                }
            }
        }
    }
}

private val FLOATING_PREVIEW_WIDTH = 72.dp

/**
 * A single wallpaper preview. Shows a placeholder background until [wallpaperUri] is non-null,
 * then fades in the image rendered with the same scaling/effects as the real wallpaper.
 */
@Composable
private fun WallpaperPreviewBox(
    wallpaperUri: String?,
    aspectRatio: Float,
    screenWidthPx: Int,
    scalingType: ScalingType,
    effects: WallpaperEffects,
    contentDescription: String,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // NONE needs the source's real pixel size (the thumbnail Coil decodes is downsampled).
    val sourceSize by produceState<IntArraySize?>(null, wallpaperUri, scalingType) {
        value = if (scalingType == ScalingType.NONE && wallpaperUri != null) {
            readSourceSize(context, wallpaperUri)
        } else {
            null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .border(
                width = AppBorderWidths.thick,
                color = Color.Black,
                shape = AppShapes.imageShape
            )
            .clip(AppShapes.imageShape)
            .background(
                // FIT / NONE render on black, exactly like the real wallpaper.
                if (wallpaperUri != null && scalingType != ScalingType.FILL && scalingType != ScalingType.STRETCH) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
    ) {
        val previewWidthPx = with(density) { maxWidth.toPx() }
        // Ratio between the preview and the real screen, used for pixel-based sizes.
        val previewRatio = if (screenWidthPx > 0) previewWidthPx / screenWidthPx else 1f

        val contentScale = remember(scalingType, sourceSize, previewRatio, screenWidthPx) {
            when (scalingType) {
                ScalingType.FILL -> ContentScale.Crop
                ScalingType.FIT -> ContentScale.Fit
                ScalingType.STRETCH -> ContentScale.FillBounds
                ScalingType.NONE -> sourceSize?.let { NativePixelScale(it, previewRatio, screenWidthPx, aspectRatio) }
                    ?: ContentScale.Inside
            }
        }

        val colorFilter = remember(effects) { effectsColorFilter(effects) }
        val blurRadiusPx = if (effects.enableBlur && effects.blurPercentage > 0) {
            effects.blurPercentage.coerceIn(0, 100) / 100f * Constants.MAX_BLUR_RADIUS * previewRatio
        } else 0f

        AnimatedVisibility(
            visible = wallpaperUri != null,
            enter = fadeIn(animationSpec = tween(if (animate) Constants.PERMISSION_SCREEN_TRANSITION_DELAY_MS.toInt() else 0)),
            exit = fadeOut(animationSpec = tween(if (animate) Constants.PERMISSION_SCREEN_TRANSITION_DELAY_MS.toInt() else 0)),
            modifier = Modifier.matchParentSize()
        ) {
            wallpaperUri?.let { uri ->
                Box(modifier = Modifier.matchParentSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .size(Size(Constants.PREVIEW_THUMBNAIL_WIDTH, Constants.PREVIEW_THUMBNAIL_HEIGHT))
                            .crossfade(true)
                            .build(),
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        alignment = Alignment.Center,
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .matchParentSize()
                            .then(
                                if (blurRadiusPx > 0f) {
                                    Modifier.blur(
                                        with(density) { blurRadiusPx.toDp() },
                                        BlurredEdgeTreatment.Rectangle
                                    )
                                } else Modifier
                            )
                            .then(
                                if (effects.enableVignette && effects.vignettePercentage > 0) {
                                    Modifier.vignette(effects.vignettePercentage, previewRatio)
                                } else Modifier
                            ),
                        onError = { state ->
                            Log.w(TAG, "preview load failed for $uri: ${state.result.throwable}")
                        }
                    )
                }
            }
        }
    }
}

/** Width/height pair (avoids pulling in a UI geometry type for a background read). */
private data class IntArraySize(val width: Int, val height: Int)

/**
 * ContentScale for [ScalingType.NONE]: shows the source at its native pixel size relative to the
 * real screen (so a small photo stays small, a large one is cropped), mirroring WallpaperUtil,
 * which caps decoding at 2x the screen size.
 */
private class NativePixelScale(
    private val source: IntArraySize,
    private val previewRatio: Float,
    private val screenWidthPx: Int,
    private val screenAspect: Float
) : ContentScale {
    override fun computeScaleFactor(srcSize: GeometrySize, dstSize: GeometrySize): ScaleFactor {
        if (srcSize.width <= 0f || srcSize.height <= 0f) return ScaleFactor(1f, 1f)
        // EXIF rotation may swap the orientation Coil reports; match it.
        val srcPortrait = srcSize.height >= srcSize.width
        val srcPortraitRaw = source.height >= source.width
        var nativeW = if (srcPortrait == srcPortraitRaw) source.width.toFloat() else source.height.toFloat()
        var nativeH = if (srcPortrait == srcPortraitRaw) source.height.toFloat() else source.width.toFloat()

        // Renderer cap: decode no larger than 2x the screen in either dimension.
        val screenW = screenWidthPx.toFloat()
        val screenH = if (screenAspect > 0f) screenW / screenAspect else screenW
        val cap = min(1f, min(screenW * 2 / nativeW, screenH * 2 / nativeH))
        nativeW *= cap
        nativeH *= cap

        val scale = nativeW * previewRatio / srcSize.width
        return ScaleFactor(scale, scale)
    }
}

/** Darken + grayscale as one color matrix, matching WallpaperUtil's math. */
private fun effectsColorFilter(effects: WallpaperEffects): ColorFilter? {
    val darken = if (effects.enableDarken) effects.darkenPercentage.coerceIn(0, 100) else 0
    val gray = if (effects.enableGrayscale) effects.grayscalePercentage.coerceIn(0, 100) else 0
    if (darken == 0 && gray == 0) return null

    val matrix = ColorMatrix()
    if (gray > 0) matrix.setToSaturation(1f - gray / 100f)
    if (darken > 0) {
        val factor = (100 - darken) / 100f
        matrix.timesAssign(ColorMatrix().apply { setToScale(factor, factor, factor, 1f) })
    }
    return ColorFilter.colorMatrix(matrix)
}

/** Radial vignette overlay with the renderer's radius formula and gradient stops. */
private fun Modifier.vignette(percent: Int, previewRatio: Float): Modifier = drawWithContent {
    drawContent()
    val halfW = size.width / 2f
    val halfH = size.height / 2f
    val diagonal = sqrt(halfW * halfW + halfH * halfH)
    val radius = (diagonal * (1 - (percent.coerceIn(0, 100) / Constants.VIGNETTE_DIVISOR)))
        .coerceAtLeast(Constants.VIGNETTE_MIN_RADIUS * previewRatio)
        .coerceAtLeast(1f)
    val stops = Constants.VIGNETTE_GRADIENT_POSITIONS
    val colors = listOf(
        Color.Transparent,
        Color.Black.copy(alpha = Constants.VIGNETTE_INNER_ALPHA),
        Color.Black.copy(alpha = Constants.VIGNETTE_OUTER_ALPHA)
    )
    drawRect(
        brush = Brush.radialGradient(
            stops[0] to colors[0],
            stops[1] to colors[1],
            stops[2] to colors[2],
            center = Offset(halfW, halfH),
            radius = radius
        )
    )
}

/** Reads the source image's pixel size without decoding it. */
private suspend fun readSourceSize(context: Context, uri: String): IntArraySize? =
    withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.w(TAG, "could not read size of $uri")
                return@withContext null
            }
            val rotation = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                ExifInterface(it).rotationDegrees
            } ?: 0
            if (rotation == 90 || rotation == 270) {
                IntArraySize(options.outHeight, options.outWidth)
            } else {
                IntArraySize(options.outWidth, options.outHeight)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reading size of $uri failed: $e")
            null
        }
    }

/** Portrait width of the whole display in pixels (what the static renderer targets). */
private fun portraitScreenWidthPx(context: Context): Int = try {
    val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    min(bounds.width(), bounds.height())
} catch (e: Exception) {
    Log.w(TAG, "window metrics unavailable: $e")
    context.resources.displayMetrics.let { min(it.widthPixels, it.heightPixels) }
}
