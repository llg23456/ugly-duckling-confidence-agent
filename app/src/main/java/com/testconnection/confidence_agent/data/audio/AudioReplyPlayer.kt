package com.testconnection.confidence_agent.data.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import java.io.File

class AudioReplyPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var currentFile: File? = null

    fun play(audioBytes: ByteArray) {
        stopAudio()
        val audioFile = File.createTempFile("duck-reply-", ".wav", context.cacheDir)
        audioFile.writeBytes(audioBytes)
        currentFile = audioFile
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setOnCompletionListener { stopAudio() }
            setOnErrorListener { _, _, _ ->
                stopAudio()
                true
            }
            prepare()
            start()
        }
    }

    /** A short two-note cue: character feedback only, never a replacement for speech. */
    fun playDuckCue() {
        toneGenerator?.release()
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 30).also {
            it.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
        }
    }

    fun stop() {
        stopAudio()
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun stopAudio() {
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }
}
