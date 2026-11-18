package app.booktts.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams mono float PCM into an AAC-LC .m4a file using the platform encoder. */
class AacEncoder(outFile: File, private val sampleRate: Int, bitrate: Int = 64_000) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var framesQueued = 0L
    private var wroteSamples = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(samples: FloatArray) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) pcm.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        pcm.flip()
        while (pcm.hasRemaining()) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(buf.remaining(), pcm.remaining()) and 1.inv()
                val slice = pcm.slice().also { it.limit(n) }
                buf.put(slice)
                pcm.position(pcm.position() + n)
                codec.queueInputBuffer(idx, 0, n, framesQueued * 1_000_000L / sampleRate, 0)
                framesQueued += n / 2
            }
            drain(endOfStream = false)
        }
    }

    /** Flushes the encoder and finalizes the file. */
    fun finish() {
        try {
            while (true) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, framesQueued * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
        } finally {
            release()
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted && wroteSamples) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        muxerStarted = false
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        wroteSamples = true
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
