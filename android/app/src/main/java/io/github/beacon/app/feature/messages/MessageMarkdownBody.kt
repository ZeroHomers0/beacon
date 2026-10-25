package io.github.beacon.app.feature.messages

import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import io.github.beacon.app.R
import io.github.beacon.app.domain.policy.MessageMarkdown
import io.github.beacon.app.domain.policy.messageMarkdownDocuments
import io.github.beacon.app.ui.components.BeaconDetailText
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.ImageSpanFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image

private data class RenderedBody(
    val text: Spanned,
    val links: List<String>,
    val bareLinks: List<String>,
    val failed: Boolean = false,
)

/**
 * Renders one message body.
 *
 * [raw] is owned by the screen, not by this composable: the render/source switch belongs to the
 * metadata row in the header card, so the body itself must not push that row around by declaring it.
 */
@Composable
internal fun MessageMarkdownBody(body: String, raw: Boolean, modifier: Modifier = Modifier) {
    // One style for the rendered body, the source body and the failed fallback: switching between
    // them must not change the size of the text, or the screen jumps on every toggle.
    val bodyStyle =
        MaterialTheme.typography.bodyLarge.copy(
            fontSize = BeaconDetailText.BodySize,
            lineHeight = BeaconDetailText.BodyLineHeight,
        )
    if (body.isBlank()) {
        BeaconText(
            stringResource(R.string.msg_detail_no_body_hint),
            modifier,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var selectedLink by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val onLink by rememberUpdatedState<(String) -> Unit>({ link ->
        MessageMarkdown.webUrl(link)?.let { selectedLink = it }
    })
    val scheme = MaterialTheme.colorScheme
    // Markwon bakes colours into the spans it creates, so the instance — and therefore the rendered
    // text — has to be rebuilt when the scheme flips.
    val darker = scheme.surface.luminance() < .5f
    val (markwon, images) = remember(context, resources, darker) {
        val metrics = resources.displayMetrics
        fun px(value: Dp) = (value.value * metrics.density).toInt()
        fun textPx(value: TextUnit) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.value, metrics).toInt()
        val codeBackground = scheme.onSurface.copy(alpha = .06f).compositeOver(scheme.surfaceContainerLow)
        val loader = MessageMarkdownImageLoader(resources)
        val built = Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.headingTextSizeMultipliers(BeaconDetailText.HeadingMultipliers.copyOf())
                    builder.blockMargin(px(BeaconDetailText.BlockMargin))
                    builder.blockQuoteWidth(px(BeaconDetailText.QuoteWidth))
                    builder.blockQuoteColor(scheme.outline.copy(alpha = .5f).toArgb())
                    builder.codeTextSize(textPx(BeaconDetailText.CodeSize))
                    builder.codeBlockTextSize(textPx(BeaconDetailText.CodeSize))
                    builder.codeBackgroundColor(codeBackground.toArgb())
                    builder.codeBlockBackgroundColor(codeBackground.toArgb())
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> onLink(link) }
                    builder.asyncDrawableLoader(loader)
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // CorePlugin drops an Image node entirely when no factory is registered for it,
                    // so the picture used to survive only as its alt text down in a media panel.
                    // This is what puts it back where the author wrote it.
                    builder.setFactory(Image::class.java, ImageSpanFactory())
                }

                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    // HTML is visible text, never executed.
                    builder.on(HtmlInline::class.java) { visitor, html ->
                        visitor.builder().append(html.literal)
                    }
                    builder.on(HtmlBlock::class.java) { visitor, html ->
                        visitor.ensureNewLine()
                        visitor.builder().append(html.literal)
                        visitor.ensureNewLine()
                    }
                }

                // Inline pictures load only while the view is attached. Markwon 4.6.2 ships the
                // scheduler but no plugin that drives it, so the two ends are wired by hand.
                override fun beforeSetText(textView: TextView, markdown: Spanned) {
                    AsyncDrawableScheduler.unschedule(textView)
                }

                override fun afterSetText(textView: TextView) {
                    AsyncDrawableScheduler.schedule(textView)
                }
            })
            .build()
        built to loader
    }
    // A body that leaves the screen must not keep downloading in the background.
    DisposableEffect(images) {
        onDispose { images.release() }
    }
    // Parsing and rendering a full 64 KiB body happens only here, off the UI thread.
    val rendered by produceState<RenderedBody?>(null, body, markwon) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                val document = messageMarkdownDocuments.get(body)
                RenderedBody(
                    document.render { markwon.render(it) },
                    document.links,
                    document.bareLinks,
                )
            }.getOrElse {
                RenderedBody(SpannableString(body), emptyList(), emptyList(), failed = true)
            }
        }
    }
    val result = rendered
    val showingSource = raw || result?.failed == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        when {
            showingSource -> {
                if (result?.failed == true && !raw) {
                    BeaconText(stringResource(R.string.message_markdown_failed))
                }
                SelectionContainer {
                    BeaconText(body, style = bodyStyle)
                }
            }

            result == null -> SelectionContainer {
                BeaconText(body, style = bodyStyle)
            }

            else -> {
                val textColor = scheme.onSurface.toArgb()
                val linkColor = scheme.primary.toArgb()
                val density = LocalDensity.current
                val textSize = with(density) { BeaconDetailText.BodySize.toPx() }
                val lineHeight = with(density) { BeaconDetailText.BodyLineHeight.toPx() }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { TextView(it).apply { setTextIsSelectable(true); setPadding(0, 0, 0, 0) } },
                    update = { view ->
                        view.setTextColor(textColor)
                        view.setLinkTextColor(linkColor)
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                        // A TextView has no line-height property, only extra leading on top of the
                        // font's own metrics, so the wanted height is turned into a lead at the
                        // current size. Doing it here — rather than with a multiplier — keeps the
                        // rhythm [BeaconDetailText.BodyLineHeight] promises whatever the font is.
                        val metrics = view.paint.fontMetrics
                        view.setLineSpacing(lineHeight - (metrics.descent - metrics.ascent), 1f)
                        if (view.tag !== result.text) {
                            markwon.setParsedMarkdown(view, result.text)
                            view.tag = result.text
                        }
                    },
                )
            }
        }
    }
    result?.let { MessageLinksPanel(if (raw) it.links else it.bareLinks) }
    selectedLink?.let { link -> MessageLinkDialog(link) { selectedLink = null } }
}
