package io.github.beacon.app.domain.policy

import java.net.URI
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Presentation only: stored, searched, copied and shared bodies retain the original text. */
object MessageMarkdown {
    private val parser = Parser.builder().build()
    private val whitespace = Regex("\\s+")
    private val webLinks = Regex("""https?://[^\s<>"\u0000-\u001f]+""", RegexOption.IGNORE_CASE)

    fun parse(body: String): Node = parser.parse(body)

    // Iterative traversal also handles deeply nested input without overflowing our call stack.
    private fun walk(root: Node, visit: (Node, Boolean) -> Unit) {
        val pending = java.util.ArrayDeque<Pair<Node, Boolean>>()
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            val (node, exiting) = pending.removeLast()
            visit(node, exiting)
            if (!exiting) {
                pending.addLast(node to true)
                var child = node.lastChild
                while (child != null) {
                    pending.addLast(child to false)
                    child = child.previous
                }
            }
        }
    }

    fun plainText(root: Node): String = buildString {
        walk(root) { node, exiting ->
            if (exiting) {
                if (node is Paragraph || node is Heading || node is ListItem || node is BlockQuote)
                    append('\n')
            } else
                when (node) {
                    is Text -> append(node.literal)
                    is Code -> append(node.literal)
                    is FencedCodeBlock -> append(node.literal).append('\n')
                    is IndentedCodeBlock -> append(node.literal).append('\n')
                    is HtmlInline -> append(node.literal)
                    is HtmlBlock -> append(node.literal).append('\n')
                    is SoftLineBreak,
                    is HardLineBreak,
                    is ThematicBreak -> append('\n')
                }
        }
    }

    /** Bounded parsing for list/notification previews, including emoji-safe truncation. */
    fun summary(body: String, limit: Int = 180, preserveLineBreaks: Boolean = false): String {
        require(limit > 0)
        val input = body.take(4096).dropLastWhile { it.isHighSurrogate() }
        val plain = runCatching { plainText(parse(input)) }.getOrDefault(input)
        val text =
            (if (preserveLineBreaks)
                    plain
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .lineSequence()
                        .joinToString("\n") { it.trim() }
                        .replace(Regex("\n{3,}"), "\n\n")
                else plain.replace(whitespace, " "))
                .trim()
                .ifEmpty { input.trim() }
        val truncated = text.codePointCount(0, text.length) > limit || input.length < body.length
        val count = minOf(limit, text.codePointCount(0, text.length))
        return text.substring(0, text.offsetByCodePoints(0, count)) + if (truncated) "…" else ""
    }

    /** A link the app is willing to hand to a browser: bounded, web, and free of credentials. */
    fun webUrl(raw: String): String? = raw.takeIf {
        it.length <= 2048 &&
            runCatching {
                    val uri = URI(it)
                    (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                        !uri.host.isNullOrEmpty() &&
                        uri.rawUserInfo == null &&
                        uri.port in -1..65535
                }
                .getOrDefault(false)
    }

    fun links(body: String): List<String> = links(parse(body))

    fun links(root: Node, includeExplicit: Boolean = true): List<String> {
        val result = linkedSetOf<String>()
        walk(root) { node, exiting ->
            if (!exiting && result.size < 10) {
                if (node is Link && includeExplicit) webUrl(node.destination)?.let(result::add)
                var parent = node.parent
                var insideLinkOrImage = false
                while (parent != null) {
                    if (parent is Link || parent is Image) insideLinkOrImage = true
                    parent = parent.parent
                }
                if (node is Text && !insideLinkOrImage)
                    webLinks.findAll(node.literal).forEach { match ->
                        if (result.size < 10)
                            webUrl(match.value.trimEnd('.', ',', '，', '。', '；', ';', '!', '！'))
                                ?.let(result::add)
                    }
            }
        }
        return result.toList()
    }
}
