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

    @Test fun picturesRequireHttpsAndAreDeduplicatedAndBounded() {
        val body = "![描述](https://example.com/a.png) ![重复](https://example.com/a.png) " +
            "![无效](http://example.com/b.png) " +
            (1..10).joinToString(" ") { "![](https://example.com/$it.png)" }
        val pictures = MessageMarkdown.pictures(MessageMarkdown.parse(body))
        assertEquals(5, pictures.size)
        assertEquals(MessageMarkdown.Picture("https://example.com/a.png", "描述"), pictures.first())
        assertEquals(5, pictures.map { it.url }.distinct().size)
        // A literal code example must not trigger an image download control.
        assertTrue(MessageMarkdown.pictures(MessageMarkdown.parse("`![a](https://example.com/a.png)`")).isEmpty())
    }
}
