package com.elicode.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight regex-based syntax highlighter for the built-in editor.
 * Produces an [AnnotatedString] of identical length (safe for cursor math).
 */
object Syntax {

    enum class Lang { KOTLIN, JAVA, XML, JSON, JS, HTML, CSS, MARKDOWN, PYTHON, C, PLAIN }

    fun forExtension(ext: String): Lang = when (ext.lowercase()) {
        "kt", "kts" -> Lang.KOTLIN
        "java" -> Lang.JAVA
        "xml", "gradle" -> Lang.XML
        "json" -> Lang.JSON
        "js", "mjs", "cjs" -> Lang.JS
        "ts", "tsx", "jsx" -> Lang.JS
        "html", "htm" -> Lang.HTML
        "css" -> Lang.CSS
        "md", "markdown" -> Lang.MARKDOWN
        "py" -> Lang.PYTHON
        "c", "h", "cpp", "hpp", "cc" -> Lang.C
        else -> Lang.PLAIN
    }

    // Theme-agnostic palette that reads well on dark and light surfaces.
    private val Keyword = Color(0xFF7DD3FC)
    private val StringC = Color(0xFF86EFAC)
    private val Comment = Color(0xFF64748B)
    private val Number = Color(0xFFFBBF24)
    private val Annotation = Color(0xFFF0ABFC)
    private val Tag = Color(0xFFFCA5A5)
    private val Attr = Color(0xFFC4B5FD)
    private val Func = Color(0xFF93C5FD)

    private data class Rule(val regex: Regex, val style: SpanStyle)

    private val comments = { line: String, block: String ->
        listOf(
            Rule(Regex(block), SpanStyle(color = Comment, fontStyle = FontStyle.Italic)),
            Rule(Regex(line), SpanStyle(color = Comment, fontStyle = FontStyle.Italic))
        )
    }

    private val strings = Rule(
        Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`"),
        SpanStyle(color = StringC)
    )
    private val numbers = Rule(Regex("\\b\\d[\\d_]*(?:\\.\\d+)?\\b"), SpanStyle(color = Number))
    private val annotations = Rule(Regex("@[A-Za-z_][\\w]*"), SpanStyle(color = Annotation))
    private val functions = Rule(Regex("\\b[A-Za-z_][\\w]*(?=\\s*\\()"), SpanStyle(color = Func))

    private fun keywords(vararg words: String) = Rule(
        Regex("\\b(?:${words.joinToString("|")})\\b"),
        SpanStyle(color = Keyword, fontWeight = FontWeight.Bold)
    )

    private val cLikeKw = keywords(
        "package", "import", "class", "interface", "object", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "new", "this", "super", "null", "true",
        "false", "public", "private", "protected", "static", "final", "abstract",
        "extends", "implements", "void", "int", "long", "double", "float", "boolean",
        "data", "sealed", "override", "open", "internal", "companion", "const",
        "suspend", "operator", "out", "in", "is", "as", "typeof", "sizeof", "struct",
        "enum", "typedef", "namespace", "using", "virtual", "typename", "template",
        "def", "lambda", "with", "from", "pass", "raise", "yield", "global",
        "function", "let", "await", "async", "export", "default", "switch", "case"
    )

    private fun rulesFor(lang: Lang): List<Rule> = when (lang) {
        Lang.PLAIN -> emptyList()
        Lang.JSON -> listOf(strings, numbers) + comments("//.*", "/\\*[\\s\\S]*?\\*/")
        Lang.MARKDOWN -> listOf(
            Rule(Regex("^#{1,6}\\s.*", RegexOption.MULTILINE), SpanStyle(color = Keyword, fontWeight = FontWeight.Bold)),
            Rule(Regex("`[^`]*`"), SpanStyle(color = StringC)),
            Rule(Regex("\\*\\*[^*]+\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold)),
            Rule(Regex("^\\s*[-*]\\s.*", RegexOption.MULTILINE), SpanStyle(color = Func))
        )
        Lang.XML, Lang.HTML -> listOf(
            Rule(Regex("<!--[\\s\\S]*?-->"), SpanStyle(color = Comment, fontStyle = FontStyle.Italic)),
            Rule(Regex("</?[A-Za-z][^\\s/>]*|/?>"), SpanStyle(color = Tag, fontWeight = FontWeight.Bold)),
            Rule(Regex("[A-Za-z-]+(?=\\s*=)"), SpanStyle(color = Attr)),
            strings, numbers
        )
        Lang.CSS -> listOf(
            Rule(Regex("/\\*[\\s\\S]*?\\*/"), SpanStyle(color = Comment, fontStyle = FontStyle.Italic)),
            Rule(Regex("[.#]?[A-Za-z-]+(?=\\s*[,{])"), SpanStyle(color = Tag, fontWeight = FontWeight.Bold)),
            Rule(Regex("[a-z-]+(?=\\s*:)"), SpanStyle(color = Attr)),
            strings, numbers
        )
        Lang.PYTHON -> comments("#.*", "\"\"\"[\\s\\S]*?\"\"\"") + listOf(
            strings, numbers, annotations, functions, cLikeKw
        )
        else -> comments("//.*", "/\\*[\\s\\S]*?\\*/") + listOf(
            strings, numbers, annotations, functions, cLikeKw
        )
    }

    /** Highlight [code] for [lang]. Later rules don't override earlier spans. */
    fun highlight(code: String, lang: Lang): AnnotatedString {
        if (code.isEmpty() || lang == Lang.PLAIN) return AnnotatedString(code)
        val painted = BooleanArray(code.length)
        return buildAnnotatedString {
            append(code)
            for (rule in rulesFor(lang)) {
                for (m in rule.regex.findAll(code)) {
                    val s = m.range.first.coerceIn(0, code.length)
                    val e = (m.range.last + 1).coerceIn(0, code.length)
                    if (s >= e) continue
                    var clash = false
                    for (i in s until e) if (painted[i]) { clash = true; break }
                    if (clash) continue
                    addStyle(rule.style, s, e)
                    for (i in s until e) painted[i] = true
                }
            }
        }
    }
}
