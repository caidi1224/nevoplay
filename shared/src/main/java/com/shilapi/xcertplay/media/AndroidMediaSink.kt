package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val context: Context,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val mainMediaAudioBufferDurationMs: Int = MainMediaAudioBuffer.DEFAULT_DURATION_MS,
    private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
    mediaMetricsMonitor: MediaMetricsMonitor? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    onVideoFrameRendered: (() -> Unit)? = null,
) : MediaSink {
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    @Volatile private var videoFrameRendered = onVideoFrameRendered
    @Volatile private var firstFrameRendered = false
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Boolean>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    // Which screen streams the sink currently considers active. Needed so a listener installed
    // while a stream is already running still learns about it: the host re-installs its listener
    // when the UI is re-attached, and without a replay it never sees (110, true) and leaves the
    // picture frozen with no indication.
    private val activeScreenTypes = mutableSetOf<Int>()
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Replaces the first-frame listener. A host that adopts a background session installs its own
     * listener here, and the session has usually rendered already, so a latched frame is replayed
     * once: the wireless watchdog needs that proof even when the frame arrived before the takeover.
     */
    fun setVideoFrameRenderedListener(listener: (() -> Unit)?) {
        videoFrameRendered = listener
        if (listener != null && firstFrameRendered) listener.invoke()
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        screenStreamActiveChanged = listener
        if (listener == null) return
        // Snapshot under the lock, invoke outside it: a callback may re-enter the sink.
        val replay = synchronized(activeScreenTypes) { activeScreenTypes.toList() }
        replay.forEach { listener.invoke(it, true) }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
        videoDecoders.values.forEach { it.setMediaMetricsMonitor(monitor) }
        audioRenderers.values.forEach { it.setMediaMetricsMonitor(monitor) }
    }

    override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
        if (requestKeyFrame == null) videoRecoveryHandlers.remove(type)
        else videoRecoveryHandlers[type] = requestKeyFrame
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        // Stamped here rather than inside the decoder: this is the arrival instant the metrics
        // monitor and the touch-latency probe both measure from, and it is also the input PTS.
        videoDecoder(type).submit(naluBytes, System.nanoTime())
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
            videoRecoveryHandlers.remove(type)
        }
        synchronized(activeScreenTypes) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
        }
        screenStreamActiveChanged?.invoke(type, active)
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(id) {
            MicrophoneUplink(context, config, microphoneGainPercent)
        }
        if (!uplink.start()) microphoneUplinks.remove(id, uplink)
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphoneUplinks.remove(id)?.close()
    }

    fun close() {
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        pendingVideoCodec.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                mediaMetricsMonitor,
                requestKeyFrame = { videoRecoveryHandlers[type]?.invoke() ?: false },
                onFirstFrameRendered = {
                    firstFrameRendered = true
                    videoFrameRendered?.invoke()
                },
            )
        }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            mainMediaAudioBufferDurationMs,
            mediaMetricsMonitor,
        ).also { audioRenderers[id] = it }
    }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedUs: Long) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val requestKeyFrame: () -> Boolean,
    private val onFirstFrameRendered: () -> Unit,
) : Closeable {
    private val queue = VideoWorkQueue<VideoJob>(8)
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var pendingSinceNs = 0L
    private var lastPtsUs = 0L
    private var statsStartNs = System.nanoTime()
    private var inputCount = 0
    private var outputCount = 0
    private var inputRetries = 0
    private var syncSkips = 0
    private var maxOutputAgeUs = 0L
    // Queue residence (arrival -> fed to the decoder) is measured separately from output age, so a
    // backlog in our own queue cannot be mistaken for the decoder being slow.
    private var maxAgeAtDequeueUs = 0L
    private var renderedCount = 0
    private var receivedBytes = 0L
    private var recoveries = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private var touchSamples = 0
    private var maxReleaseUs = 0L
    private var slowReleases = 0
    private var lowLatencyApplied = false
    private var needsKeyFrame = false
    private var lastKeyFrameRequestNs = 0L
    @Volatile private var requestedSurface: Surface? = surface
    private var outputSurface: Surface? = surface
    private var heldFrame: VideoJob.Frame? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    /** [arrivalNs] is the instant the frame was handed to the sink; it also becomes the input PTS. */
    fun submit(nalus: ByteArray, arrivalNs: Long) {
        receivedBytes += nalus.size
        val touchLatency = TouchLatencyProbe.onFrame(arrivalNs)
        if (touchLatency >= 0) {
            touchLatencySumNs += touchLatency
            if (touchLatency > maxTouchLatencyNs) maxTouchLatencyNs = touchLatency
            touchSamples++
        }
        queue.frame(VideoJob.Frame(nalus, arrivalNs / 1000))
    }

    fun setSurface(surface: Surface?) {
        requestedSurface = surface
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                try {
                    if (outputSurface !== requestedSurface) changeSurface(requestedSurface)
                    if (needsKeyFrame && outputSurface != null &&
                        System.nanoTime() - lastKeyFrameRequestNs >= KEY_FRAME_REQUEST_INTERVAL_NS
                    ) {
                        lastKeyFrameRequestNs = System.nanoTime()
                        recoveries++
                        Log.i(TAG, "video recovery key frame requested sent=${requestKeyFrame()}")
                    }
                    // Poll output independently of input arrival, including the final/static frame.
                    pump?.tick()
                    logVideoStats()
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled; waiting for a new random access frame"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        Thread.sleep(5)
                        continue
                    }
                    when (val job = heldFrame ?: queue.poll(5)) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            // receivedUs is microseconds, matching System.nanoTime() / 1000.
                            maxAgeAtDequeueUs = maxOf(
                                maxAgeAtDequeueUs,
                                System.nanoTime() / 1000 - job.receivedUs,
                            )
                            // Preserve the initial random access picture until a Surface exists.
                            heldFrame = if (outputSurface == null) job else null
                            if (outputSurface != null) feed(job)
                        }
                        null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder failed; restarting at random access", error)
                    releaseDecoder()
                    needsKeyFrame = true
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame = null
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        // A config has arrived, so frames are no longer waiting for one; let it be logged again if
        // that ever happens once more in this session.
        awaitingConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd: ByteArray
        val pps: ByteArray?
        if (codec == VideoCodec.H265) {
            csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            pps = null
        } else {
            val sets = MediaCodecSupport.avcParameterSets(codecData)
            require(sets.first.isNotEmpty() && sets.second.isNotEmpty()) { "Missing AVC parameter sets" }
            csd = START_CODE + sets.first
            pps = START_CODE + sets.second
        }
        require(csd.isNotEmpty()) { "Missing or invalid video parameter sets" }
        val parameters = VideoParameters.parse(codec, csd)
        val codecSpecificData = listOfNotNull(csd.takeIf { it.isNotEmpty() }, pps)
        // A vendor decoder can answer BAD_VALUE to the tuned keys (input size, priority, colour, low
        // latency), and a single rejected configure used to leave this decoder restarting for every
        // frame that followed. Walk down: tuned, then minimal, then the software decoder by name.
        val attempts = buildList {
            add(DecoderAttempt(codecName = null, tuned = true))
            add(DecoderAttempt(codecName = null, tuned = false))
            softwareDecoderName(mime)?.let { add(DecoderAttempt(codecName = it, tuned = false)) }
        }
        var next: MediaCodec? = null
        var lastFailure: Exception? = null
        for (attempt in attempts) {
            val configured = tryConfigureDecoder(
                attempt = attempt,
                mime = mime,
                parameters = parameters,
                codecSpecificData = codecSpecificData,
                surface = surface,
            )
            if (configured != null) {
                next = configured
                break
            }
            lastFailure = Exception(
                "Video decoder attempt failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned}",
            )
        }
        if (next == null) {
            throw lastFailure ?: IllegalStateException("No usable video decoder for $mime")
        }
        decoder = next
        syncGate = VideoSyncGate(codec)
        pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = drainOutput(next)
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                val index = next.dequeueInputBuffer(0)
                if (index < 0) {
                    inputRetries++
                    return false
                }
                val input = checkNotNull(next.getInputBuffer(index)) { "Missing video input buffer" }
                input.clear()
                check(bytes.size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                input.put(bytes)
                next.queueInputBuffer(index, 0, bytes.size, presentationTimeUs, 0)
                inputCount++
                return true
            }
        })
        Log.i(TAG, "video SPS parameters=$parameters negotiated=${width}x$height")
        renderedFrameLogged = false
        submittedFrameLogged = false
        Log.i(
            TAG,
            "video decoder configured name=${next.name} mime=$mime " +
                "lowLatency=$lowLatencyApplied; waiting for random access",
        )
    }

    /** One rung of the decoder ladder: which codec, and whether the tuned keys are offered. */
    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    /**
     * Builds and configures one decoder, returning null when this rung is rejected.
     *
     * [DecoderAttempt.tuned] adds the keys asking for low latency and a large input buffer; the
     * minimal rung drops them, because their presence is what some vendor decoders answer BAD_VALUE
     * to. Nothing is stored here: the caller keeps the first rung that works.
     */
    private fun tryConfigureDecoder(
        attempt: DecoderAttempt,
        mime: String,
        parameters: VideoParameters,
        codecSpecificData: List<ByteArray>,
        surface: Surface,
    ): MediaCodec? {
        val format = MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            codecSpecificData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Unspecified VUI fields remain unspecified; do not force full range or BT.709.
                if (parameters.colorStandard != -1) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
                }
                if (parameters.colorRange != -1) {
                    setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
                }
                if (parameters.colorTransfer != -1) {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
                }
            }
        }
        var candidate: MediaCodec? = null
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) }
                ?: createDecoder(mime)
            candidate = codec
            lowLatencyApplied = false
            if (attempt.tuned &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                lowLatencyApplied = true
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder attempt rejected name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /** The platform's software decoder for [mime], if this build has one. */
    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        if (decoder == null) {
            if (outputSurface == null) return
            val config = lastConfig
            if (config == null) {
                // Some HEVC senders start with picture frames and never send the AMC codec config
                // first. Without a config there is nothing to configure, so every frame used to be
                // dropped here in silence and the decoder was never created. Ask the sender for a
                // random access frame instead, and say so once so the case shows up in the log.
                if (!awaitingConfigLogged) {
                    awaitingConfigLogged = true
                    Log.i(TAG, "video frames without codec config; requesting key frame")
                }
                needsKeyFrame = true
                return
            }
            configureDecoder(config)
        }
        val activePump = pump ?: return
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        require(annexB.isNotEmpty()) { "Malformed video access unit" }
        if (syncGate?.accept(annexB) != true) {
            // RASL intentionally skipped after CRA does not mean sync was lost.
            if (syncGate?.waitingForRandomAccess == true) {
                needsKeyFrame = true
                syncSkips++
            }
            return
        }
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(TAG, "video decoder first random access input bytes=${annexB.size}")
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedUs)
        activePump.submit(annexB, lastPtsUs)
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    outputCount++
                    maxOutputAgeUs = maxOf(maxOutputAgeUs, System.nanoTime() / 1000 - info.presentationTimeUs)
                    // releaseOutputBuffer(render=true) hands the frame to the compositor and can block
                    // until a buffer is free, so its cost separates a slow compositor from a slow
                    // decoder - the two need opposite fixes.
                    val releaseStart = System.nanoTime()
                    codec.releaseOutputBuffer(index, render)
                    val releaseUs = (System.nanoTime() - releaseStart) / 1000
                    if (render) {
                        maxReleaseUs = maxOf(maxReleaseUs, releaseUs)
                        if (releaseUs >= SLOW_RELEASE_US) slowReleases++
                    }
                    if (render) renderedCount++
                    if (render) {
                        // feed() assigns the arrival stamp as the input PTS, so the latency the
                        // monitor reports is arrival -> handed to the compositor. It assumes
                        // MediaCodec preserves that PTS in BufferInfo.
                        mediaMetricsMonitor?.recordVideoFrameRendered(
                            arrivalNs = info.presentationTimeUs * 1_000L,
                            renderedNs = System.nanoTime(),
                        )
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                        // Proof that this session is live, for the host's wireless handoff
                        // watchdog: some iPhones never open the tunnel control channel even
                        // though the picture is already on screen.
                        onFirstFrameRendered()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logVideoStats() {
        val now = System.nanoTime()
        if (now - statsStartNs < 5_000_000_000L) return
        if (inputCount + outputCount + inputRetries + syncSkips > 0) {
            val seconds = (now - statsStartNs) / 1e9
            val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
            Log.i(TAG, "video stats inputFps=${inputCount / seconds} decodedFps=${outputCount / seconds} " +
                "shownFps=${renderedCount / seconds} kbps=${(receivedBytes * 8 / 1000) / seconds} " +
                "maxAgeAtDequeueMs=${maxAgeAtDequeueUs / 1000} recoveries=$recoveries " +
                "touch2frameAvgMs=$touchAvgMs touch2frameMaxMs=${maxTouchLatencyNs / 1_000_000} " +
                "touchSamples=$touchSamples touchSendMaxMs=${TouchLatencyProbe.maxSendNs / 1_000_000} " +
                "inputRetries=$inputRetries syncSkips=$syncSkips queued=${queue.size} " +
                "maxOutputAgeMs=${maxOutputAgeUs / 1000} maxReleaseMs=${maxReleaseUs / 1000} " +
                "slowReleases=$slowReleases waitingForSync=${syncGate?.waitingForRandomAccess}")
        }
        statsStartNs = now
        inputCount = 0
        outputCount = 0
        inputRetries = 0
        syncSkips = 0
        maxOutputAgeUs = 0
        maxAgeAtDequeueUs = 0L
        renderedCount = 0
        receivedBytes = 0L
        recoveries = 0
        touchLatencySumNs = 0L
        maxTouchLatencyNs = 0L
        touchSamples = 0
        TouchLatencyProbe.maxSendNs = 0L
        maxReleaseUs = 0
        slowReleases = 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        pump = null
        syncGate = null
        if (codec != null) {
            needsKeyFrame = true
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val SLOW_RELEASE_US = 30_000L
        const val INPUT_STALL_NS = 2_000_000_000L
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 2_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mainMediaAudioBufferDurationMs: Int,
    mediaMetricsMonitor: MediaMetricsMonitor?,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    // One line every AUDIO_STATS_INTERVAL_NS saying whether the track ran dry, and which of the two
    // failure shapes that sound alike it was. See logAudioStats().
    private var audioStatsStartNs = System.nanoTime()
    private var audioStatsPackets = 0
    private var audioStatsUnderruns = 0
    private var audioStatsDropped = 0
    // The other end of `audio playback started`: without it a log shows a stream beginning but
    // never stopping, which is how "which stream was audible at that moment" became guesswork.
    // See logTrackReleased().
    private var playbackStartedNs = 0L
    private var audioChannel: AudioChannel? = null
    private var audioUsage = 0
    // Buffered-but-unplayed audio, for the latency monitor: bytes handed to the track, the play
    // head, and (where the platform reports one) the output-latency estimate.
    private var trackBytesPerFrame = 0
    private var totalBytesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadRaw = 0L
    private var lastPlaybackHeadFrames = 0L
    private val audioTimestamp = AudioTimestamp()
    private var lastAudioTimestampQueryNs = 0L
    private var timestampHeadWraps = 0L
    private var lastTimestampHeadRaw = 0L
    private var lastTimestampHeadFrames = 0L
    private var latestOutputLatencyMs: Float? = null
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val trackChannelCount = if (format.channels >= 2) 2 else 1
        val channelMask = if (trackChannelCount == 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        // Only the main media stream is user configurable: a larger buffer trades latency for
        // resistance to bursts, while navigation and call audio keep the fixed heuristic.
        val configuredMainMediaBuffer = MainMediaAudioBuffer.isMainMedia(
            audioType = format.audioType,
            payloadType = format.payloadType,
        )
        val bufferBytes = if (configuredMainMediaBuffer) {
            MainMediaAudioBuffer.bufferSizeBytes(
                durationMs = mainMediaAudioBufferDurationMs,
                sampleRate = format.sampleRate,
                channelCount = format.channels,
                minBufferBytes = minBuffer,
            )
        } else {
            maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)
        }
        startThresholdBytes = if (format.audioType == "telephony" || format.audioType == "speechrecognition") {
            CallAudioBuffer.startThresholdBytes(format.sampleRate, trackChannelCount, minBuffer)
        } else {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // AudioTrack consumes 16-bit PCM, so a frame is as wide as the channel mask chosen above.
        trackBytesPerFrame = trackChannelCount * BYTES_PER_PCM_16_SAMPLE
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "bufferBytes=$bufferBytes" +
                if (configuredMainMediaBuffer) {
                    " configuredDurationMs=" +
                        MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
                } else {
                    ""
                },
        )
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        // Kept for the release line, so one place names the channel and the usage both here and at
        // the end of the track's life. Called once per track creation.
        audioChannel = selection.channel
        audioUsage = usage
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        audioStatsPackets++
        logAudioStats()
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            totalBytesWritten += count
            val metricsMonitor = mediaMetricsMonitor
            if (metricsMonitor != null && trackBytesPerFrame > 0) {
                val nowNs = System.nanoTime()
                val totalFramesWritten = totalBytesWritten / trackBytesPerFrame
                metricsMonitor.recordAudioBuffer(
                    totalFramesWritten = totalFramesWritten,
                    playbackHeadFrames = playbackHeadFrames(track),
                    sampleRate = format.sampleRate,
                    nowNs = nowNs,
                    outputLatencyMs = outputLatencyMs(track, totalFramesWritten, nowNs),
                )
            }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    playbackStartedNs = System.nanoTime()
                    Log.i(
                        TAG,
                        "audio playback started type=${format.payloadType} " +
                            "audioType=${format.audioType} channel=${audioChannel ?: "?"} " +
                            "usage=$audioUsage",
                    )
                }
            }
        }
    }

    /**
     * The one number that says whether the track ran dry, next to the numbers that say why.
     *
     * `AudioTrack.getUnderrunCount` is the only direct evidence of underrun on this path - the
     * counters around it exist because two failures sound identical from the driver's seat. An
     * underrun with `bufferedMs` at or near zero is the track starving, and a larger track is the
     * answer. The same silence with `queue` pinned at its cap and `codecDropped` climbing is the
     * opposite shape - the producer is ahead of a blocking `write` - and a larger track makes that
     * worse. Device logs showed single `decoder input unavailable ... dropped=1` events with
     * neither number recorded, which is exactly the ambiguity this line removes.
     */
    private fun logAudioStats() {
        val track = track ?: return
        val now = System.nanoTime()
        if (now - audioStatsStartNs < AUDIO_STATS_INTERVAL_NS) return
        val seconds = (now - audioStatsStartNs).toDouble() / NANOS_PER_SECOND
        val underruns = track.underrunCount
        val framesWritten =
            if (trackBytesPerFrame > 0) totalBytesWritten / trackBytesPerFrame else 0L
        val bufferedFrames = (framesWritten - playbackHeadFrames(track)).coerceAtLeast(0L)
        val bufferedMs =
            if (format.sampleRate > 0) bufferedFrames * 1000L / format.sampleRate else 0L
        Log.i(
            TAG,
            "audio stats type=${format.payloadType} codec=${format.codec} " +
                "packets=${(audioStatsPackets / seconds).toInt()}/s " +
                "underruns=${underruns - audioStatsUnderruns}(+$underruns) bufferedMs=$bufferedMs " +
                "queue=${queue.size}/$MAX_QUEUED_PACKETS " +
                "codecDropped=${inputDropped - audioStatsDropped}(+$inputDropped) " +
                "playing=$playbackStarted",
        )
        audioStatsStartNs = now
        audioStatsPackets = 0
        audioStatsUnderruns = underruns
        audioStatsDropped = inputDropped
    }

    /** Unwraps the 32-bit play head, which the platform lets wrap roughly every 24 hours. */
    private fun playbackHeadFrames(track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and UINT32_MASK
        if (raw < lastPlaybackHeadRaw && lastPlaybackHeadRaw - raw > UINT32_HALF_RANGE) {
            playbackHeadWraps++
        }
        lastPlaybackHeadRaw = raw
        val unwrapped = playbackHeadWraps * UINT32_MODULUS + raw
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, unwrapped)
        return lastPlaybackHeadFrames
    }

    /**
     * Frames still sitting between the mixer and the speaker, from `AudioTrack.getTimestamp`, or null
     * when the platform does not report a timestamp for this track.
     */
    private fun outputLatencyMs(
        track: AudioTrack,
        totalFramesWritten: Long,
        nowNs: Long,
    ): Float? {
        if (!playbackStarted) return null
        if (nowNs - lastAudioTimestampQueryNs < AUDIO_TIMESTAMP_INTERVAL_NS) {
            return latestOutputLatencyMs
        }
        lastAudioTimestampQueryNs = nowNs
        if (!track.getTimestamp(audioTimestamp)) {
            latestOutputLatencyMs = null
            return null
        }
        val raw = audioTimestamp.framePosition and UINT32_MASK
        if (raw < lastTimestampHeadRaw && lastTimestampHeadRaw - raw > UINT32_HALF_RANGE) {
            timestampHeadWraps++
        }
        lastTimestampHeadRaw = raw
        val unwrapped = timestampHeadWraps * UINT32_MODULUS + raw
        lastTimestampHeadFrames = maxOf(lastTimestampHeadFrames, unwrapped)
        val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
        val elapsedFrames = (elapsedNs.toDouble() * format.sampleRate / NANOS_PER_SECOND).toLong()
        val presentedFrames = minOf(totalFramesWritten, lastTimestampHeadFrames + elapsedFrames)
        return MediaMetricsMonitor.audioLatencyMs(
            totalFramesWritten,
            presentedFrames,
            format.sampleRate,
        ).also { latestOutputLatencyMs = it }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            logTrackReleased(track)
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * The other end of `audio playback started`.
     *
     * Nothing used to record when a track stopped, so a device log could show that a stream began
     * and never that it ended. "Which stream was audible at that moment" then had to be inferred
     * from the phone's TEARDOWN messages, which say what the phone stopped sending, not what this
     * device stopped playing - and a perceived change in loudness arrived with no line to confirm or
     * deny it. The channel and usage are repeated because those are the values the vehicle routes
     * and ducks by, and the counters are repeated so one line summarises the whole track.
     */
    private fun logTrackReleased(track: AudioTrack) {
        val playedMs = if (playbackStarted && playbackStartedNs != 0L) {
            (System.nanoTime() - playbackStartedNs) / NANOS_PER_MILLISECOND
        } else {
            0L
        }
        Log.i(
            TAG,
            "audio track released type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} channel=${audioChannel ?: "?"} usage=$audioUsage " +
                "started=$playbackStarted playedMs=$playedMs " +
                "underruns=${runCatching { track.underrunCount }.getOrDefault(-1)} " +
                "bytesWritten=$totalBytesWritten codecDropped=$inputDropped",
        )
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val AUDIO_TIMESTAMP_INTERVAL_NS = 200_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        /** Long enough that the line is one row per interval in a log, short enough to place a glitch. */
        const val AUDIO_STATS_INTERVAL_NS = 5_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_PCM_16_SAMPLE = 2
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_HALF_RANGE = 0x8000_0000L
        const val UINT32_MODULUS = 0x1_0000_0000L
    }
}
