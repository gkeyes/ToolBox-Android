package io.toolbox.host.help

internal data class HelpDocument(
    val title: String,
    val introduction: String,
    val chapters: List<HelpChapter>,
    val source: String,
) {
    fun search(query: String): List<HelpChapter> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return chapters
        return chapters.mapNotNull { chapter ->
            val articles = chapter.articles.filter { article ->
                words.all { word -> word in article.searchText }
            }
            chapter.copy(articles = articles).takeIf { articles.isNotEmpty() }
        }
    }
}

internal data class HelpChapter(
    val id: String,
    val title: String,
    val summary: String,
    val articles: List<HelpArticle>,
)

internal data class HelpArticle(
    val id: String,
    val title: String,
    val blocks: List<HelpBlock>,
    val searchText: String,
)

internal data class HelpBlock(
    val text: String,
    val code: Boolean = false,
    val label: String = "",
)

internal fun parseHelpDocument(source: String): HelpDocument {
    var title = "开发帮助"
    val introduction = mutableListOf<String>()
    val chapters = mutableListOf<HelpChapter>()
    var chapterTitle: String? = null
    val chapterSummary = mutableListOf<String>()
    val articles = mutableListOf<HelpArticle>()
    var articleTitle: String? = null
    val blocks = mutableListOf<HelpBlock>()
    val paragraph = mutableListOf<String>()
    var codeLabel: String? = null
    val codeLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = paragraph.joinToString("\n").trim()
        when {
            articleTitle != null -> blocks += HelpBlock(text)
            chapterTitle != null -> chapterSummary += text
            else -> introduction += text
        }
        paragraph.clear()
    }

    fun flushArticle() {
        flushParagraph()
        val currentTitle = articleTitle ?: return
        val currentChapter = requireNotNull(chapterTitle)
        require(blocks.isNotEmpty()) { "Empty help article: $currentTitle" }
        articles += HelpArticle(
            id = "$currentChapter/$currentTitle",
            title = currentTitle,
            blocks = blocks.toList(),
            searchText = buildString {
                appendLine(currentChapter)
                appendLine(chapterSummary.joinToString(" "))
                appendLine(currentTitle)
                blocks.forEach { appendLine(it.text) }
            }.lowercase(),
        )
        blocks.clear()
        articleTitle = null
    }

    fun flushChapter() {
        flushArticle()
        val currentTitle = chapterTitle ?: return
        require(articles.isNotEmpty()) { "Empty help chapter: $currentTitle" }
        chapters += HelpChapter(currentTitle, currentTitle, chapterSummary.joinToString("\n"), articles.toList())
        articles.clear()
        chapterSummary.clear()
    }

    source.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        when {
            codeLabel != null && line == "```" -> {
                blocks += HelpBlock(codeLines.joinToString("\n"), code = true, label = codeLabel.orEmpty())
                codeLines.clear()
                codeLabel = null
            }
            codeLabel != null -> codeLines += rawLine
            line.startsWith("```") -> {
                require(articleTitle != null) { "Code must belong to a help article" }
                flushParagraph()
                codeLabel = line.removePrefix("```").trim()
            }
            line.startsWith("### ") -> {
                require(chapterTitle != null) { "Help article has no chapter" }
                flushArticle()
                articleTitle = line.removePrefix("### ").trim()
            }
            line.startsWith("## ") -> {
                flushChapter()
                chapterTitle = line.removePrefix("## ").trim()
            }
            line.startsWith("# ") -> {
                require(chapterTitle == null) { "Manual has more than one title" }
                title = line.removePrefix("# ").trim()
            }
            line.isBlank() -> flushParagraph()
            else -> paragraph += line
        }
    }
    require(codeLabel == null) { "Unclosed help code fence" }
    flushChapter()
    require(chapters.isNotEmpty()) { "Help manual is empty" }
    require(chapters.map(HelpChapter::id).distinct().size == chapters.size)
    val articleIds = chapters.flatMap { it.articles }.map(HelpArticle::id)
    require(articleIds.distinct().size == articleIds.size) { "Duplicate help article" }
    return HelpDocument(title, introduction.joinToString("\n\n"), chapters, source)
}
