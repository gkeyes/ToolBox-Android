package io.toolbox.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskExecutionMetadataTest {
    // Shapes written by older builds (org.json escaped '/' as "\/").
    private val legacyWithToken =
        """{"url":"https:\/\/example.com\/feed?a=1","title":"T","notificationId":"n-1","_toolboxExecutionToken":"tok-1"}"""
    private val legacyWithoutToken = """{"url":"https:\/\/example.com\/feed","allowRedirects":true}"""

    @Test
    fun readsTokenFromStoredRows() {
        assertEquals("tok-1", TaskExecutionMetadata.token(legacyWithToken))
        assertNull(TaskExecutionMetadata.token(legacyWithoutToken))
        assertNull(TaskExecutionMetadata.token("""{"_toolboxExecutionToken":""}"""))
        assertNull(TaskExecutionMetadata.token("{}"))
    }

    @Test
    fun stripsTokenAndKeepsOtherFieldsInOrder() {
        assertEquals(
            """{"url":"https://example.com/feed?a=1","title":"T","notificationId":"n-1"}""",
            TaskExecutionMetadata.spec(legacyWithToken),
        )
        assertEquals("""{"url":"https://example.com/feed","allowRedirects":true}""", TaskExecutionMetadata.spec(legacyWithoutToken))
    }

    @Test
    fun replacesTokenAtTheEnd() {
        val updated = TaskExecutionMetadata.spec(legacyWithToken, "tok-2")
        assertEquals(
            """{"url":"https://example.com/feed?a=1","title":"T","notificationId":"n-1","_toolboxExecutionToken":"tok-2"}""",
            updated,
        )
        assertEquals("tok-2", TaskExecutionMetadata.token(updated))
        assertEquals(TaskExecutionMetadata.spec(legacyWithToken), TaskExecutionMetadata.spec(updated))
    }

    @Test
    fun preservesUnicodeAndNumbers() {
        val spec = """{"title":"你好 \"q\"","n":9007199254740993,"x":1.5,"nested":{"a":[1,null,false]}}"""
        val stripped = TaskExecutionMetadata.spec(spec)
        assertEquals("""{"title":"你好 \"q\"","n":9007199254740993,"x":1.5,"nested":{"a":[1,null,false]}}""", stripped)
    }
}
