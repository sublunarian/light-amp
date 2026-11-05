package com.sublunar.amp.playback.output.airplay

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A track's audio from [extractor], decoded: Android's own software decoder
 * for everything but Apple Lossless, which LightOS has none that works for
 * and goes through [AlacDecoder] instead.
 *
 * [seekUs] is where in the stream to start. The extractor's seek can land
 * a frame past what it was asked (FLAC's does), so it's asked for a little
 * before, and what's decoded up to the target is dropped: the first sample
 * is the one asked for, and a decoder that needs a run-up (Opus) has had it.
 * [startMs] is what that is in the track.
 *
 * A stream whose length isn't known yet ([canSeek] false: a server
 * converting as it sends) is never sought in: Android's Ogg reader, asked to
 * seek in one, even to its start, reads nothing at all after. It's read from
 * its start and the frames before [seekUs] are dropped instead — a stream
 * opened partway already starts within a second of the place.
 */
internal fun decodedTrack(extractor: MediaExtractor, seekUs: Long, startMs: Long, canSeek: Boolean = true): PcmSource {
    var index = -1
    var format: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
            index = i
            format = f
            break
        }
    }
    if (format == null) throw IOException("no audio in the file")
    extractor.selectTrack(index)
    if (seekUs > 0 && canSeek) seekBefore(extractor, seekUs)
    return if (format.getString(MediaFormat.KEY_MIME) == ALAC_MIME) {
        AlacTrack(extractor, format, seekUs, startMs)
    } else {
        CodecTrack(extractor, format, seekUs, startMs)
    }
}

private const val ALAC_MIME = "audio/alac"
private const val SEEK_LEAD_US = 250_000L

/**
 * Put [extractor] at or before [targetUs]. Asked to seek, FLAC's lands on a
 * frame after the time asked (by up to a third of a second, measured on the
 * LP3), whatever the mode, so where it landed is checked and it's asked
 * again further back until it's at or before the target.
 */
