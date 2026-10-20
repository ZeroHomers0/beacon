package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class MessageMarkdownTest {
    @Test fun summariesRemoveFormattingButPreserveCodeAndLinkLabels() {
        assertEquals("任务结果 成功 3 项 查看报告", MessageMarkdown.summary(
            "# 任务结果\n\n- **成功** 3 项\n- [查看报告](https://example.com/report)"
        ))
        assertEquals("a_b * x", MessageMarkdown.summary("```sh\na_b * x\n```"))
        assertEquals("a_b", MessageMarkdown.summary("a_b"))
        assertEquals("literal * star", MessageMarkdown.summary("literal \\* star"))
        assertEquals("预览", MessageMarkdown.summary("![预览](https://example.com/photo.png)"))
    }

    @Test fun previewsAreBoundedAndDoNotSplitEmoji() {
        assertEquals("😀😀…", MessageMarkdown.summary("😀😀😀", 2))
        assertEquals("正文", MessageMarkdown.summary("正文", 2))
        val longBody = "正文".repeat(10000)
        assertTrue(MessageMarkdown.summary(longBody).endsWith("…"))
        assertTrue(MessageMarkdown.summary(longBody).length <= 181)
        assertEquals("***", MessageMarkdown.summary("***"))
    }

    @Test fun linksResolveReferenceSyntaxAndRejectUnsafeDestinations() {
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), MessageMarkdown.links(
            "[报告](https://example.com/a) [日志][log]\n\n[log]: https://example.com/b"
        ))
        for (link in listOf("javascript:alert(1)", "file:///tmp/a", "https://user:pass@example.com", "https://example.com:99999/"))
            assertNull(MessageMarkdown.webUrl(link))
        assertTrue(MessageMarkdown.links("[执行](javascript:alert(1))").isEmpty())
    }

    @Test fun inlineImagesAreNeitherListedAsLinksNorStrippedFromTheBody() {
        // Pictures render where the author wrote them, so they are not a separate list any more, and
        // an image destination must not show up a second time in the link panel or in the copyable
        // body text.
        val body = "![描述](https://example.com/a.png) [报告](https://example.com/report)"
        val document = MessageMarkdownDocuments().get(body)
        assertEquals(listOf("https://example.com/report"), document.links)
        assertEquals("描述 报告", MessageMarkdown.plainText(document.node).trim())
        assertTrue(MessageMarkdown.links("![](https://example.com/a.png)").isEmpty())
        // A literal code example is text, not a picture.
        assertEquals("![a](https://example.com/a.png)",
            MessageMarkdown.plainText(MessageMarkdown.parse("`![a](https://example.com/a.png)`")).trim())
    }
}
