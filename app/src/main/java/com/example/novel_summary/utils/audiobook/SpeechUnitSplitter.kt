package com.example.novel_summary.utils.audiobook

object SpeechUnitSplitter {

    private const val MAX_UNIT_LENGTH = 800
    private const val TARGET_UNIT_LENGTH = 550
    private const val MIN_UNIT_LENGTH = 300

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val chunks = mutableListOf<String>()
        normalized.split('\n').forEach { paragraph ->
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.length <= MAX_UNIT_LENGTH) {
                chunks.addAll(splitLongText(trimmed))
            } else {
                chunks.addAll(splitLongText(trimmed))
            }
        }

        return rebalanceChunks(chunks.filter { it.isNotBlank() })
    }

    private fun splitLongText(input: String): List<String> {
        val tokens = input.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var start = 0

        while (start < tokens.size) {
            var end = start
            var currentLength = 0

            while (end < tokens.size) {
                val tokenLength = tokens[end].length
                val candidateLength = if (currentLength == 0) {
                    tokenLength
                } else {
                    currentLength + 1 + tokenLength
                }

                if (candidateLength > MAX_UNIT_LENGTH) break

                if (currentLength > 0 && currentLength >= MIN_UNIT_LENGTH && candidateLength >= TARGET_UNIT_LENGTH) {
                    break
                }

                currentLength = candidateLength
                end++
            }

            if (end == start) {
                end = (start + 1).coerceAtMost(tokens.size)
            }

            val chunk = tokens.subList(start, end).joinToString(" ")
            result.add(chunk)
            start = end
        }

        return result
    }

    private fun rebalanceChunks(chunks: List<String>): List<String> {
        if (chunks.isEmpty()) return emptyList()

        val result = chunks.toMutableList()
        var index = 0

        while (index < result.size) {
            val chunk = result[index]

            if (chunk.length > MAX_UNIT_LENGTH) {
                val split = splitLongText(chunk)
                if (split.size > 1) {
                    result.subList(index, index + 1).clear()
                    result.addAll(index, split)
                    continue
                }
            }

            if (chunk.length < MIN_UNIT_LENGTH && index > 0) {
                val merged = (result[index - 1].trim() + " " + chunk.trim()).trim()
                if (merged.length <= MAX_UNIT_LENGTH) {
                    result[index - 1] = merged
                    result.removeAt(index)
                    continue
                }
            }

            if (chunk.length < MIN_UNIT_LENGTH && index + 1 < result.size) {
                val merged = (chunk.trim() + " " + result[index + 1].trim()).trim()
                if (merged.length <= MAX_UNIT_LENGTH) {
                    result[index] = merged
                    result.removeAt(index + 1)
                    continue
                }
            }

            index++
        }

        return result.filter { it.isNotBlank() }
    }
}