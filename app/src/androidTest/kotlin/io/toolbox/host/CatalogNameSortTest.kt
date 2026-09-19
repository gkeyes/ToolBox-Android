package io.toolbox.host

import io.toolbox.core.data.CatalogSort
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.catalogNameSortKey
import io.toolbox.host.catalog.catalogSorted
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogNameSortTest {
    @Test fun androidIcuOrdersChineseByPinyinAlongsideCaseInsensitiveEnglishAndDigits() {
        val names = listOf("中国", "Beta", "阿里", "apple", "北京", "10", "Apple", "aardvark")
        val tools = names.mapIndexed { i, name -> CatalogTool("id.$i", name, 1, "1.0.0", 1, null,
            nameSortKey = catalogNameSortKey(name)) }
        assertEquals(listOf("10", "aardvark", "阿里", "Apple", "apple", "北京", "Beta", "中国"),
            tools.catalogSorted(CatalogSort.NAME).map { it.name })
    }
}