private fun seekBefore(extractor: MediaExtractor, targetUs: Long) {
    var lead = SEEK_LEAD_US
    repeat(SEEK_TRIES) {
        val asked = (targetUs - lead).coerceAtLeast(0)
        extractor.seekTo(asked, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (asked == 0L || extractor.sampleTime in 0..targetUs) return
        lead *= 3
    }
    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
}

private const val SEEK_TRIES = 4

/**
 * Drops the decoded frames before the one asked for. The first block's time
 * places the stream; after it, frames are counted rather than read off each
 * block's time, which an extractor may round (FLAC's are whole ms). Where a
 * stream's frames are all [blockSize] long, the first time is put on that
 * grid, which takes the rounding out too.
 */
private class Skipper(private val targetUs: Long, private val rate: () -> Int, private val blockSize: Int = 0) {
    private var done = targetUs <= 0
    private var position = -1L

    /** How many of a block's frames, starting at [ptsUs], come before the target. */
    fun framesToSkip(ptsUs: Long, frames: Int): Int {
        if (done) return 0
        val r = rate().toLong()
        if (position < 0) {
            position = Math.round(ptsUs * r / 1_000_000.0)
            if (blockSize > 0) position = Math.round(position.toDouble() / blockSize) * blockSize
        }
        val target = Math.round(targetUs * r / 1_000_000.0)
        val skip = (target - position).coerceIn(0, frames.toLong()).toInt()
        position += frames
        if (skip < frames) done = true
        return skip
    }
}

private class AlacTrack(
    private val extractor: MediaExtractor,
    format: MediaFormat,
    seekUs: Long,
    override val startMs: Long,
) : PcmSource {
    private val decoder: AlacDecoder
    private val input: ByteBuffer
    private val out: IntArray
    private val skipper: Skipper

    init {
        val csd = format.getByteBuffer("csd-0") ?: throw IOException("an ALAC track without its cookie")
        val cookie = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        decoder = AlacDecoder(AlacConfig.parse(cookie))
        val max = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        input = ByteBuffer.allocate(maxOf(max, decoder.config.frameLength * decoder.config.channels * 4 + 64))
        out = IntArray(decoder.config.frameLength * decoder.config.channels)
        skipper = Skipper(seekUs, { decoder.config.sampleRate })
    }

    override val sampleRate: Int get() = decoder.config.sampleRate
    override val channels: Int get() = decoder.config.channels
    override val order: ChannelOrder get() = ChannelOrder.ALAC

    override fun next(block: PcmBlock): Boolean {
        while (true) {
            input.clear()
            val size = extractor.readSampleData(input, 0)
            if (size < 0) return false
            val pts = extractor.sampleTime
            extractor.advance()
            val frames = decoder.decode(input.array(), input.arrayOffset(), size, out)
            val skip = skipper.framesToSkip(pts, frames)
            val kept = frames - skip
            if (kept <= 0) continue
            val ch = channels
            val bits = decoder.config.bitDepth
            block.frames = kept
            if (bits == 16) {
                val shorts = block.shorts(kept * ch)
                for (i in 0 until kept * ch) shorts[i] = out[skip * ch + i].toShort()
            } else {
                val floats = block.floats(kept * ch)
                val scale = 1f / (1L shl (bits - 1)).toFloat()
                for (i in 0 until kept * ch) floats[i] = out[skip * ch + i] * scale
            }
            return true
        }
    }

    override fun close() {
        runCatching { extractor.release() }
    }
}

private class CodecTrack(
    private val extractor: MediaExtractor,
    format: MediaFormat,
    seekUs: Long,
    override val startMs: Long,
) : PcmSource {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private val skipper: Skipper

    override var sampleRate: Int = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private set
    override var channels: Int = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private set
    override val order: ChannelOrder get() = ChannelOrder.ANDROID

    private var formatRead = false

    /** The rate and channels are the Feed's plan from here on: see [readOutputFormat]. */
    private var handedOut = false

    // The first block, decoded at open so the rate and channels are the
    // decoder's own (an HE-AAC stream doubles its rate) before it's read.
    private var held: PcmBlock? = null

    init {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = createDecoder(mime)
        val wanted = MediaFormat(format)
        // Finer than 16 bits comes out as floats, to be dithered rather than truncated.
        if (format.containsKey(BITS_PER_SAMPLE) && format.getInteger(BITS_PER_SAMPLE) > 16) {
            wanted.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        try {
            codec.configure(wanted, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("the decoder wouldn't start", e)
        }
        skipper = Skipper(seekUs, { sampleRate }, fixedBlockSize(format))
        try {
            val first = PcmBlock()
            if (decodeInto(first)) held = first else outputDone = true
        } catch (e: Exception) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw e as? IOException ?: IOException("the first frame wouldn't decode", e)
        }
        handedOut = true
    }

    override fun next(block: PcmBlock): Boolean {
        held?.let {
            held = null
            block.frames = it.frames
            block.isShort = it.isShort
            if (it.isShort) block.shorts = it.shorts else block.floats = it.floats
            return true
        }
        if (outputDone) return false
        return decodeInto(block)
    }

    private fun decodeInto(block: PcmBlock): Boolean {
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val buffer = codec.getInputBuffer(i)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(codec.outputFormat)
                o >= 0 -> {
                    if (!formatRead) readOutputFormat(codec.outputFormat)
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val got = convert(codec.getOutputBuffer(o)!!, block)
                    codec.releaseOutputBuffer(o, false)
                    if (end) outputDone = true
                    if (got) return true
                }
            }
        }
        return false
    }

    private fun readOutputFormat(f: MediaFormat) {
        formatRead = true
        val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
        val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
        // The Feed laid its plan by the first block's format; a file that
        // changes partway (a concatenated MP3, a chained Ogg) ends there,
        // visibly, rather than playing on at the wrong speed.
        if (handedOut && (rate != sampleRate || ch != channels)) throw IOException("the format changed partway")
        sampleRate = rate
        channels = ch
        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
    }

    /** One output buffer into [block]; false when nothing of it is kept. */
    private fun convert(buffer: ByteBuffer, block: PcmBlock): Boolean {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val data = buffer.slice().order(ByteOrder.nativeOrder())
        val ch = channels
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val frames = info.size / (bytesPerSample * ch)
        val skip = skipper.framesToSkip(info.presentationTimeUs, frames)
        val kept = frames - skip
        if (kept <= 0) return false
        val first = skip * ch
        val samples = kept * ch
        block.frames = kept
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = block.floats(samples)
                data.asFloatBuffer().apply { position(first) }.get(floats, 0, samples)
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val floats = block.floats(samples)
                val ints = data.asIntBuffer()
                for (i in 0 until samples) floats[i] = ints.get(first + i) / 2_147_483_648f
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val floats = block.floats(samples)
                for (i in 0 until samples) {
                    val at = (first + i) * 3
                    val v = (data.get(at).toInt() and 0xff) or ((data.get(at + 1).toInt() and 0xff) shl 8) or (data.get(at + 2).toInt() shl 16)
                    floats[i] = v / 8_388_608f
                }
            }
            else -> {
                val shorts = block.shorts(samples)
                data.asShortBuffer().apply { position(first) }.get(shorts, 0, samples)
            }
        }
        return true
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val BITS_PER_SAMPLE = "bits-per-sample"

        /** FLAC's frame length where every frame has the same, as the extractor reports it. */
        fun fixedBlockSize(format: MediaFormat): Int {
            val min = if (format.containsKey("min-block-size")) format.getInteger("min-block-size") else 0
            val max = if (format.containsKey("max-block-size")) format.getInteger("max-block-size") else -1
            return if (min == max && min > 0) min else 0
        }

        /** Android's own software decoder where there is one: the same everywhere, and bit-exact for FLAC. */
        fun createDecoder(mime: String): MediaCodec {
            val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .map { it.name }
            val name = names.firstOrNull { it.startsWith("c2.android.") } ?: names.firstOrNull()
                ?: throw IOException("no decoder for $mime")
            return MediaCodec.createByCodecName(name)
        }
    }
}

