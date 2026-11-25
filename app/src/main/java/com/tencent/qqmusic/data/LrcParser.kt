package com.tencent.qqmusic.data

import java.io.File

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

object LrcParser {

    private val timeRegex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun loadLyricsForAudio(audioPath: String): List<LyricLine> {
        if (audioPath.isBlank()) return emptyList()
        val audioFile = File(audioPath)
        val candidates = listOfNotNull(
            audioFile.parent?.let { parent ->
                val baseName = audioFile.nameWithoutExtension
                File(parent, "$baseName.lrc")
            },
            File(audioPath.substringBeforeLast('.', audioPath) + ".lrc")
        )
        val lrcFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return emptyList()
        return parseLrcContent(lrcFile.readText())
    }

    fun parseLrcContent(content: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val matches = timeRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val lyricText = line.replace(timeRegex, "").trim()
            if (lyricText.isEmpty()) return@forEach

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fractionRaw.length) {
                    1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10L
                    3 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (minutes * 60_000L) + (seconds * 1_000L) + millis
                result.add(LyricLine(timestampMs = totalMs, text = lyricText))
            }
        }
        return result.sortedBy { it.timestampMs }
    }

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var activeIndex = 0
        for (i in lines.indices) {
            if (lines[i].timestampMs <= positionMs) {
                activeIndex = i
            } else {
                break
            }
        }
        return activeIndex
    }
}
