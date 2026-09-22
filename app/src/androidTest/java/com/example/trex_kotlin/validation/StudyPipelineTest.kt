package com.example.trex_kotlin.validation

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** 합성 영상은 시간·저장·재생 계약만 검증하며 사람의 운동 정답으로 사용하지 않는다. */
@RunWith(AndroidJUnit4::class)
class StudyPipelineTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val times=listOf(0L,100_000L,250_000L,480_000L,710_000L,1_010_000L,1_320_000L,1_650_000L)
    @Test fun decodedTimeUsesRealVariablePts() {
        val file=File(context.cacheDir,"vfr-${newId()}.mp4")
        makeVideo(file)
        val actual=mutableListOf<Long>(); val cancelled=AtomicBoolean(false)
        try {
            VideoFrames.decode(file,cancelled,1) { bitmap,pts,relative ->
                assertTrue(bitmap.width>0); assertEquals(pts,relative); actual+=pts
            }
            assertEquals(times,actual)
        } finally {file.delete()}
    }
    @Test fun cancellationFailsWithoutReturningPartialSuccess() {
        val file=File(context.cacheDir,"cancel-${newId()}.mp4"); makeVideo(file)
        try { VideoFrames.decode(file,AtomicBoolean(true)) { _,_,_ -> fail("취소 뒤에는 프레임을 처리하면 안 됩니다.") }; fail("취소가 누락됐습니다.") }
        catch(e: IllegalStateException) { assertTrue(e.message!!.contains("중단")) }
        finally {file.delete()}
    }
    @Test fun videoToLabelsReplayAndZip() {
        val store=StudyStore(context)
        val dir=store.create("SYNTHETIC-${newId().take(8)}","2026-09-23","calibration","바벨 스쿼트","SIMULTANEOUS","정면",false,false,"synthetic_test")
        try {
            makeVideo(File(dir,"video.mp4")); store.finishVideo(dir)
            val processor=StudyProcessor(context)
            val extraction=processor.extract(dir,AtomicBoolean(false)) {}
            assertEquals("COMPLETE",readJson(File(extraction,"manifest.json")).getString("status"))
            val rows=File(extraction,"frames.jsonl").readLines()
            assertEquals(7,rows.size)
            val raw=org.json.JSONObject(rows.first())
            assertEquals(99,raw.getJSONArray("world").length()); assertEquals(33,raw.getJSONArray("raw_presence").length())
            val duration=readJson(File(dir,"session.json")).getLong("duration_ms")
            val truth=Truth("SYNTHETIC_FIXTURE",0,duration,true,emptyList(),listOf(TruthForm(0,duration,"unobservable-test","UNKNOWN")))
            val label=store.saveTruth(dir,truth)
            assertEquals(truth,store.truth(label))
            val run=processor.evaluate(dir,extraction,label,AtomicBoolean(false)) {}
            val report=readJson(File(run,"report.json"))
            assertEquals(0,report.getJSONObject("v2_reps").getInt("truth"))
            assertEquals(0,report.getJSONObject("v2_reps").getInt("predicted"))
            assertTrue(report.getJSONArray("v2_form").getJSONObject(0).isNull("coverage"))
            val rerun=processor.evaluate(dir,extraction,label,AtomicBoolean(false)) {}
            assertEquals(File(run,"predictions.jsonl").readText(),File(rerun,"predictions.jsonl").readText())
            val second=store.saveTruth(dir,truth.copy(reviewer="SECOND_FIXTURE"))
            assertNotEquals(label.name,second.name); assertTrue(label.exists())
            val zip=File(context.cacheDir,"export-${newId()}.zip")
            try {
                store.export(dir,Uri.fromFile(zip))
                val names=mutableListOf<String>()
                ZipInputStream(zip.inputStream()).use { z -> while(true) {val entry=z.nextEntry ?: break;names+=entry.name;z.closeEntry()} }
                assertTrue(names.contains("video.mp4"));assertTrue(names.contains("checksums.json"))
                assertTrue(names.contains("runs/${run.name}/report.json"))
            } finally {zip.delete()}
            // 원본과 좌표 변조는 조용히 받아들이지 않는다.
            File(extraction,"frames.jsonl").appendText("\n")
            try {processor.evaluate(dir,extraction,label,AtomicBoolean(false)) {};fail("해시 오류가 누락됐습니다.")} catch(_: IllegalArgumentException) {}
        } finally { check(dir.parentFile!!.canonicalFile == store.root.canonicalFile);dir.deleteRecursively() }
    }
    @Test fun personCannotLeakAcrossSplits() {
        val store=StudyStore(context);val person="SYNTHETIC-${newId().take(8)}"
        val dir=store.create(person,"2026-09-23","train","바벨 스쿼트","SIMULTANEOUS","정면",false,false,"synthetic_test")
        try {store.create(person,"2026-09-24","test","바벨 스쿼트","SIMULTANEOUS","정면",false,false,"synthetic_test");fail("분할 충돌을 차단해야 합니다.")}
        catch(_: IllegalArgumentException) {}
        finally {check(dir.parentFile!!.canonicalFile == store.root.canonicalFile);dir.deleteRecursively()}
    }

    fun makeVideo(file: File) {
        val width=320; val height=240
        val codec=MediaCodec.createEncoderByType("video/avc")
        val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,500_000);setInteger(MediaFormat.KEY_FRAME_RATE,10);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1;var started=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            var input=0;var ended=false;val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+30_000_000_000L
            while(!ended) {
                check(System.nanoTime()<deadline)
                if(input<=times.size) {
                    val i=codec.dequeueInputBuffer(10_000)
                    if(i>=0) {
                        if(input == times.size) codec.queueInputBuffer(i,0,0,times.last()+100_000,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val buffer=codec.getInputBuffer(i)!!;buffer.clear()
                            buffer.put(ByteArray(width*height) {(32+input*12).toByte()})
                            buffer.put(ByteArray(width*height/2) {128.toByte()})
                            codec.queueInputBuffer(i,0,width*height*3/2,times[input],0)
                        }
                        input++
                    }
                }
                when(val o=codec.dequeueOutputBuffer(info,10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {track=muxer.addTrack(codec.outputFormat);muxer.start();started=true}
                    else -> if(o>=0) {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data=codec.getOutputBuffer(o)!!;data.position(info.offset);data.limit(info.offset+info.size);muxer.writeSampleData(track,data,info)
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(o,false)
                    }
                }
            }
        } finally {codec.stop();codec.release();if(started)muxer.stop();muxer.release()}
    }
}
