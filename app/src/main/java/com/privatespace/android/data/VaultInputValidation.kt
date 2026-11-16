package com.privatespace.android.data

import java.util.Locale

object VaultInputValidation {
    fun topicName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "Enter a topic name." }
        require(name.length <= 64) { "Topic names must be 64 characters or fewer." }
        require(name.none { it.isISOControl() || it == '/' || it == '\\' }) {
            "Topic names cannot contain slashes or control characters."
        }
        return name
    }

    fun displayName(value: String): String {
        val cleaned = value
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim()
            .trim('.')
            .take(160)
        return cleaned.ifBlank { "document" }
    }

    fun mimeType(value: String?, fallback: String = "application/octet-stream"): String {
        val candidate = value?.trim()?.lowercase(Locale.ROOT) ?: return fallback
        return if (candidate.matches(Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"""))) {
            candidate
        } else {
            fallback
        }
    }
}
