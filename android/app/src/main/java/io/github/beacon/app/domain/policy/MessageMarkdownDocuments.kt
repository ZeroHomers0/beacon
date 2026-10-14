package io.github.beacon.app.domain.policy

import org.commonmark.node.Node

/** Context-free detail documents; rendered spans must keep the current screen's link resolver. */
internal class MessageMarkdownDocuments(
    private val maxEntries: Int = 8,
    private val maxSourceChars: Int = 256 * 1024,
) {
    data class Document(
        val node: Node,
        val pictures: List<MessageMarkdown.Picture>,
        val links: List<String>,
        val bareLinks: List<String>,
    )

    private val documents = LinkedHashMap<String, Document>(maxEntries, 0.75f, true)
    private val parsing = Any()
    private var sourceChars = 0
    private var generation = 0L

    init {
        require(maxEntries > 0 && maxSourceChars > 0)
    }

    // Serialize cache misses off the UI thread, but keep parsing outside the cache lock so
    // the main thread can clear it immediately under memory pressure. Hits do not wait for
    // unrelated parsing. Recheck after acquiring the parse lock to share concurrent misses.
    fun get(body: String): Document {
        synchronized(this) { documents[body]?.let { return it } }
        return synchronized(parsing) {
            val expectedGeneration = synchronized(this) {
                documents[body]?.let { return it }
                generation
            }
            val node = MessageMarkdown.parse(body)
            val result = Document(
                node, MessageMarkdown.pictures(node), MessageMarkdown.links(node),
                MessageMarkdown.links(node, includeExplicit = false),
            )
            synchronized(this) {
                // A clear during parsing must not refill the cache with that old request.
                if (body.length <= maxSourceChars && generation == expectedGeneration) {
                    documents[body] = result
                    sourceChars += body.length
                    while (documents.size > maxEntries || sourceChars > maxSourceChars) {
                        val oldest = documents.entries.iterator()
                        sourceChars -= oldest.next().key.length
                        oldest.remove()
                    }
                }
            }
            result
        }
    }

    @Synchronized
    fun clear() {
        generation++
        documents.clear()
        sourceChars = 0
    }
}

internal val messageMarkdownDocuments = MessageMarkdownDocuments()
