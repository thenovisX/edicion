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

class Stage2Test {
    private fun video(id: String, ms: Long) = Clip(id = id, uri = "u", kind = MediaKind.VIDEO, sourceDurationMs = ms)

    @Test
    fun lanesAreIndependent() {
        var p = Project(id = "p", name = "n", clips = listOf(video("a", 20_000)))
        p = Timeline.addAudio(p, AudioClip(id = "m", uri = "u", sourceDurationMs = 5_000), 0)
        p = Timeline.addAudio(p, AudioClip(id = "v", uri = "u", sourceDurationMs = 5_000, lane = LANE_VOICE), 1_000)
        assertEquals(0, p.audio.first { it.id == "m" }.startMs)
        assertEquals(1_000, p.audio.first { it.id == "v" }.startMs)
        p = Timeline.addAudio(p, AudioClip(id = "v2", uri = "u", sourceDurationMs = 5_000, lane = LANE_VOICE), 2_000)
        assertEquals(6_000, p.audio.first { it.id == "v2" }.startMs)
    }

    @Test
    fun fades() {
        val p = Project(
            id = "p", name = "n",
            clips = listOf(
                video("a", 10_000).copy(transition = TransitionType.FADE_BLACK, transitionMs = 1_000),
                video("b", 10_000).copy(transition = TransitionType.FADE_WHITE, transitionMs = 1_000),
                video("c", 10_000),
            ),
        )
        // Fundido de entrada del primer clip.
        assertEquals(1f, Timeline.fadeAt(p, 0)!!.amount, 1e-3f)
        assertEquals(0.5f, Timeline.fadeAt(p, 500)!!.amount, 1e-3f)
        assertNull(Timeline.fadeAt(p, 5_000))
        // Corte a los 10 s: medio segundo a cada lado, en blanco.
        val before = Timeline.fadeAt(p, 9_750)!!
        assertTrue(before.white)
        assertEquals(0.5f, before.amount, 1e-3f)
        assertEquals(1f, Timeline.fadeAt(p, 10_000)!!.amount, 1e-3f)
        assertEquals(0.5f, Timeline.fadeAt(p, 10_250)!!.amount, 1e-3f)
        assertNull(Timeline.fadeAt(p, 20_000))
    }

    @Test
    fun oldProjectsStillLoad() {
        val old = """{"id":"p","name":"n","clips":[{"id":"a","uri":"u","kind":"VIDEO","sourceDurationMs":1000}],"texts":[{"id":"t","text":"x","startMs":0,"endMs":500}]}"""
        val p = ProjectJson.decode(old)
        assertEquals(TransitionType.NONE, p.clips[0].transition)
        assertEquals(TextFont.SANS, p.texts[0].font)
    }
}

class LayersTest {
    private val base = Project(id = "p", name = "n", clips = listOf(Clip(id = "a", uri = "u", kind = MediaKind.VIDEO, sourceDurationMs = 20_000)))
    private fun layer(id: String, s: Long, e: Long) = TextLayer(id = id, text = id, startMs = s, endMs = e)

    @Test
    fun rowsDoNotOverlap() {
        val layers = listOf(layer("a", 0, 5_000), layer("b", 1_000, 3_000), layer("c", 5_000, 8_000), layer("d", 2_000, 9_000))
        assertEquals(listOf(0, 1, 0, 2), Timeline.layerRows(layers, 4))
        assertEquals(listOf(0, 1, 0, 1), Timeline.layerRows(layers, 2))
    }

    @Test
    fun zOrder() {
        var p = base.copy(texts = listOf(layer("a", 0, 1_000), layer("b", 0, 1_000), layer("c", 0, 1_000)))
        p = Timeline.moveLayerZ(p, "a", toFront = true)
        assertEquals(listOf("b", "c", "a"), p.texts.map { it.id })
        p = Timeline.moveLayerZ(p, "c", toFront = false)
        assertEquals(listOf("c", "b", "a"), p.texts.map { it.id })
    }

