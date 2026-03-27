package com.neptunekit.sdk.android.examples.simulator

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.neptunekit.sdk.android.model.InspectorSnapshot
import com.neptunekit.sdk.android.model.ViewTreeFrame
import com.neptunekit.sdk.android.model.ViewTreeNode
import com.neptunekit.sdk.android.model.ViewTreeSnapshot
import com.neptunekit.sdk.android.model.ViewTreeStyle
import com.neptunekit.sdk.android.viewtree.ViewTreeCollector
import com.neptunekit.sdk.android.viewtree.ViewTreeQuery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal const val TYPOGRAPHY_UNIT_DP = "dp"
private const val SOURCE_TYPOGRAPHY_UNIT_SP = "sp"

class AndroidViewTreeCollector(
    private val activityProvider: () -> Activity?,
) : ViewTreeCollector {
    override fun captureSnapshot(query: ViewTreeQuery): ViewTreeSnapshot? =
        runOnMainThread {
            val activity = activityProvider() ?: return@runOnMainThread null
            val decorView = activity.window?.decorView ?: return@runOnMainThread null
            val platform = query.platform?.takeIf { it.isNotBlank() } ?: "android"
            ViewTreeSnapshot(
                snapshotId = "$platform-ui-tree-${System.currentTimeMillis()}",
                capturedAt = java.time.Instant.now().toString(),
                platform = platform,
                roots = listOf(buildSnapshotNode(decorView, path = "root", parentId = null)),
            )
        }

    override fun captureInspector(query: ViewTreeQuery): InspectorSnapshot? =
        runOnMainThread {
            val activity = activityProvider() ?: return@runOnMainThread null
            val decorView = activity.window?.decorView ?: return@runOnMainThread null
            val platform = query.platform?.takeIf { it.isNotBlank() } ?: "android"
            InspectorSnapshot(
                snapshotId = "$platform-inspector-${System.currentTimeMillis()}",
                capturedAt = java.time.Instant.now().toString(),
                platform = platform,
                available = true,
                payload = AndroidInspectorPayload(
                    activityClass = activity.javaClass.name,
                    roots = listOf(buildInspectorNode(decorView, path = "root", parentId = null)),
                ),
            )
        }

    private fun buildSnapshotNode(
        view: View,
        path: String,
        parentId: String?,
    ): ViewTreeNode {
        val nodeId = buildNodeId(view, path)
        val children = if (view is ViewGroup) {
            buildList {
                for (index in 0 until view.childCount) {
                    add(buildSnapshotNode(view.getChildAt(index), "$path/$index", nodeId))
                }
            }
        } else {
            emptyList()
        }

        return ViewTreeNode(
            id = nodeId,
            parentId = parentId,
            name = view.nodeName(),
            frame = view.toFrame(),
            style = view.toStyle(),
            text = view.toText(),
            visible = view.isVisible(),
            children = children,
        )
    }

    private fun buildInspectorNode(
        view: View,
        path: String,
        parentId: String?,
    ): AndroidInspectorNode {
        val nodeId = buildNodeId(view, path)
        val children = if (view is ViewGroup) {
            buildList {
                for (index in 0 until view.childCount) {
                    add(buildInspectorNode(view.getChildAt(index), "$path/$index", nodeId))
                }
            }
        } else {
            emptyList()
        }

        return AndroidInspectorNode(
            id = nodeId,
            parentId = parentId,
            name = view.nodeName(),
            className = view.javaClass.name,
            viewId = view.resourceEntryName(),
            frame = view.toFrame(),
            visible = view.isVisible(),
            alpha = view.alpha.toDouble(),
            text = view.toText(),
            contentDescription = view.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            layoutParams = view.toLayoutParams(),
            style = view.toStyle(),
            children = children,
        )
    }

    private fun buildNodeId(view: View, path: String): String {
        val resourceEntryName = view.resourceEntryName()
        return if (resourceEntryName == null) {
            path
        } else {
            "$path:$resourceEntryName"
        }
    }

    private fun View.nodeName(): String =
        javaClass.simpleName.takeIf { it.isNotBlank() } ?: javaClass.name.substringAfterLast('.')

    private fun View.resourceEntryName(): String? =
        if (id == View.NO_ID) {
            null
        } else {
            runCatching { resources.getResourceEntryName(id) }.getOrNull()
        }

    private fun View.toFrame(): ViewTreeFrame? {
        val location = IntArray(2)
        return runCatching {
            getLocationInWindow(location)
            ViewTreeFrame(
                x = location[0].toDouble(),
                y = location[1].toDouble(),
                width = width.toDouble(),
                height = height.toDouble(),
            )
        }.getOrNull()
    }

    private fun View.toStyle(): ViewTreeStyle {
        val backgroundColor = (background as? ColorDrawable)?.color?.toHexColor()
        val opacity = alpha.toDouble()
        val zIndex = z.toDouble()
        if (this !is TextView) {
            return ViewTreeStyle(
                opacity = opacity,
                backgroundColor = backgroundColor,
                zIndex = zIndex,
            )
        }

        return resolveTextTypographyStyle(
            textSizePx = textSize,
            lineHeightPx = lineHeight,
            letterSpacingEm = letterSpacing,
            density = resources.displayMetrics.density,
            platformFontScale = resources.configuration.fontScale.toDouble(),
            fontWeightRaw = buildFontWeightRawString(typeface?.style, paint.isFakeBoldText),
        ).copy(
            opacity = opacity,
            backgroundColor = backgroundColor,
            textColor = currentTextColor.toHexColor(),
            fontWeight = typeface?.toAndroidWeight(),
            zIndex = zIndex,
            textAlign = textAlignment.toAndroidTextAlign(),
        )
    }

    private fun View.toText(): String? =
        (this as? TextView)
            ?.text
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    private fun View.toLayoutParams(): AndroidLayoutParams? {
        val params = layoutParams ?: return null
        val marginParams = params as? ViewGroup.MarginLayoutParams
        return AndroidLayoutParams(
            width = params.width,
            height = params.height,
            marginLeft = marginParams?.leftMargin,
            marginTop = marginParams?.topMargin,
            marginRight = marginParams?.rightMargin,
            marginBottom = marginParams?.bottomMargin,
        )
    }

    private fun View.isVisible(): Boolean =
        visibility == View.VISIBLE && isShown && alpha > 0f

    private fun Int.toHexColor(): String =
        String.format("#%08X", this)

    private fun Typeface.toAndroidWeight(): String? =
        when (style) {
            Typeface.BOLD -> "bold"
            Typeface.ITALIC -> "italic"
            Typeface.BOLD_ITALIC -> "bold-italic"
            else -> null
        }

    private fun Int.toAndroidTextAlign(): String? =
        when (this) {
            View.TEXT_ALIGNMENT_CENTER -> "center"
            View.TEXT_ALIGNMENT_GRAVITY -> "gravity"
            View.TEXT_ALIGNMENT_INHERIT -> "inherit"
            View.TEXT_ALIGNMENT_TEXT_END -> "end"
            View.TEXT_ALIGNMENT_TEXT_START -> "start"
            View.TEXT_ALIGNMENT_VIEW_END -> "view-end"
            View.TEXT_ALIGNMENT_VIEW_START -> "view-start"
            else -> null
        }

    private fun <T> runOnMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(block())
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error.get()?.let { throw it }
        return result.get()
    }
}

