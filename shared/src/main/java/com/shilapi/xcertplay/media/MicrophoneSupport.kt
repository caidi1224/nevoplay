package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal const val MICROPHONE_CAPTURE_RATE_HZ = 16_000

internal fun openMicrophoneRecorder(requestedBufferBytes: Int): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(
        MICROPHONE_CAPTURE_RATE_HZ,
        AndroidAudioFormat.CHANNEL_IN_MONO,
        AndroidAudioFormat.ENCODING_PCM_16BIT,
    )
    check(minBuffer > 0) { "microphone unavailable at $MICROPHONE_CAPTURE_RATE_HZ Hz" }
    val recorder = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MICROPHONE_CAPTURE_RATE_HZ)
                .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer * 2, requestedBufferBytes))
        .build()
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        error("microphone recorder failed to initialize")
    }
    return recorder
}

object MicrophoneGain {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 10

    fun sanitize(percent: Int): Int =
        percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    internal fun applyPcm16InPlace(buffer: ByteArray, count: Int, percent: Int): Int {
        require(count in 0..buffer.size)
        val gain = sanitize(percent)
        var peak = 0
        var offset = 0
        while (offset + 1 < count) {
            val sample = (
                (buffer[offset].toInt() and 0xff) or
                    (buffer[offset + 1].toInt() shl 8)
                ).toShort().toInt()
            val amplified =
                (sample * gain / 100).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[offset] = amplified.toByte()
            buffer[offset + 1] = (amplified ushr 8).toByte()
            peak = maxOf(peak, kotlin.math.abs(amplified))
            offset += 2
        }
        return peak
    }

    internal fun peakPercent(peak: Int): Int =
        (peak.coerceIn(0, 32_768) * 100 / 32_768).coerceIn(0, 100)
}

/**
 * Keeps a communication audio mode active until every microphone recorder has closed.
 *
 * The mode is what routes the capture to the car's microphone, so it is worth asking for - but
 * failing to enter it must not cost the uplink. During a hands-free call the telephony stack
 * already owns the mode ([android.media.AudioManager.MODE_IN_CALL]), and that is both a
 * communication mode and the one the call's own routing is built on. Treating only
 * [android.media.AudioManager.MODE_IN_COMMUNICATION] as success aborted the capture there, so the
 * other party heard nothing at all - worse than an imperfect route, and exactly what a device log
 * shows happening on a call.
 */
