package com.robin.claudeusage.data

enum class Profile(val key: String, val label: String) {
    PERSONAL("personal", "Personal"),
    WORK("work", "Work");

    companion object {
        fun fromKey(key: String?): Profile =
            entries.firstOrNull { it.key == key } ?: PERSONAL
    }
}
