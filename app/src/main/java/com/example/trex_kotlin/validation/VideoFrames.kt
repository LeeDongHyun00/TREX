package com.example.trex_kotlin.validation

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** 디코딩한 실제 프레임의 PTS만 사용한다. 요청한 시각을 프레임의 시각으로 대체하지 않는다. */
object VideoFrames {
    data class Metadata(val durationMs: Long, val rotation: Int)
    fun metadata(file: File): Metadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return Metadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
        } finally { retriever.release() }
    }
    fun decode(file: File, cancelled: AtomicBoolean, intervalUs: Long = 200_000,
        consume: (Bitmap, Long, Long) -> Unit): Int {
        require(intervalUs > 0)
        val metadata = metadata(file)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("영상 트랙이 없습니다.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_ROTATION,0)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format,null,null,0); decoder.start()
            var inputEnded = false; var outputEnded = false; var origin: Long? = null
            var next = 0L; var lastPts = Long.MIN_VALUE; var count = 0
            var progressAt = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                check(!cancelled.get()) { "분석을 중단했습니다. 원본 영상은 보존했습니다." }
                check((System.nanoTime()-progressAt)/1_000_000 < 30_000) { "디코더가 응답하지 않습니다. 다른 영상 형식으로 다시 시도해 주세요." }
                if(!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if(index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val n = extractor.readSampleData(buffer,0)
                        if(n < 0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info,10_000)
                if(index >= 0) {
                    try {
                        if(info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pts = info.presentationTimeUs
                            require(pts > lastPts) { "영상의 프레임 시각이 중복되거나 역행합니다." }
                            lastPts = pts
                            if(origin == null) origin = pts
                            val relative = pts-origin
                            if(relative >= next) {
                                val image = decoder.getOutputImage(index) ?: error("이 기기의 디코더에서 프레임 좌표를 읽을 수 없습니다.")
                                val bitmap = try { rgb(image,metadata.rotation) } finally { image.close() }
                                try { consume(bitmap,pts,relative) } finally { bitmap.recycle() }
                                count++; next = relative+intervalUs
                            }
                            progressAt = System.nanoTime()
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
            }
            require(count > 0) { "분석 가능한 영상 프레임이 없습니다." }
            return count
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
    private fun rgb(image: Image, rotation: Int): Bitmap {
        require(image.planes.size == 3) { "YUV 420 영상을 지원하는 디코더가 필요합니다." }
        val crop = image.cropRect
        val scale = minOf(1.0,640.0/maxOf(crop.width(),crop.height()))
        val w = (crop.width()*scale).roundToInt().coerceAtLeast(1)
        val h = (crop.height()*scale).roundToInt().coerceAtLeast(1)
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        val offsets = buffers.map { it.position() }
        fun value(p: Int, x: Int, y: Int): Int = buffers[p].get(offsets[p]+y*planes[p].rowStride+x*planes[p].pixelStride).toInt() and 255
        val pixels = IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val sx = crop.left+x*crop.width()/w; val sy = crop.top+y*crop.height()/h
            val yy = (value(0,sx,sy)-16).coerceAtLeast(0); val u = value(1,sx/2,sy/2)-128; val v = value(2,sx/2,sy/2)-128
            val r = ((298*yy+409*v+128) shr 8).coerceIn(0,255)
            val g = ((298*yy-100*u-208*v+128) shr 8).coerceIn(0,255)
            val b = ((298*yy+516*u+128) shr 8).coerceIn(0,255)
            pixels[y*w+x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val source = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        if(rotation == 0) return source
        val result = Bitmap.createBitmap(source,0,0,w,h,Matrix().apply { postRotate(rotation.toFloat()) },true)
        if(result !== source) source.recycle()
        return result
    }
}