internal class AudioModeLeaseManager(
    private val readMode: () -> Int,
    private val writeMode: (Int) -> Unit,
    private val communicationMode: Int,
    private val acceptableModes: Set<Int> = setOf(communicationMode),
    private val onAcquired: (found: Int, attemptedWrite: Boolean, effective: Int) -> Unit =
        { _, _, _ -> },
    private val onDegraded: (actualMode: Int) -> Unit = {},
) {
    private var users = 0
    private var previousMode = 0
    private var changedMode = false

    @Synchronized
    fun acquire(): Closeable {
        if (users == 0) {
            previousMode = readMode()
            changedMode = previousMode !in acceptableModes
            if (changedMode) writeMode(communicationMode)
            val effectiveMode = readMode()
            onAcquired(previousMode, changedMode, effectiveMode)
            if (effectiveMode !in acceptableModes) {
                // Something else owns the mode and refused ours. Capture anyway: a recorder that
                // runs with the wrong route beats one that never starts.
                onDegraded(effectiveMode)
            }
        }
        users++
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    @Synchronized
    private fun release() {
        check(users > 0)
        users--
        if (users == 0 && changedMode && readMode() == communicationMode) {
            writeMode(previousMode)
        }
    }
}

internal object MicrophoneAudioMode {
    private const val TAG = "xcertplay-usb"

    /** Communication modes the uplink is happy to capture under. */
    private val COMMUNICATION_MODES = setOf(
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_IN_CALL,
    )

    private var leases: AudioModeLeaseManager? = null

    @Synchronized
    fun acquire(context: Context): Closeable {
        val manager = leases ?: run {
            val audioManager =
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioModeLeaseManager(
                readMode = { audioManager.mode },
                writeMode = { audioManager.mode = it },
                communicationMode = AudioManager.MODE_IN_COMMUNICATION,
                acceptableModes = COMMUNICATION_MODES,
                // Which mode the capture is about to run under, recorded before the recorder is
                // built rather than only when it looks wrong: a device log showed AudioFlinger
                // refusing the record track on 12 of the 14 attempts to open the call microphone,
                // and nothing in it said what the mode was when that happened. Paired with the line
                // below on success and the recorder error on failure, one call decides whether
                // MODE_IN_CALL is what AudioFlinger objects to.
                onAcquired = { found, attemptedWrite, effective ->
                    Log.i(
                        TAG,
                        "microphone audio mode found=$found attemptedWrite=$attemptedWrite " +
                            "effective=$effective " +
                            "communicationMode=${AudioManager.MODE_IN_COMMUNICATION} " +
                            "callMode=${AudioManager.MODE_IN_CALL}",
                    )
                },
                onDegraded = { actualMode ->
                    Log.w(
                        TAG,
                        "audio mode stayed $actualMode, not " +
                            "${AudioManager.MODE_IN_COMMUNICATION}; starting the uplink anyway",
                    )
                },
            ).also { leases = it }
        }
        return manager.acquire()
    }
}

/** Records the same stream used by CarPlay and reports the amplified peak every ~100 ms. */
class MicrophoneLevelMonitor(
    context: Context,
    private val gainPercent: () -> Int,
    private val onPeakPercent: (Int) -> Unit,
    private val onStopped: (Throwable?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            thread = Thread(::capture, "microphone-level-test").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            running.set(false)
            onStopped(error)
            false
        }
    }

    private fun capture() {
        var failure: Throwable? = null
        var audioModeLease: Closeable? = null
        var activeRecorder: AudioRecord? = null
        try {
            audioModeLease = MicrophoneAudioMode.acquire(appContext)
            val frameBytes =
                MICROPHONE_CAPTURE_RATE_HZ * BYTES_PER_SAMPLE * REPORT_MILLIS / 1000
            activeRecorder = openMicrophoneRecorder(frameBytes * 2)
            recorder = activeRecorder
            if (!running.get()) return
            activeRecorder.startRecording()
            val buffer = ByteArray(frameBytes)
            while (running.get()) {
                val count =
                    activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("microphone level read failed code=$count")
                if (count == 0) continue
                val peak =
                    MicrophoneGain.applyPcm16InPlace(buffer, count, gainPercent())
                onPeakPercent(MicrophoneGain.peakPercent(peak))
            }
        } catch (error: Exception) {
            if (running.get()) failure = error
        } finally {
            running.set(false)
            recorder = null
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {
                // The recorder may already have been stopped by close().
            }
            try {
                activeRecorder?.release()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                audioModeLease?.close()
            } catch (_: Exception) {
                // Best effort.
            }
            onStopped(failure)
        }
    }

    override fun close() {
        running.set(false)
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; the worker releases the recorder.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val REPORT_MILLIS = 100
        const val CLOSE_JOIN_MILLIS = 500L
    }
}

internal fun expandMonoPcm(input: ByteArray, channels: Int): ByteArray {
    require(channels > 0 && input.size % 2 == 0)
    if (channels == 1) return input
    return ByteArray(input.size * channels).also { output ->
        for (sample in 0 until input.size / 2) {
            for (channel in 0 until channels) {
                val outputOffset = (sample * channels + channel) * 2
                output[outputOffset] = input[sample * 2]
                output[outputOffset + 1] = input[sample * 2 + 1]
            }
        }
    }
}

/** Streaming mono PCM16 resampler used between fixed Android capture and negotiated output. */
internal class PcmMonoResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private var inputSamples = 0L
    private var nextOutputPosition = 0L
    private var previousSample = 0
    private var pendingLowByte = -1

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    fun convert(input: ByteArray, offset: Int, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= input.size)
        val output = ByteArrayOutputStream(count * outputRate / inputRate + 4)
        var cursor = offset
        val end = offset + count
        if (pendingLowByte >= 0 && cursor < end) {
            writeSample(pendingLowByte or (input[cursor].toInt() shl 8), output)
            pendingLowByte = -1
            cursor++
        }
        while (cursor + 1 < end) {
            val sample =
                (input[cursor].toInt() and 0xff) or (input[cursor + 1].toInt() shl 8)
            writeSample(sample, output)
            cursor += 2
        }
        if (cursor < end) pendingLowByte = input[cursor].toInt() and 0xff
        return output.toByteArray()
    }

    private fun writeSample(rawSample: Int, output: ByteArrayOutputStream) {
        val sample = rawSample.toShort().toInt()
        val currentPosition = inputSamples * outputRate
        while (nextOutputPosition <= currentPosition) {
            val leftIndex = nextOutputPosition / outputRate
            val fraction = nextOutputPosition % outputRate
            val value = if (leftIndex == inputSamples || inputSamples == 0L) {
                sample
            } else {
                (
                    previousSample.toLong() * (outputRate - fraction) +
                        sample.toLong() * fraction
                ).div(outputRate).toInt()
            }
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            nextOutputPosition += inputRate
        }
        previousSample = sample
        inputSamples++
    }
}
