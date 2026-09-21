package com.hu.nav.data.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hu.nav.domain.tts.TtsSpeaker
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AndroidTtsSpeaker(context: Context) : TtsSpeaker {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val ready = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private val lock = Any()
    private var engine: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var remainingEngines = ArrayDeque<String?>()
    private var attempt = 0

    override var isMuted: Boolean = false
    override var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            engine?.setSpeechRate(field)
        }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
        .build()

    override fun warmUp() {
        main.post { startNextEngine(reset = true) }
    }

    override fun speak(text: String, flush: Boolean) {
        val spoken = text.trim()
        Log.i(
            TAG,
            "speak() muted=$isMuted ready=${ready.get()} blank=${spoken.isBlank()} " +
                "musicVol=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                "${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "text=$spoken",
        )
        if (isMuted || spoken.isBlank()) return
        if (!ready.get()) {
            synchronized(lock) {
                if (flush) pending.clear()
                pending.addLast(spoken to flush)
            }
            Log.w(TAG, "TTS not ready, queued: $spoken")
            warmUp()
            return
        }
        speakNow(spoken, flush)
    }

    override fun stop() {
        engine?.stop()
        synchronized(lock) { pending.clear() }
        abandonFocus()
    }

    override fun shutdown() {
        stop()
        ready.set(false)
        starting.set(false)
        main.removeCallbacksAndMessages(null)
        engine?.shutdown()
        engine = null
    }

    private fun startNextEngine(reset: Boolean) {
        if (ready.get() || starting.getAndSet(true)) return
        if (reset) {
            remainingEngines = ArrayDeque(discoverEngines())
        }
        if (remainingEngines.isEmpty()) {
            starting.set(false)
            if (attempt >= MAX_ATTEMPTS) {
                Log.e(TAG, "TTS give up after $attempt attempts")
                return
            }
            attempt++
            val delayMs = 1200L
            Log.w(TAG, "TTS engines exhausted, retry in ${delayMs}ms attempt=$attempt")
            main.postDelayed({ startNextEngine(reset = true) }, delayMs)
            return
        }
        val packageName = remainingEngines.removeFirst()
        engine?.shutdown()
        engine = null
        Log.i(TAG, "TTS binding engine=${packageName ?: "default"}")
        engine = if (packageName.isNullOrBlank()) {
            TextToSpeech(app) { status -> onInit(status, packageName) }
        } else {
            TextToSpeech(app, { status -> onInit(status, packageName) }, packageName)
        }
    }

    private fun onInit(status: Int, packageName: String?) {
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.e(TAG, "TTS init failed status=$status engine=$packageName")
            starting.set(false)
            main.post { startNextEngine(reset = false) }
            return
        }
        tts.setAudioAttributes(audioAttributes)
        chooseLanguage(tts)
        tts.setSpeechRate(speechRate)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "TTS onStart id=$utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "TTS onDone id=$utteranceId")
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS utterance error id=$utteranceId")
            }
        })
        ready.set(true)
        starting.set(false)
        attempt = 0
        Log.i(TAG, "TTS ready requested=$packageName engine=${tts.defaultEngine} voice=${tts.defaultVoice?.name}")
        flushPending()
    }

    private fun discoverEngines(): List<String?> {
        val installed = app.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL,
        ).mapNotNull { it.serviceInfo?.packageName }.distinct()
        val preferred = Settings.Secure.getString(app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        val ordered = buildList {
            add(null)
            if (!preferred.isNullOrBlank()) add(preferred)
            addAll(installed)
        }.distinct()
        Log.i(TAG, "TTS preferred=$preferred installed=$installed order=$ordered")
        return ordered
    }

    private fun speakNow(text: String, flush: Boolean) {
        val tts = engine ?: return
        requestFocus()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
        }
        val queue = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(text, queue, params, "nav-${System.currentTimeMillis()}")
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed result=$result text=$text")
        } else {
            Log.i(TAG, "TTS speak ok: $text")
        }
    }

    private fun flushPending() {
        val items = synchronized(lock) {
            pending.toList().also { pending.clear() }
        }
        items.forEach { (text, flush) -> speakNow(text, flush) }
    }

    private fun chooseLanguage(tts: TextToSpeech) {
        val locales = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale.CHINESE,
            Locale("zh", "CN"),
            Locale.getDefault(),
        )
        for (locale in locales) {
            val available = tts.isLanguageAvailable(locale)
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                val result = tts.setLanguage(locale)
                Log.i(TAG, "TTS language=$locale available=$available set=$result")
                if (result >= TextToSpeech.LANG_AVAILABLE) return
            }
        }
        Log.w(TAG, "TTS using engine default language")
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .build()
        focusRequest = request
        val focus = audioManager.requestAudioFocus(request)
        Log.i(TAG, "audioFocus=$focus")
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "NavTts"
        const val MAX_ATTEMPTS = 6
    }
}
