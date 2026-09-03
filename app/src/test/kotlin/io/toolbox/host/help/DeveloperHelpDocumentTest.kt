package io.toolbox.host.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperHelpDocumentTest {
    @Test
    fun shippedManualParsesWithReachableChaptersAndCopyableSdk() {
        val source = requireNotNull(javaClass.getResourceAsStream("/manual.md"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val document = parseHelpDocument(source)
        assertEquals(source, document.source)
        assertEquals(7, document.chapters.size)
        assertEquals(28, document.chapters.sumOf { it.articles.size })
        assertTrue(document.chapters.all { it.summary.isNotBlank() })
        assertTrue(document.search("background.setTimer").isNotEmpty())
        assertTrue(document.search("manifest 图标").flatMap { it.articles }.any {
            it.title == "同一图标用于列表、通知和超级岛"
        })
        val sdk = document.chapters.flatMap { it.articles }.flatMap { it.blocks }
            .single { it.label == "ts sdk/toolbox-api.d.ts" }
        assertTrue(sdk.code)
        assertTrue(sdk.text.contains("interface ToolBoxApi"))
    }

    @Test
    fun parsesHierarchyAndPreservesCopyableCodeWhileSearchingAcrossIt() {
        val source = """
            # 开发帮助
            离线手册。

            ## 后台与通知
            会话与计时器。

            ### 定时更新
            说明。

            ```js app.js
            const value = "## not a heading";
            await ToolBox.background.setTimer("clock", 10000);
            ```

            ### 停止
            停止会话。

            ## 文件
            系统交互。

            ### 读取
            ToolBox.files.read 返回字节。
        """.trimIndent()
        val document = parseHelpDocument(source)
        assertEquals(2, document.chapters.size)
        assertEquals(2, document.chapters.first().articles.size)
        val code = document.chapters.first().articles.first().blocks.last()
        assertTrue(code.code)
        assertEquals("js app.js", code.label)
        assertTrue(code.text.contains("\"## not a heading\""))
        assertEquals(source, document.source)
        assertEquals("定时更新", document.search("后台 SETTIMER").single().articles.single().title)
        assertTrue(document.search("does-not-exist").isEmpty())
        assertEquals(document.chapters, document.search("  "))
        assertFalse(document.chapters.first().articles.last().blocks.first().code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnclosedCodeInsteadOfDisplayingAnIncompleteCopySample() {
        parseHelpDocument("# 手册\n## 基础\n### 示例\n```js\nconst x = 1;")
    }
}
