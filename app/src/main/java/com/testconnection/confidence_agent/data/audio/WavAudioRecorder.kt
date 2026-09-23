package com.testconnection.confidence_agent.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class WavAudioRecorder(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var outputFile: File? = null
    private var recordedBytes = 0L

    @SuppressLint("MissingPermission")
    fun start(): File {
        check(!recording.get()) { "Recorder is already running" }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "当前设备不支持录音格式" }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)
        val file = File.createTempFile("duck_voice_", ".wav", context.cacheDir)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "录音设备初始化失败" }

        outputFile = file
        audioRecord = recorder
        recordedBytes = 0
        recording.set(true)
        recorder.startRecording()
        worker = Thread {
            FileOutputStream(file).use { output ->
                output.write(ByteArray(44))
                val buffer = ByteArray(bufferSize)
                while (recording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        recordedBytes += read
                    }
                }
            }
        }.apply {
            name = "DuckWavRecorder"
            start()
        }
        return file
    }

    fun stop(): File? {
        if (!recording.getAndSet(false)) return outputFile
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        worker = null
        return outputFile?.also { writeHeader(it, recordedBytes) }
    }

    fun cancel() {
        stop()?.delete()
        outputFile = null
    }

    private fun writeHeader(file: File, dataSize: Long) {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeLittleEndianInt(36 + dataSize)
            wav.writeBytes("WAVEfmt ")
            wav.writeLittleEndianInt(16)
            wav.writeLittleEndianShort(1)
            wav.writeLittleEndianShort(CHANNEL_COUNT)
            wav.writeLittleEndianInt(SAMPLE_RATE.toLong())
            wav.writeLittleEndianInt(byteRate.toLong())
            wav.writeLittleEndianShort(CHANNEL_COUNT * BITS_PER_SAMPLE / 8)
            wav.writeLittleEndianShort(BITS_PER_SAMPLE)
            wav.writeBytes("data")
            wav.writeLittleEndianInt(dataSize)
        }
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Long) {
        write(
            byteArrayOf(
                value.toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte(),
            )
        )
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte()))
    }
}