    @Test
    fun videoLayerCannotExceedSource() {
        val v = layer("v", 1_000, 15_000).copy(videoUri = "u", sourceDurationMs = 4_000, trimStartMs = 1_000)
        val p = Timeline.addText(base, v)
        assertEquals(4_000, p.texts[0].endMs)
        assertEquals(2_500, LayerMath.videoPositionMs(p.texts[0], 2_500))
    }

    @Test
    fun animationState() {
        val l = layer("a", 1_000, 5_000).copy(animIn = LayerAnim.FADE, animOut = LayerAnim.SLIDE_UP)
        assertEquals(0f, LayerMath.stateAt(l, 500).alpha)
        assertEquals(0.5f, LayerMath.stateAt(l, 1_200).alpha, 1e-3f)
        val rest = LayerMath.stateAt(l, 3_000)
        assertEquals(1f, rest.alpha)
        assertEquals(0f, rest.dy)
        assertTrue(LayerMath.stateAt(l, 4_900).dy < 0f)
        val t = layer("t", 0, 4_000).copy(text = "0123456789", animIn = LayerAnim.TYPEWRITER)
        assertEquals(5, LayerMath.typewriterChars(t, 300))
        assertNull(LayerMath.typewriterChars(t, 700))
    }
}

class ColorGradeTest {
    private fun apply(p: ColorPipeline, r: Float, g: Float, b: Float) = floatArrayOf(r, g, b).also { p.apply(it) }

    @Test
    fun neutralPipelineIsIdentity() {
        val p = ColorPipeline(FilterPreset.NONE, 1f, Adjustments())
        assertTrue(p.isIdentity)
        val c = apply(p, 0.2f, 0.5f, 0.8f)
        assertEquals(0.2f, c[0], 1e-4f); assertEquals(0.5f, c[1], 1e-4f); assertEquals(0.8f, c[2], 1e-4f)
        val cube = p.buildCube(5)
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cube[4][4][4])
        assertEquals(0xFFFF0000.toInt(), cube[4][0][0])
        assertEquals(0xFF0000FF.toInt(), cube[0][0][4])
    }

    @Test
    fun exposureAndShadows() {
        val brighter = apply(ColorPipeline(FilterPreset.NONE, 1f, Adjustments(exposure = 0.5f)), 0.3f, 0.3f, 0.3f)
        assertTrue(brighter[0] > 0.3f)
        val shadows = ColorPipeline(FilterPreset.NONE, 1f, Adjustments(shadows = 1f))
        assertFalse(shadows.isMatrixOnly)
        assertTrue(apply(shadows, 0.2f, 0.2f, 0.2f)[0] > 0.2f)
        // El negro y el blanco puros no se mueven.
        assertEquals(0f, apply(shadows, 0f, 0f, 0f)[0], 1e-4f)
        assertEquals(1f, apply(shadows, 1f, 1f, 1f)[0], 1e-4f)
    }

    @Test
    fun hslOnlyTouchesItsBand() {
        val adj = Adjustments()
        val p = ColorPipeline(FilterPreset.NONE, 1f, adj.copy(hslSat = adj.withHsl(adj.hslSat, HslBand.RED, -1f)))
        val red = apply(p, 0.9f, 0.1f, 0.1f)
        assertTrue(red[0] - red[1] < 0.2f, "el rojo debe desaturarse")
        val blue = apply(p, 0.1f, 0.1f, 0.9f)
        assertEquals(0.9f, blue[2], 1e-3f)
        assertEquals(0.1f, blue[0], 1e-3f)
        val gray = apply(p, 0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, gray[0], 1e-4f)
    }

    @Test
    fun curvesKeepRange() {
        for (c in CurvePreset.entries) for (ch in 0..2) for (i in 0..10) {
            val v = ColorPipeline.curve(c, i / 10f, ch)
            assertTrue(v in 0f..1f, "$c fuera de rango")
        }
        assertEquals(0.5f, ColorPipeline.curve(CurvePreset.SOFT_S, 0.5f, 0), 1e-4f)
    }

    @Test
    fun pixelsKeepAlphaAndVignetteDarkensCorners() {
        val px = IntArray(25) { 0x80808080.toInt() }
        ColorPipeline(FilterPreset.NONE, 1f, Adjustments(vignette = 1f)).applyToPixels(px, 5, 5)
        assertEquals(0x80, px[0] ushr 24)
        assertEquals(0x80, px[12] and 0xFF)
        assertTrue((px[0] and 0xFF) < 0x80)
    }

    @Test
    fun everyFilterStaysInRange() {
        for (f in FilterPreset.entries) {
            val c = apply(ColorPipeline(f, 1f, Adjustments()), 0.3f, 0.6f, 0.9f)
            assertTrue(c.all { it in 0f..1f }, "$f")
        }
    }
}

