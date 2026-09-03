package io.toolbox.host.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StaticSvgPolicyTest {
    @Test
    fun permitsStaticShapesTextAndLocalGradients() {
        val xml = svg("""
            <defs><linearGradient id="g"><stop offset="0" stop-color="blue"/></linearGradient></defs>
            <rect width="64" height="64" fill="url(#g)"/>
            <text x="10" y="30">工具</text><path d="M0 0L20 20"/>
        """)
        assertEquals(xml, StaticSvgPolicy.validate(xml.toByteArray()))
    }

    @Test
    fun rejectsExternalResourcesEntitiesExecutableContentAndRecursiveReferences() {
        val invalid = listOf(
            svg("<script>alert(1)</script>"),
            svg("<image href=\"https://example.com/a.png\"/>"),
            svg("<use href=\"#cycle\" id=\"cycle\"/>"),
            svg("<rect onload=\"alert(1)\"/>"),
            svg("<rect fill=\"url(https://example.com/a)\"/>"),
            svg("<style>@import 'https://example.com/a';</style>"),
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private/secret'>]>" + svg("<text>&x;</text>"),
            "<?xml-stylesheet href='https://example.com/a'?>" + svg("<rect/>"),
            svg("<foreignObject><html/></foreignObject>"),
        )
        invalid.forEach { xml -> assertThrows(Exception::class.java) { StaticSvgPolicy.validate(xml.toByteArray()) } }
    }

    @Test
    fun boundsParsingWorkBeforeRendering() {
        listOf(svg("<g>".repeat(33) + "</g>".repeat(33)), svg("<rect/>".repeat(2_049))).forEach { xml ->
            assertThrows(Exception::class.java) { StaticSvgPolicy.validate(xml.toByteArray()) }
        }
    }

    private fun svg(content: String) = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">$content</svg>"
}