internal fun resolveTextTypographyStyle(
    textSizePx: Float,
    lineHeightPx: Int,
    letterSpacingEm: Float,
    density: Float,
    platformFontScale: Double = 1.0,
    fontWeightRaw: String? = null,
): ViewTreeStyle =
    // Android 的字号、行高、字距来自不同的运行时来源：
    // textSize 语义上对应 sp，lineHeight 是 px，letterSpacing 是 em。
    // 这里用 sourceTypographyUnit=sp 作为主语义，配合 platformFontScale 和归一化后的 dp 值保留可还原信息。
    ViewTreeStyle(
        typographyUnit = TYPOGRAPHY_UNIT_DP,
        sourceTypographyUnit = SOURCE_TYPOGRAPHY_UNIT_SP,
        platformFontScale = platformFontScale,
        fontSize = textSizePx.pxToDp(density),
        lineHeight = lineHeightPx.pxToDp(density),
        letterSpacing = letterSpacingEm.toDpFromEm(textSizePx, density),
        fontWeightRaw = fontWeightRaw,
    )

internal fun buildFontWeightRawString(typefaceStyle: Int?, isFakeBoldText: Boolean): String? {
    if (typefaceStyle == null) {
        return null
    }

    return buildString {
        append("style=")
        append(typefaceStyle)
        append(",fakeBold=")
        append(isFakeBoldText)
    }
}

data class AndroidInspectorPayload(
    val activityClass: String,
    val roots: List<AndroidInspectorNode>,
)

data class AndroidInspectorNode(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val className: String,
    val viewId: String? = null,
    val frame: ViewTreeFrame? = null,
    val visible: Boolean,
    val alpha: Double,
    val text: String? = null,
    val contentDescription: String? = null,
    val layoutParams: AndroidLayoutParams? = null,
    val style: ViewTreeStyle? = null,
    val children: List<AndroidInspectorNode> = emptyList(),
)

data class AndroidLayoutParams(
    val width: Int? = null,
    val height: Int? = null,
    val marginLeft: Int? = null,
    val marginTop: Int? = null,
    val marginRight: Int? = null,
    val marginBottom: Int? = null,
)

private fun Float.pxToDp(density: Float): Double =
    (this / density).toDouble()

private fun Int.pxToDp(density: Float): Double =
    (toFloat() / density).toDouble()

private fun Float.toDpFromEm(textSizePx: Float, density: Float): Double =
    (this * textSizePx / density).toDouble()
