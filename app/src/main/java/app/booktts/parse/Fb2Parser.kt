package app.booktts.parse

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * FictionBook 2 parser. Every `<section>` that has a `<title>` starts a new chapter.
 * The XmlPullParser is injected so the parser runs both on Android (Xml.newPullParser)
 * and in JVM unit tests (KXmlParser).
 */
class Fb2Parser(private val newParser: () -> XmlPullParser) {

    fun parse(bytes: ByteArray): ParsedBook {
        val xpp = newParser()
        xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // null encoding -> parser honours the XML declaration (e.g. windows-1251)
        xpp.setInput(ByteArrayInputStream(bytes), null)

        var bookTitle: String? = null
        var lang: String? = null
        var firstName = ""
        var lastName = ""
        var authorDone = false

        val chapters = ArrayList<ParsedChapter>()
        var chapterTitle: String? = null
        var paras = ArrayList<Para>()

        val stack = ArrayList<String>()
        val text = StringBuilder()
        val titleText = StringBuilder()
        var bodyIndex = -1
        var skipBody = false
        var noteLinkDepth = 0

        fun inTag(name: String) = stack.contains(name)
        fun flushChapter() {
            if (paras.isNotEmpty()) chapters += ParsedChapter(chapterTitle, paras)
            paras = ArrayList()
        }

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = xpp.name.substringAfter(':')
                    stack += name
                    when (name) {
                        "body" -> {
                            bodyIndex++
                            val bodyName = xpp.getAttributeValue(null, "name")
                            skipBody = bodyIndex > 0 && (bodyName == "notes" || bodyName == "comments" || bodyName == null)
                        }
                        "a" -> {
                            val type = xpp.getAttributeValue(null, "type")
                            if (type == "note") noteLinkDepth++
                        }
                        "p", "v", "subtitle", "text-author" -> text.clear()
                        "title" -> if (inTag("body")) titleText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (noteLinkDepth == 0) {
                        val t = xpp.text
                        when {
                            inTag("binary") -> {}
                            inTag("description") -> {
                                when (stack.lastOrNull()) {
                                    "book-title" -> if (inTag("title-info")) bookTitle = (bookTitle ?: "") + t
                                    "lang" -> if (inTag("title-info")) lang = t.trim()
                                    "first-name" -> if (inTag("title-info") && inTag("author") && !authorDone) firstName += t
                                    "last-name" -> if (inTag("title-info") && inTag("author") && !authorDone) lastName += t
                                }
                            }
                            inTag("body") && !skipBody -> text.append(t)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = stack.removeAt(stack.lastIndex)
                    if (inTag("body") || name == "body") {
                        if (!skipBody) when (name) {
                            "p", "v", "subtitle", "text-author" -> {
                                val s = text.toString().trim()
                                text.clear()
                                if (s.isNotEmpty()) {
                                    if (inTag("title")) {
                                        if (titleText.isNotEmpty()) titleText.append(". ")
                                        titleText.append(s)
                                    } else {
                                        paras += Para(s, heading = name == "subtitle")
                                    }
                                }
                            }
                            "title" -> {
                                val t = titleText.toString().trim()
                                titleText.clear()
                                if (t.isNotEmpty()) {
                                    // only a section's own title starts a chapter (not poem/epigraph titles)
                                    if (stack.lastOrNull() == "section") {
                                        flushChapter()
                                        chapterTitle = t
                                    }
                                    paras += Para(t, heading = true)
                                }
                            }
                        }
                    }
                    when (name) {
                        "a" -> if (noteLinkDepth > 0) noteLinkDepth--
                        "author" -> if (inTag("title-info")) authorDone = true
                        "body" -> skipBody = false
                    }
                }
            }
            event = xpp.next()
        }
        flushChapter()

        val author = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return ParsedBook(
            title = bookTitle?.trim()?.takeIf { it.isNotEmpty() },
            author = author.takeIf { it.isNotEmpty() },
            lang = lang?.lowercase()?.take(2)?.takeIf { it.isNotEmpty() },
            format = "fb2",
            chapters = chapters,
        )
    }
}
