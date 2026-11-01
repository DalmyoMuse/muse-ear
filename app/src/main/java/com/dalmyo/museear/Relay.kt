package com.dalmyo.museear

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * voice-relay on the mini PC (voice_relay.py), the same two calls the panel makes:
 *   POST /stt (16 kHz mono WAV) → {"ok", "text", "why"}
 *   POST /ask ({"text"})        → {"ok", "text", "reply", "why"}
 * Any network error comes back as {"ok": false, "why": "relay"}, like the panel's post().
 */
class Relay(private val base: String) {

    fun stt(pcm: ByteArray): JSONObject = post("/stt", wav(pcm), "audio/wav", 30_000)

    fun ask(text: String): JSONObject =
        post("/ask", JSONObject().put("text", text).toString().toByteArray(), "application/json", 75_000)

    /**
     * The Muse, through the Termux bridge (termux/ear_bridge.py in D:\dev\muse_bridge):
     *   POST 127.0.0.1:47821/ask ({"text"}) → {"ok", "why"}
     * The bridge only delivers the text to the gadget's 뮤즈귀 side chat; the Muse answers out loud
     * itself (termux-tts-speak), so nothing comes back here but the delivery result.
     */
    fun askMuse(text: String): JSONObject = Relay(MUSE_BRIDGE).post(
        "/ask", JSONObject().put("text", text).toString().toByteArray(), "application/json", 20_000)

    private fun post(path: String, body: ByteArray, type: String, timeoutMs: Int): JSONObject {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 5_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (code == 403) JSONObject().put("ok", false).put("why", "forbidden")
            else JSONObject(text)
        } catch (e: IOException) {
            Ear.log("$path 실패: ${e.javaClass.simpleName}")
            JSONObject().put("ok", false).put("why", "relay")
        } catch (e: org.json.JSONException) {
            JSONObject().put("ok", false).put("why", "relay")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val MUSE_BRIDGE = "http://127.0.0.1:47821"

        fun wav(pcm: ByteArray, rate: Int = WakeWord.RATE): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size)
            return ByteArrayOutputStream(44 + pcm.size).apply { write(h.array()); write(pcm) }.toByteArray()
        }
    }
}
