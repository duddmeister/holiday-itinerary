package io.github.duddmeister.lumen.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.duddmeister.lumen.keyboard.KeyCodes

/**
 * Key haptics and sounds. Haptics use the crispest effect each Android version
 * offers (composition primitives, then predefined clicks, then a short pulse).
 */
class Feedback(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    var hapticsEnabled = true
    var soundEnabled = false
    var volume = 0.4f
    private var keyEffect: VibrationEffect? = null
    private var tickEffect: VibrationEffect? = null

    @Suppress("DEPRECATION")
    private val legacyAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** [strength]: 1 light, 2 medium, 3 firm. */
    fun configure(haptics: Boolean, strength: Int, sound: Boolean, soundVolume: Int) {
        hapticsEnabled = haptics && vibrator?.hasVibrator() == true
        soundEnabled = sound
        volume = soundVolume / 100f
        keyEffect = if (hapticsEnabled) effect(strength) else null
        tickEffect = if (hapticsEnabled) effect(1) else null
    }

    private fun effect(strength: Int): VibrationEffect {
        val v = vibrator
        if (Build.VERSION.SDK_INT >= 31 && v != null) {
            val primitive = if (strength == 1) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            if (v.areAllPrimitivesSupported(primitive)) {
                val scale = when (strength) { 1 -> 0.9f; 2 -> 0.55f; else -> 1f }
                return VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return VibrationEffect.createPredefined(
                when (strength) {
                    1 -> VibrationEffect.EFFECT_TICK
                    2 -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                },
            )
        }
        val amplitude = if (v?.hasAmplitudeControl() == true) intArrayOf(70, 140, 230)[strength - 1] else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(longArrayOf(6, 12, 20)[strength - 1], amplitude)
    }

    private fun vibrate(effect: VibrationEffect?) {
        val v = vibrator ?: return
        effect ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, legacyAttributes)
        }
    }

    /** A key was pressed. */
    fun key(code: Int) {
        if (hapticsEnabled) vibrate(keyEffect)
        if (soundEnabled && audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val fx = when (code) {
                KeyCodes.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyCodes.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyCodes.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, volume)
        }
    }

    /** A subtle tick for cursor steps and panel taps. */
    fun tick() {
        if (hapticsEnabled) vibrate(tickEffect)
    }
}
