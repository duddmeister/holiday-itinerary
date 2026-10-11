package io.github.duddmeister.lumen.ime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import io.github.duddmeister.lumen.R

/**
 * Voice typing through the phone's speech recognition service (usually Google's):
 * each start listens for one stretch of speech, reporting the words heard so far,
 * then hands over what was said. Lumen itself keeps no audio.
 */
class VoiceTyping(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onVoiceState(state: State)
        /** The words said, once the speech service has settled on them. */
        fun onVoiceResult(text: String)
    }

    sealed class State {
        data object Idle : State()
        /** Waiting for the speech service. */
        data object Starting : State()
        /** Listening: [level] is how loud, 0 to 1; [heard] the words so far. */
        data class Listening(val level: Float, val heard: String) : State()
        /** Done listening; the words are on their way. */
        data class Working(val heard: String) : State()
        class Failed(val message: String) : State()
    }

    var state: State = State.Idle
        private set(value) {
            field = value
            listener.onVoiceState(value)
        }

    private var recognizer: SpeechRecognizer? = null
    private var heard = ""

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val listening: Boolean get() = state is State.Starting || state is State.Listening

    /** Starts listening for speech in [language] (a BCP 47 tag such as "en-GB"). */
    fun start(language: String) {
        release()
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            null
        }
        if (r == null) {
            state = State.Failed(context.getString(R.string.voice_unavailable))
            return
        }
        recognizer = r
        heard = ""
        r.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Punctuation and capitals from the speech service, where it offers them.
        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        state = State.Starting
        r.startListening(intent)
    }

    /** Stops listening; what was said so far still comes through. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Stops and forgets whatever was being heard. */
    fun cancel() {
        recognizer?.cancel()
        release()
        if (state !is State.Idle) state = State.Idle
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = State.Listening(0f, heard)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val s = state as? State.Listening ?: return
            // Speech services report roughly -2 to 10 dB.
            state = s.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            state = State.Working(heard)
        }

        override fun onError(error: Int) {
            release()
            state = State.Failed(context.getString(message(error)))
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            release()
            if (text.isEmpty()) {
                state = State.Failed(context.getString(R.string.voice_no_match))
            } else {
                state = State.Idle
                listener.onVoiceResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (text.isBlank()) return
            heard = text
            state = when (val s = state) {
                is State.Listening -> s.copy(heard = text)
                is State.Working -> State.Working(text)
                else -> State.Listening(0f, text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun message(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> R.string.voice_no_match
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_timeout
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.voice_network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
        else -> if (Build.VERSION.SDK_INT >= 31 && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            R.string.voice_language
        } else {
            R.string.voice_failed
        }
    }

    companion object {
        /** When the microphone was allowed from the keyboard, so voice typing can pick up from there. */
        @Volatile internal var grantedAt = 0L

        private const val PICK_UP_MS = 30_000L

        /** True once, shortly after the microphone was allowed for voice typing. */
        fun justGranted(): Boolean {
            val at = grantedAt
            grantedAt = 0L
            return at > 0L && SystemClock.elapsedRealtime() - at < PICK_UP_MS
        }
    }
}

/**
 * Asks for the microphone on the keyboard's behalf (a keyboard has no screen of its
 * own to ask from), then gets out of the way. If Android won't ask any more, it
 * opens Lumen's app settings instead.
 */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoiceTyping.grantedAt = SystemClock.elapsedRealtime()
            finish()
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            VoiceTyping.grantedAt = SystemClock.elapsedRealtime()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android no longer asks: the switch is in Lumen's app settings.
            Toast.makeText(this, R.string.voice_permission_settings, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: android.content.ActivityNotFoundException) {
                // Nowhere to send them; the toast says what to do.
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1

        fun start(context: Context) {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
