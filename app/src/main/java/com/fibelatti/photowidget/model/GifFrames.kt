package com.fibelatti.photowidget.model

data class GifFrames(
    val frames: List<LocalPhoto>,
    val interval: Int,
) {

    companion object {

        val EMPTY = GifFrames(frames = emptyList(), interval = 0)
    }
}
