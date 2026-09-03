package io.toolbox.host.icons

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal object StaticSvgPolicy {
    private val elements = setOf(
        "svg", "g", "defs", "title", "desc", "path", "rect", "circle", "ellipse",
        "line", "polyline", "polygon", "text", "tspan", "linearGradient", "radialGradient", "stop",
    )
    private val localPaint = Regex("url\\(\\s*['\"]?#[A-Za-z_][A-Za-z0-9_.:-]*['\"]?\\s*\\)", RegexOption.IGNORE_CASE)

    fun validate(bytes: ByteArray): String {
        require(bytes.size <= InstalledToolIconReader.MAX_SVG_BYTES)
        val xml = bytes.decodeToString(throwOnInvalidSequence = true)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        var depth = 0
        var nodes = 0
        factory.newSAXParser().parse(InputSource(StringReader(xml)), object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                require(uri == "http://www.w3.org/2000/svg" && localName in elements)
                require(++depth <= 32 && ++nodes <= 2_048)
                if (nodes == 1) require(localName == "svg")
                for (index in 0 until attributes.length) {
                    val name = attributes.getLocalName(index).lowercase()
                    val value = attributes.getValue(index)
                    require(name != "href" && !name.startsWith("on"))
                    require(name !in setOf("filter", "mask", "clip-path"))
                    val withoutLocalPaint = localPaint.replace(value, "")
                    require(!withoutLocalPaint.contains("url", ignoreCase = true) && '@' !in value)
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) { depth -= 1 }
            override fun processingInstruction(target: String, data: String) { throw SAXException("No SVG instructions") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw SAXException("No SVG external entities")
        })
        require(nodes > 0 && depth == 0)
        return xml
    }
}
