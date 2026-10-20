package io.github.beacon.app.domain.policy

import org.commonmark.node.Node
import org.commonmark.node.OrderedList

/**
 * Context-free detail documents; rendered spans must keep the current screen's link resolver.
 *
 * Rendering mutates the tree: Markwon's list-item visitor advances every `OrderedList` start
 * number so consecutive items print 1, 2, 3. A cached document is rendered again on each visit,
 * so without a reset the numbers continue where the previous visit stopped
 * (1..15, then 16..30, ...). [Document.render] restores the parsed numbering first and holds a
 * lock, so two screens rendering the same document cannot interleave their counters.
 */
internal class MessageMarkdownDocuments(
    private val maxEntries: Int = 8,
    private val maxSourceChars: Int = 256 * 1024,
) {
    data class Document(
        val node: Node,
        val pictures: List<MessageMarkdown.Picture>,
        val links: List<String>,
        val bareLinks: List<String>,
    ) {
        private val lock = Any()
        private val orderedListStarts = ArrayList<Pair<OrderedList, Int>>().apply {
            val pending = java.util.ArrayDeque<Node>()
            pending.addLast(node)
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                if (current is OrderedList) add(current to current.startNumber)
                var child = current.firstChild
                while (child != null) {
                    pending.addLast(child)
                    child = child.next
                }
            }
        }

        /** Renders the tree with the numbering it was parsed with. */
        fun <T> render(block: (Node) -> T): T = synchronized(lock) {
            orderedListStarts.forEach { (list, startNumber) -> list.startNumber = startNumber }
            block(node)
        }
    }

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
