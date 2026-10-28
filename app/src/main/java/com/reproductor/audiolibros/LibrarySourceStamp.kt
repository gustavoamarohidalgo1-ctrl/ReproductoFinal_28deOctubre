package com.reproductor.audiolibros

/** Provider identity and generation for every currently mounted external volume. */
internal data class LibrarySourceStamp(val volumes: Map<String, Volume>) {
    data class Volume(val version: String, val generation: Long)
}
