package com.thenovisx.edicion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineTest {
    private fun video(id: String, ms: Long) = Clip(id = id, uri = "content://$id", kind = MediaKind.VIDEO, sourceDurationMs = ms)
    private fun image(id: String, ms: Long = DEFAULT_IMAGE_DURATION_MS) =
        Clip(id = id, uri = "content://$id", kind = MediaKind.IMAGE, sourceDurationMs = MAX_IMAGE_DURATION_MS, trimEndMs = ms)

    private val base = Project(id = "p", name = "Test", clips = listOf(video("a", 10_000), image("b"), video("c", 5_000)))

    @Test
    fun durationSumsClips() {
        assertEquals(18_000, base.durationMs)
    }

    @Test
    fun speedChangesDuration() {
        val p = Timeline.updateClip(base, "a") { it.copy(speed = 2f) }
        assertEquals(5_000, p.clips[0].durationMs)
        assertEquals(13_000, p.durationMs)
    }

    @Test
    fun speedIsClamped() {
        val p = Timeline.updateClip(base, "a") { it.copy(speed = 100f) }
        assertEquals(4f, p.clips[0].speed)
    }

    @Test
    fun clipAtFindsCorrectClip() {
        assertEquals(0, Timeline.clipAt(base, 0)!!.index)
        assertEquals(1, Timeline.clipAt(base, 10_500)!!.index)
        assertEquals(500, Timeline.clipAt(base, 10_500)!!.offsetMs)
        assertEquals(2, Timeline.clipAt(base, 99_999)!!.index)
        assertNull(Timeline.clipAt(base.copy(clips = emptyList()), 0))
    }

    @Test
    fun splitVideoKeepsTotalDuration() {
        val (p, newId) = Timeline.splitAt(base, 4_000, "a2")
        assertEquals("a2", newId)
        assertEquals(4, p.clips.size)
        assertEquals(4_000, p.clips[0].trimEndMs)
        assertEquals(4_000, p.clips[1].trimStartMs)
        assertEquals(10_000, p.clips[1].trimEndMs)
        assertEquals(base.durationMs, p.durationMs)
    }

    @Test
    fun splitRespectsSpeed() {
        val fast = Timeline.updateClip(base, "a") { it.copy(speed = 2f) }
        val (p, _) = Timeline.splitAt(fast, 1_000, "a2")
        assertEquals(2_000, p.clips[0].trimEndMs)
        assertEquals(fast.durationMs, p.durationMs)
    }

    @Test
    fun splitImage() {
        val (p, newId) = Timeline.splitAt(base, 11_000, "b2")
        assertEquals("b2", newId)
        assertEquals(1_000, p.clips[1].durationMs)
        assertEquals(2_000, p.clips[2].durationMs)
        assertEquals(base.durationMs, p.durationMs)
    }

    @Test
    fun splitAtEdgeDoesNothing() {
        val (p, newId) = Timeline.splitAt(base, 10_000, "x")
        assertNull(newId)
        assertEquals(base, p)
    }

    @Test
    fun trimIsClampedToSource() {
        val p = Timeline.trimClip(base, "c", -500, 99_000)
        assertEquals(0, p.clips[2].trimStartMs)
        assertEquals(5_000, p.clips[2].trimEndMs)
        val q = Timeline.trimClip(base, "c", 3_000, 3_000)
        assertEquals(MIN_CLIP_DURATION_MS, q.clips[2].trimmedDurationMs)
    }

    @Test
    fun moveAndDuplicate() {
        val moved = Timeline.moveClip(base, "c", 0)
        assertEquals(listOf("c", "a", "b"), moved.clips.map { it.id })
        val dup = Timeline.duplicateClip(base, "b", "b2")
        assertEquals(listOf("a", "b", "b2", "c"), dup.clips.map { it.id })
    }

    @Test
    fun audioDoesNotOverlap() {
        var p = Timeline.addAudio(base, AudioClip(id = "m1", uri = "u", sourceDurationMs = 5_000))
        p = Timeline.addAudio(p, AudioClip(id = "m2", uri = "u", sourceDurationMs = 5_000))
        assertEquals(5_000, p.audio[1].startMs)
        p = Timeline.updateAudio(p, "m1") { it.copy(startMs = 2_000) }
        val m1 = p.audio.first { it.id == "m1" }
        val m2 = p.audio.first { it.id == "m2" }
        assertEquals(2_000, m1.startMs)
        assertTrue(m2.startMs >= m1.endMs)
    }

    @Test
    fun splitAudio() {
        val p = Timeline.addAudio(base, AudioClip(id = "m1", uri = "u", sourceDurationMs = 5_000), 1_000)
        val (q, id) = Timeline.splitAudioAt(p, 3_000, "m2")
        assertEquals("m2", id)
        assertEquals(2_000, q.audio[0].durationMs)
        assertEquals(3_000, q.audio[1].startMs)
        assertEquals(2_000, q.audio[1].trimStartMs)
    }

    @Test
    fun textsAreClampedToVideo() {
        val p = Timeline.addText(base, TextLayer(id = "t", text = "Hola", startMs = 17_000, endMs = 30_000, x = 3f))
        assertEquals(18_000, p.texts[0].endMs)
        assertEquals(1f, p.texts[0].x)
        assertTrue(p.texts[0].isVisibleAt(17_500))
        assertFalse(p.texts[0].isVisibleAt(5_000))
    }

    @Test
    fun jsonRoundTrip() {
        val p = Timeline.addText(base, TextLayer(id = "t", text = "Hola", startMs = 0, endMs = 1_000))
        assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
    }

    @Test
    fun historyUndoRedo() {
        val h = History(1)
        h.push(2); h.push(3)
        assertEquals(2, h.undo())
        assertEquals(1, h.undo())
        assertFalse(h.canUndo)
        assertEquals(2, h.redo())
        h.push(5)
        assertFalse(h.canRedo)
    }

    @Test
    fun outputSizes() {
        assertEquals(1080 to 1920, AspectRatio.VERTICAL_9_16.outputSize(1080))
        assertEquals(1920 to 1080, AspectRatio.HORIZONTAL_16_9.outputSize(1080))
        assertEquals(1080 to 1350, AspectRatio.PORTRAIT_4_5.outputSize(1080))
    }

    @Test
    fun colorMatrices() {
        assertTrue(ColorMath.combined(FilterPreset.NONE, 1f, Adjustments()).isIdentity)
        val mono = ColorMath.filter(FilterPreset.MONO)
        // Una entrada gris debe seguir siendo gris.
        val r = mono[0, 0] + mono[0, 1] + mono[0, 2]
        assertEquals(1f, r, 1e-4f)
        val hue0 = ColorMath.hue(0f)
        assertEquals(1f, hue0[0, 0], 1e-3f)
        assertEquals(0f, hue0[0, 1], 1e-3f)
        val col = ColorMath.brightness(0.5f).toColumnMajor4x4()
        assertEquals(0.5f, col[12])
        assertEquals(1f, col[15])
        assertEquals(127.5f, ColorMath.brightness(0.5f).toAndroid4x5()[4])
        assertTrue(ColorMath.filter(FilterPreset.SEPIA).withIntensity(0f).isIdentity)
    }
}
