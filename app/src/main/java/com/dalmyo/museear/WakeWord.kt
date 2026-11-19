package com.dalmyo.museear

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * openWakeWord on the phone: a line-by-line port of its streaming path (utils.AudioFeatures._streaming_features
 * and model.Model.predict) for exact 80 ms chunks, so a model scores the same here as on the panel.
 *
 * audio → melspectrogram.onnx (×/10+2) → last 76 mel frames → embedding_model.onnx (96 values)
 *       → last N embeddings → wake word model → score 0..1
 */
class WakeWord private constructor(
    private val env: OrtEnvironment,
    private val mel: OrtSession,
    private val emb: OrtSession,
    private val models: Map<String, OrtSession>,
) : AutoCloseable {

    val names: Set<String> get() = models.keys

    private val melIn = mel.inputNames.first()
    private val embIn = emb.inputNames.first()
    private val modelIn = models.mapValues { it.value.inputNames.first() }
    private val modelFrames = models.mapValues { ((it.value.inputInfo.values.first().info) as TensorInfo).shape[1].toInt() }

    private var tail = FloatArray(0)              // the 3 hops (480 samples) before the next chunk
    private val melBuf = ArrayList<FloatArray>()  // mel frames × 32
    private val featBuf = ArrayList<FloatArray>() // embeddings × 96
    private val predictions = HashMap<String, Int>()

    init { reset() }

    /** Same as Model.reset(): mel buffer of ones, embeddings of 4 s of random noise, first 5 scores forced to 0. */
    fun reset() {
        tail = FloatArray(0)
        melBuf.clear()
        repeat(MEL_WINDOW) { melBuf.add(FloatArray(MEL_BINS) { 1f }) }
        featBuf.clear()
        val spec = melspec(FloatArray(RATE * 4) { Random.nextInt(-1000, 1000).toFloat() })
        featBuf.addAll(embed((0 until spec.size step 8).filter { it + MEL_WINDOW <= spec.size }.map { spec.subList(it, it + MEL_WINDOW) }))
        predictions.clear()
    }

    /** One 80 ms chunk (1280 samples) in, one score per model out. */
    fun process(chunk: ShortArray): Map<String, Float> {
        require(chunk.size == CHUNK)
        val x = FloatArray(tail.size + CHUNK)
        tail.copyInto(x)
        for (i in chunk.indices) x[tail.size + i] = chunk[i].toFloat()
        tail = x.copyOfRange(maxOf(0, x.size - 480), x.size)

        melBuf.addAll(melspec(x))
        if (melBuf.size > MEL_MAX) melBuf.subList(0, melBuf.size - MEL_MAX).clear()
        featBuf.addAll(embed(listOf(melBuf.subList(melBuf.size - MEL_WINDOW, melBuf.size))))
        if (featBuf.size > FEAT_MAX) featBuf.subList(0, featBuf.size - FEAT_MAX).clear()

        return models.mapValues { (name, session) ->
            val n = modelFrames.getValue(name)
            val input = FloatArray(n * EMB_SIZE)
            for (i in 0 until n) featBuf[featBuf.size - n + i].copyInto(input, i * EMB_SIZE)
            val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, n.toLong(), EMB_SIZE.toLong())).use { t ->
                session.run(mapOf(modelIn.getValue(name) to t)).use { (it[0] as OnnxTensor).floatBuffer.get(0) }
            }
            val seen = predictions.getOrDefault(name, 0)
            predictions[name] = seen + 1
            if (seen < 5) 0f else score
        }
    }

    private fun melspec(x: FloatArray): List<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { t ->
            mel.run(mapOf(melIn to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                List(fb.remaining() / MEL_BINS) { f -> FloatArray(MEL_BINS) { j -> fb.get(f * MEL_BINS + j) / 10f + 2f } }
            }
        }

    private fun embed(windows: List<List<FloatArray>>): List<FloatArray> {
        if (windows.isEmpty()) return emptyList()
        val input = FloatArray(windows.size * MEL_WINDOW * MEL_BINS)
        windows.forEachIndexed { w, frames -> frames.forEachIndexed { f, row -> row.copyInto(input, (w * MEL_WINDOW + f) * MEL_BINS) } }
        val shape = longArrayOf(windows.size.toLong(), MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            emb.run(mapOf(embIn to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                List(windows.size) { w -> FloatArray(EMB_SIZE) { j -> fb.get(w * EMB_SIZE + j) } }
            }
        }
    }

    override fun close() {
        models.values.forEach { it.close() }
        emb.close()
        mel.close()
    }

    companion object {
        const val RATE = 16000
        const val CHUNK = 1280
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val MEL_MAX = 10 * 97
        private const val EMB_SIZE = 96
        private const val FEAT_MAX = 120

        /** Wake word files in assets/, named like openWakeWord names them: the file name without .onnx. */
        val WAKE_FILES = listOf("hi_clio_v2.onnx", "hey_jarvis_v0.1.onnx")   // hi_clio_v2: 중앙관리, md5 59dff68fe270…

        fun fromAssets(ctx: Context, files: List<String> = WAKE_FILES): WakeWord {
            val env = OrtEnvironment.getEnvironment()
            // XNNPACK was tried (2026-10-14): same scores, no speed-up on the Note 9, so plain CPU.
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            fun load(f: String) = env.createSession(ctx.assets.open(f).use { it.readBytes() }, opts)
            // A wake file that is not in assets is skipped, so a build can leave a model out:
            // the public build ships hey_jarvis only, while house builds keep our own model.
            val present = files.filter { f -> try { ctx.assets.open(f).close(); true } catch (e: Exception) { false } }
            return WakeWord(env, load("melspectrogram.onnx"), load("embedding_model.onnx"), present.associate { it.removeSuffix(".onnx") to load(it) })
        }
    }
}
