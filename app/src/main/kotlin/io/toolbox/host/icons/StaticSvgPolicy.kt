package io.toolbox.host.icons

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Validate resource access before giving the document to the pinned static renderer. */
internal object StaticSvgPolicy {
    private val localReference = Regex("""#[^\s"'()<>]+""")
    private val embeddedRaster = Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+""")
    private val cssComments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val cssEscape = Regex("\\\\([0-9a-fA-F]{1,6}[ \\t\\r\\n\\u000C]?|[^\\r\\n\\u000C])")
    private val cssUrl = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

    fun validate(bytes: ByteArray): String {
        val xml = bytes.decodeToString(throwOnInvalidSequence = true)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        var nodes = 0
        var style: StringBuilder? = null
        factory.newSAXParser().parse(InputSource(StringReader(xml)), object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                require(uri.isEmpty() || uri == "http://www.w3.org/2000/svg")
                require(localName.lowercase() !in setOf("script", "foreignobject"))
                nodes += 1
                if (nodes == 1) require(localName == "svg")
                if (localName == "style") style = StringBuilder()
                for (index in 0 until attributes.length) {
                    val name = attributes.getLocalName(index).lowercase()
                    val value = attributes.getValue(index)
                    require(!name.startsWith("on") && name != "base")
                    if (name == "href" || name == "src") {
                        val reference = value.trim()
                        // AndroidSVG decodes image data URLs as raster bytes without opening a URI.
                        // Its external resolver stays disabled; fragment refs never load another file.
                        require(localReference.matches(reference) || (localName == "image" && embeddedRaster.matches(reference)))
                    }
                    validateCss(value)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                style?.append(ch, start, length)
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                if (localName == "style") {
                    validateCss(requireNotNull(style).toString())
                    style = null
                }
            }

            override fun processingInstruction(target: String, data: String) { throw SAXException("No SVG instructions") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw SAXException("No SVG external entities")
        })
        require(nodes > 0)
        return xml
    }

    private fun validateCss(value: String) {
        val normalized = cssEscape.replace(cssComments.replace(value, "")) { match ->
            val escaped = match.groupValues[1]
            if (escaped[0].digitToIntOrNull(16) != null) {
                val code = escaped.trim().toInt(16)
                if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else "\uFFFD"
            } else escaped
        }
        require(!Regex("@\\s*import\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized))
        val withoutUrls = cssUrl.replace(normalized) { match ->
            val target = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            require(localReference.matches(target))
            ""
        }
        require(!Regex("url\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(withoutUrls))
    }
}
