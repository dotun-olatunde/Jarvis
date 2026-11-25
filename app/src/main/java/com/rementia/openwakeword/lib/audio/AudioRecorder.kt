package com.rementia.openwakeword.lib.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import android.content.Context
import kotlin.coroutines.coroutineContext
import android.annotation.SuppressLint

/**
 * Handles audio recording from device microphone.
 * Emits audio buffers as a Flow for processing.
 */
internal class AudioRecorder(
    private val context: Context
) {
    
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_IN_SHORTS = 1280
    }
    
    /**
     * Check if audio recording permission is granted.
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start recording audio and emit buffers as Flow.
     * 
     * @return Flow of float arrays containing audio samples
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<FloatArray> = flow {
        require(hasRecordPermission()) { "RECORD_AUDIO permission not granted" }
        
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_IN_SHORTS * 2)
        
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize AudioRecord" }
            val audioBuffer = ShortArray(BUFFER_SIZE_IN_SHORTS)
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start recording" }
            
            while (coroutineContext.isActive) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (!coroutineContext.isActive) break
                check(readCount >= 0) { "Microphone read failed ($readCount)" }
                
                if (readCount > 0) {
                    // Keep raw int16 scale (-32768..32767). openWakeWord's melspectrogram model and
                    // its "/10 + 2" transform are calibrated for int16 values; normalising to -1..1
                    // makes every frame look like near-silence, so the wake word never scores.
                    val floatBuffer = FloatArray(readCount) { i ->
                        audioBuffer[i].toFloat()
                    }
                    emit(floatBuffer)
                }
            }
        } finally {
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)
}
