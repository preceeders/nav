package com.hu.nav.domain.tts

interface TtsSpeaker {
    var isMuted: Boolean
    var speechRate: Float
    fun speak(text: String, flush: Boolean = false)
    fun stop()
    fun shutdown()
    fun warmUp() {}
}