class KeyframeTest {
    private fun layer() = TextLayer(id = "l", text = "hola", startMs = 1000, endMs = 5000)
    private fun project() = Project(
        id = "p", name = "p",
        clips = listOf(Clip(id = "c", uri = "u", kind = MediaKind.VIDEO, sourceDurationMs = 10_000)),
        texts = listOf(layer()),
    )

    @Test
    fun sinFotogramasClaveCambianLosValoresFijos() {
        val p = Timeline.setLayerPose(project(), "l", 2000) { it.copy(x = 0.5f, scale = 2f) }
        val l = p.texts.single()
        assertEquals(0.5f, l.x)
        assertEquals(2f, l.scale)
        assertTrue(l.keyframes.isEmpty())
    }

    @Test
    fun interpolaEntreFotogramasClave() {
        var p = Timeline.toggleKeyframe(project(), "l", 1000)
        p = Timeline.setLayerPose(p, "l", 3000) { it.copy(x = 1f) }
        val l = p.texts.single()
        assertEquals(2, l.keyframes.size)
        assertEquals(0f, LayerMath.poseAt(l, 1000).x)
        assertEquals(0.5f, LayerMath.poseAt(l, 2000).x, 0.001f)
        assertEquals(1f, LayerMath.poseAt(l, 4500).x)
        // Mover de nuevo en el mismo instante actualiza el fotograma en lugar de crear otro.
        val again = Timeline.setLayerPose(p, "l", 3020) { it.copy(x = -1f) }.texts.single()
        assertEquals(2, again.keyframes.size)
        assertEquals(-1f, again.keyframes.last().x)
    }

    @Test
    fun quitarElUltimoFotogramaDejaLaCapaFija() {
        var p = Timeline.toggleKeyframe(project(), "l", 2000)
        p = Timeline.setLayerPose(p, "l", 2000) { it.copy(y = 0.3f) }
        p = Timeline.toggleKeyframe(p, "l", 2000)
        val l = p.texts.single()
        assertTrue(l.keyframes.isEmpty())
        assertEquals(0.3f, l.y)
    }

    @Test
    fun dividirReparteLosFotogramas() {
        var p = Timeline.toggleKeyframe(project(), "l", 1000)
        p = Timeline.setLayerPose(p, "l", 5000) { it.copy(x = 1f) }
        p = Timeline.splitLayerAt(p, "l", 3000, "n")
        val (a, b) = p.texts
        assertEquals(3000, a.endMs)
        assertEquals(3000, b.startMs)
        assertEquals(LayerMath.poseAt(a, 2999).x, LayerMath.poseAt(b, 3000).x, 0.01f)
        assertEquals(1f, LayerMath.poseAt(b, 5000).x)
    }
}

