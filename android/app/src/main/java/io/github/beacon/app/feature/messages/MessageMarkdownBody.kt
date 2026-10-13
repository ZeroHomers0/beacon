package io.github.beacon.app.feature.messages

import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.beacon.app.R
import io.github.beacon.app.domain.policy.MessageMarkdown
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.json.JSONObject

private data class RenderedBody(
    val text: Spanned,
    val pictures: List<MessageMarkdown.Picture>,
    val links: List<String>,
    val bareLinks: List<String>,
    val failed: Boolean = false,
)

@Composable
internal fun MessageMarkdownBody(body: String) {
    var raw by rememberSaveable { mutableStateOf(false) }
    var selectedLink by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val onLink by rememberUpdatedState<(String) -> Unit>({ link ->
        MessageMarkdown.webUrl(link)?.let { selectedLink = it }
    })
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> onLink(link) }
                }

                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    // Images are loaded explicitly below. HTML is visible text, never executed.
                    builder.on(Image::class.java) { visitor, image -> visitor.visitChildren(image) }
                    builder.on(HtmlInline::class.java) { visitor, html -> visitor.builder().append(html.literal) }
                    builder.on(HtmlBlock::class.java) { visitor, html ->
                        visitor.ensureNewLine()
                        visitor.builder().append(html.literal)
                        visitor.ensureNewLine()
                    }
                }
            })
            .build()
    }
    // Parsing/rendering a full 64 KiB body happens only in details, off the UI thread.
    val rendered by produceState<RenderedBody?>(null, body, markwon) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                val document = markwon.parse(body)
                RenderedBody(
                    markwon.render(document), MessageMarkdown.pictures(document),
                    MessageMarkdown.links(document), MessageMarkdown.links(document, includeExplicit = false),
                )
            }.getOrElse { RenderedBody(SpannableString(body), emptyList(), emptyList(), emptyList(), failed = true) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BeaconTextButton(onClick = { raw = !raw }) {
            BeaconText(stringResource(if (raw) R.string.message_markdown_rendered else R.string.message_markdown_source))
        }
        if (raw || rendered?.failed == true || body.isBlank()) {
            if (rendered?.failed == true && !raw)
                BeaconText(stringResource(R.string.message_markdown_failed))
            SelectionContainer {
                BeaconText(body.ifBlank { stringResource(R.string.msg_detail_no_body_hint) }, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            val result = rendered
            if (result == null) BeaconText(stringResource(R.string.message_markdown_loading))
            else {
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val linkColor = MaterialTheme.colorScheme.primary.toArgb()
                val textSize = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toPx() }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { TextView(it).apply { setTextIsSelectable(true); setPadding(0, 0, 0, 0) } },
                    update = { view ->
                        view.setTextColor(textColor)
                        view.setLinkTextColor(linkColor)
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                        if (view.tag !== result.text) {
                            markwon.setParsedMarkdown(view, result.text)
                            view.tag = result.text
                        }
                    },
                )
                result.pictures.forEachIndexed { index, picture ->
                    key(picture.url) {
                        MessageMediaPanel(
                            JSONObject().put("image", picture.url).toString(),
                            picture.description.ifBlank { stringResource(R.string.message_markdown_image_number, index + 1) },
                        )
                    }
                }
            }
        }
    }
    rendered?.let { MessageLinksPanel(if (raw) it.links else it.bareLinks) }
    selectedLink?.let { link -> MessageLinkDialog(link) { selectedLink = null } }
}