/**
 * A stream fetched once into [file] while it's read: reads ahead of what
 * has arrived wait for it. The extractor seeks back and forth in what's
 * there, and a pause, a seek behind or a repeat reads it again without
 * asking the door twice for the same track (which would end the first fetch).
 *
 * Read and written through a RandomAccessFile under a lock, never a
 * FileChannel: an interrupt of a reading thread (a stopped Feed) closes a
 * channel for every reader, and the next run on this file would find it dead.
 */
internal class StreamFile(private val url: String, val file: File) {
    private val lock = Object()
    private val io = RandomAccessFile(file, "rw")

    @Volatile
    private var arrived = 0L

    @Volatile
    private var length = -1L

    @Volatile
    private var done = false

    @Volatile
    private var failure: IOException? = null

    @Volatile
    private var closed = false

    private var connection: HttpURLConnection? = null
    private val thread = Thread({ fetch() }, "AmpAirPlay-fetch").apply { isDaemon = true }

    init {
        thread.start()
    }

    private fun fetch() {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            connection = c
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("Accept-Encoding", "identity")
            val status = c.responseCode
            if (status !in 200..299) throw IOException("the door answered $status")
            val declared = c.contentLengthLong
            if (declared >= 0) length = declared
            c.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!closed) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(lock) {
                        io.seek(arrived)
                        io.write(buffer, 0, n)
                        arrived += n
                        lock.notifyAll()
                    }
                }
            }
            // A stream that stops short of the length it declared was cut,
            // not finished.
            if (declared >= 0 && arrived < declared && !closed) throw IOException("the stream ended short")
            synchronized(lock) {
                done = true
                length = arrived
                lock.notifyAll()
            }
        } catch (e: Exception) {
            fail(e as? IOException ?: IOException(e))
        } finally {
            connection?.disconnect()
        }
    }

    private fun fail(e: IOException) {
        synchronized(lock) {
            if (failure == null) failure = e
            lock.notifyAll()
        }
    }

    /** As much as is there of [size] bytes at [position], waiting for the first of them; -1 past the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        synchronized(lock) {
            var waitedSince = System.nanoTime()
            var seen = arrived
            while (!closed && position >= arrived && !done && failure == null) {
                lock.wait(WAIT_SLICE_MS)
                if (arrived != seen) {
                    seen = arrived
                    waitedSince = System.nanoTime()
                } else if (System.nanoTime() - waitedSince > STALL_NS) {
                    // Kept, so the track is seen to break rather than to end.
                    failure = IOException("the stream stalled")
                }
            }
            if (closed) throw IOException("closed")
            if (position >= arrived) {
                failure?.let { throw it }
                return -1
            }
            val n = minOf(size.toLong(), arrived - position).toInt()
            io.seek(position)
            return io.read(buffer, offset, n)
        }
    }

    /** The fetch broke off: reading it again needs a fetch of its own. */
    val failed: Boolean get() = failure != null

    /** The whole length where it's known: declared by the server, or once it has all arrived. */
    val size: Long get() = if (done) arrived else length

    /** A view for one extractor, which closes its source when it's done with it: this file outlives that. */
    fun source(): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            this@StreamFile.readAt(position, buffer, offset, size)

        override fun getSize(): Long = this@StreamFile.size

        override fun close() {}
    }

    fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { connection?.disconnect() }
        synchronized(lock) { runCatching { io.close() } }
        file.delete()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val WAIT_SLICE_MS = 250L
        const val STALL_NS = 25_000_000_000L
    }
}

/**
 * [inner] read from [file]: when it ends because the fetch broke (the
 * server went, the stream stalled), the end is a failure, not the track's
 * end — the extractor itself reports either as the end of the stream.
 */
internal class BreakAware(private val inner: PcmSource, private val file: StreamFile) : PcmSource by inner {
    override fun next(block: PcmBlock): Boolean {
        val more = inner.next(block)
        if (!more && file.failed) throw IOException("the stream broke")
        return more
    }
}