class SubtitlesTest {
    @Test
    fun leeSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHola <i>mundo</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nDos\nlíneas\n"
        val cues = Subtitles.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(Cue(1000, 2500, "Hola mundo"), cues[0])
        assertEquals("Dos\nlíneas", cues[1].text)
        assertEquals(cues, Subtitles.parseSrt(Subtitles.toSrt(cues)))
    }

    @Test
    fun reparteLineasSinPasarseDelFinal() {
        val cues = Subtitles.fromLines("uno\n\nuna frase bastante más larga\ntres", 1000, 4000)
        assertEquals(3, cues.size)
        assertEquals(1000, cues.first().startMs)
        assertTrue(cues.last().endMs <= 4000)
        assertTrue(cues[1].endMs - cues[1].startMs > cues[0].endMs - cues[0].startMs)
    }
}

class Stage3Test {
    private fun video(id: String, ms: Long) = Clip(id = id, uri = "u$id", kind = MediaKind.VIDEO, sourceDurationMs = ms)

    @Test
    fun transicionDeEmpujeMueveLosDosClips() {
        val p = Project(id = "p", name = "p", clips = listOf(video("a", 10_000), video("b", 10_000).copy(transition = TransitionType.SLIDE_LEFT, transitionMs = 1_000)))
        val cuts = Timeline.transitionCuts(p)
        assertNull(Timeline.transitionMotion(cuts, 5_000))
        val out = Timeline.transitionMotion(cuts, 9_990)!!
        val inn = Timeline.transitionMotion(cuts, 10_000)!!
        assertTrue(out.dx < -1.8f)
        assertTrue(inn.dx > 1.8f)
        assertNull(Timeline.fadeAt(cuts, 9_990))
    }

    @Test
    fun efectosDeClipSonEstablesYAcotados() {
        for (e in ClipEffect.entries) for (t in 0L..3000L step 37) {
            val (m, light) = Timeline.clipEffectAt(e, t, 3000)
            assertTrue(m.scale in 0.9f..1.4f)
            assertTrue(kotlin.math.abs(m.dx) < 0.5f && kotlin.math.abs(m.dy) < 0.5f)
            assertTrue(light in 0f..1f)
            assertEquals(m, Timeline.clipEffectAt(e, t, 3000).first)
        }
        assertEquals(1f, Timeline.clipEffectAt(ClipEffect.ZOOM_IN_SLOW, 0, 3000).first.scale)
        assertEquals(1.25f, Timeline.clipEffectAt(ClipEffect.ZOOM_IN_SLOW, 3000, 3000).first.scale)
    }

    @Test
    fun extraerAudioSilenciaElClipYCreaLaPista() {
        val p = Project(id = "p", name = "p", clips = listOf(video("a", 4_000), video("b", 6_000).copy(trimStartMs = 1_000)))
        val (q, id) = Timeline.extractAudio(p, "b", "x")
        assertEquals("x", id)
        assertTrue(q.clips[1].muted)
        val a = q.audio.single()
        assertEquals(4_000, a.startMs)
        assertEquals(1_000, a.trimStartMs)
        assertEquals(5_000, a.durationMs)
        assertNull(Timeline.extractAudio(q, "b", "y").second)
    }

    @Test
    fun marcadoresSePonenYSeQuitan() {
        var p = Project(id = "p", name = "p")
        p = Timeline.toggleMarker(p, 2_000)
        p = Timeline.toggleMarker(p, 1_000)
        assertEquals(listOf(1_000L, 2_000L), p.markers)
        p = Timeline.toggleMarker(p, 2_100)
        assertEquals(listOf(1_000L), p.markers)
    }

    @Test
    fun posicionEnElOriginalTieneEnCuentaRecorteYVelocidad() {
        val p = Project(id = "p", name = "p", clips = listOf(video("a", 10_000).copy(trimStartMs = 2_000, speed = 2f)))
        assertEquals(4_000L, Timeline.sourcePositionAt(p, 1_000)!!.second)
    }
}
