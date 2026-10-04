package com.example.duotranslate

import android.Manifest
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val serviceId = "com.example.duotranslate"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private lateinit var client: ConnectionsClient
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var peer: String? = null
    private var speaking = false
    private val translators = HashMap<String, Translator>()

    private var status by mutableStateOf("Not connected")
    private var myLang by mutableStateOf("en")
    private var micOn by mutableStateOf(false)
    private var solo by mutableStateOf(false)
    private var soloLang by mutableStateOf<String?>(null)
    private val accents = mutableStateMapOf<String, String>()
    private val accentChoices = mapOf("en" to listOf("US", "GB", "AU", "IN"), "es" to listOf("US", "MX", "ES", "AR", "CO"))
    private val log = mutableStateListOf<String>()
    private var dark by mutableStateOf<Boolean?>(null)
    private var room by mutableStateOf("")
    @Volatile private var roomTopic: String? = null
    @Volatile private var roomConn: HttpURLConnection? = null
    private val me = java.util.UUID.randomUUID().toString().take(8)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        for (lang in accentChoices.keys) getPreferences(MODE_PRIVATE).getString("accent_$lang", null)?.let { accents[lang] = it }
        if (getPreferences(MODE_PRIVATE).contains("dark")) dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false)
        tts = TextToSpeech(this) {}
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { resume() }
            override fun onDone(id: String?) { resume() }
        })
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permLauncher.launch(perms.toTypedArray())

        setContent {
            MaterialTheme(if (dark ?: isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    @Composable
    fun Screen() {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Duo Translate", style = MaterialTheme.typography.headlineSmall)
                val isDark = dark ?: isSystemInDarkTheme()
                TextButton({ dark = !isDark; getPreferences(MODE_PRIVATE).edit().putBoolean("dark", !isDark).apply() }) {
                    Text(if (isDark) "Light mode" else "Dark mode")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(!solo, "Two phones") { setMode(false) }
                Choice(solo, "One phone") { setMode(true) }
            }
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ nextAccent("en") }) { Text("English: ${locale("en").displayCountry}") }
                TextButton({ nextAccent("es") }) { Text("Español: ${locale("es").displayCountry}") }
            }
            if (solo) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ soloTap("en") }, Modifier.weight(1f)) { Text(if (soloLang == "en") "Listening…" else "Speak English") }
                    Button({ soloTap("es") }, Modifier.weight(1f)) { Text(if (soloLang == "es") "Escuchando…" else "Hablar español") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(myLang == "en", "I speak English") { myLang = "en" }
                    Choice(myLang == "es", "Hablo español") { myLang = "es" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ host() }) { Text("Host nearby") }
                    OutlinedButton({ join() }) { Text("Join nearby") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(room, { v -> room = v.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8) }, Modifier.weight(1f), label = { Text("Room code") }, singleLine = true)
                    OutlinedButton({ joinRoom() }) { Text("Join room") }
                    OutlinedButton({ room = newCode(); joinRoom() }) { Text("New") }
                }
                Button({ micOn = !micOn; if (micOn) listen() else recognizer?.destroy() }, Modifier.fillMaxWidth()) {
                    Text(if (micOn) "Mic ON (tap to stop)" else "Start talking")
                }
            }
            TextButton({ log.clear() }, Modifier.align(Alignment.End)) { Text("Clear chat") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(log.reversed()) { Text(it) } }
        }
    }

    @Composable
    fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
        if (selected) Button(onClick) { Text(label) } else OutlinedButton(onClick) { Text(label) }
    }

    private fun setMode(one: Boolean) {
        if (solo == one) return
        solo = one
        micOn = false
        soloLang = null
        speaking = false
        recognizer?.destroy()
        tts?.stop()
        status = if (one) "Tap the language being spoken" else if (peer != null) "Connected" else if (roomTopic != null) "In room $room" else "Not connected"
    }

    private fun soloTap(lang: String) {
        tts?.stop()
        if (soloLang == lang) {
            recognizer?.destroy()
            soloLang = null
            return
        }
        soloLang = lang
        val other = if (lang == "en") "es" else "en"
        recognize(lang, { text ->
            soloLang = null
            log.add("${lang.uppercase()}: $text")
            translate(lang, other, text) {
                log.add("${other.uppercase()}: $it")
                speak(it, other)
            }
        }, { soloLang = null })
    }

    private fun newCode() = (1..6).map { "ABCDEFGHJKMNPQRSTUVWXYZ23456789".random() }.joinToString("")

    private fun joinRoom() {
        val code = room
        if (code.length < 4) {
            status = "Type a room code (4+ letters) or tap New"
            return
        }
        leaveRoom()
        client.stopAllEndpoints()
        peer = null
        val topic = "duotranslate-$code"
        roomTopic = topic
        status = "Joining room $code"
        Thread {
            while (roomTopic == topic) {
                try {
                    val c = URL("https://ntfy.sh/$topic/json").openConnection() as HttpURLConnection
                    roomConn = c
                    c.connectTimeout = 8000
                    c.readTimeout = 120000
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val json = JSONObject(line)
                            when (json.optString("event")) {
                                "open" -> {
                                    runOnUiThread { status = "In room $code, waiting for the other phone" }
                                    runCatching { publish(topic, "$me|!|") }
                                }
                                "message" -> roomMessage(code, topic, json.optString("message"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (roomTopic == topic) runOnUiThread { status = "Room connection lost, retrying" }
                }
                if (roomTopic == topic) Thread.sleep(5000)
            }
        }.start()
    }

    private fun roomMessage(code: String, topic: String, msg: String) {
        val p = msg.split("|", limit = 3)
        if (p.size != 3 || p[0] == me) return
        if (p[1].startsWith("!")) {
            runOnUiThread { status = "In room $code, other phone connected" }
            if (p[1] == "!") runCatching { publish(topic, "$me|!!|") }
        } else {
            runOnUiThread { incoming(p[1], p[2]) }
        }
    }

    private fun publish(topic: String, msg: String) {
        val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Cache", "no")
            c.outputStream.use { it.write(msg.toByteArray()) }
            if (c.responseCode == 429) runOnUiThread { status = "Sending too fast for the free relay, wait a few seconds" }
        } finally {
            c.disconnect()
        }
    }

    private fun leaveRoom() {
        roomTopic = null
        val c = roomConn
        roomConn = null
        if (c != null) Thread { c.disconnect() }.start()
    }

    private fun host() {
        leaveRoom()
        status = "Starting host…"
        client.startAdvertising("phone", serviceId, connCb, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { status = "Waiting for the other phone…" }
            .addOnFailureListener { status = "Host failed: ${it.message}" }
    }

    private fun join() {
        leaveRoom()
        status = "Starting search…"
        client.startDiscovery(serviceId, discCb, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { status = "Searching…" }
            .addOnFailureListener { status = "Search failed: ${it.message}" }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            status = "Found a phone, connecting…"
            client.requestConnection("phone", id, connCb)
                .addOnFailureListener { status = "Connect failed: ${it.message}" }
        }
        override fun onEndpointLost(id: String) {}
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCb)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                peer = id
                status = "Connected"
                client.stopAdvertising()
                client.stopDiscovery()
            } else {
                status = "Connection failed: ${r.status.statusMessage ?: r.status.statusCode}"
            }
        }
        override fun onDisconnected(id: String) {
            peer = null
            status = "Disconnected"
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val bytes = p.asBytes() ?: return
            val msg = String(bytes, Charsets.UTF_8)
            val parts = msg.split("|", limit = 2)
            if (parts.size == 2) runOnUiThread { incoming(parts[0], parts[1]) }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private fun incoming(lang: String, text: String) {
        if (lang == myLang) show(text) else translate(lang, myLang, text) { show(it) }
    }

    private fun show(text: String) {
        log.add("Them: $text")
        speaking = true
        recognizer?.destroy()
        speak(text, myLang)
    }

    private fun speak(text: String, lang: String) {
        val r = tts?.setLanguage(locale(lang))
        if (r == null || r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "No ${if (lang == "es") "Spanish" else "English"} voice installed on this phone (Settings > Text-to-speech)"
            resume()
            return
        }
        if (tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u") != TextToSpeech.SUCCESS) resume()
    }

    private fun nextAccent(lang: String) {
        val list = accentChoices.getValue(lang)
        val next = list[(list.indexOf(locale(lang).country) + 1) % list.size]
        accents[lang] = next
        getPreferences(MODE_PRIVATE).edit().putString("accent_$lang", next).apply()
    }

    private fun locale(lang: String): Locale {
        accents[lang]?.let { return Locale(lang, it) }
        val sys = resources.configuration.locales
        for (i in 0 until sys.size()) {
            if (sys[i].language == lang && sys[i].country.isNotEmpty()) return Locale(lang, sys[i].country)
        }
        return Locale(lang, "US")
    }

    private fun resume() {
        speaking = false
        Handler(Looper.getMainLooper()).post { listen() }
    }

    private fun translate(src: String, tgt: String, text: String, done: (String) -> Unit) {
        Thread {
            val online = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash").firstNotNullOfOrNull { runCatching { gemini(it, src, tgt, text) }.getOrNull() }
                ?: runCatching { myMemory(src, tgt, text) }.getOrNull()
            runOnUiThread { if (online != null) done(online) else translateOffline(src, tgt, text, done) }
        }.start()
    }

    private fun gemini(model: String, src: String, tgt: String, text: String): String? {
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) return null
        val names = mapOf("en" to "English", "es" to "Spanish")
        val prompt = "You translate a live spoken conversation from ${names[src]} to ${names[tgt]}. " +
            "The text comes from speech recognition, so it may have no punctuation and may run several sentences together. " +
            "Reply with only the natural ${names[tgt]} translation and nothing else."
        fun parts(s: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", s)))
        val body = JSONObject().put("system_instruction", parts(prompt)).put("contents", JSONArray().put(parts(text)))
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            return json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text").trim().ifEmpty { null }
        } finally {
            c.disconnect()
        }
    }

    private fun myMemory(src: String, tgt: String, text: String): String? {
        val c = URL("https://api.mymemory.translated.net/get?q=${URLEncoder.encode(text, "UTF-8")}&langpair=$src%7C$tgt").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            if (json.optInt("responseStatus") != 200) return null
            val out = json.getJSONObject("responseData").getString("translatedText")
            return android.text.Html.fromHtml(out, 0).toString().trim().ifEmpty { null }
        } finally {
            c.disconnect()
        }
    }

    private fun translateOffline(src: String, tgt: String, text: String, done: (String) -> Unit) {
        val t = translators.getOrPut("$src$tgt") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        }
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                val sentences = text.split(Regex("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
                Tasks.whenAllSuccess<String>(sentences.map { t.translate(it) })
                    .addOnSuccessListener { done(it.joinToString(" ")) }
            }
            .addOnFailureListener { status = "Translation model needs internet once to download" }
    }

    private fun send(text: String) {
        log.add("You: $text")
        peer?.let { client.sendPayload(it, Payload.fromBytes("$myLang|$text".toByteArray(Charsets.UTF_8))) }
        roomTopic?.let { t -> Thread { runCatching { publish(t, "$me|$myLang|$text") } }.start() }
    }

    private fun listen() {
        if (!micOn || speaking) return
        recognize(myLang, { send(it); listen() }, { e ->
            if (e != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                Handler(Looper.getMainLooper()).postDelayed({ listen() }, 400)
        })
    }

    private fun recognize(lang: String, onText: (String) -> Unit, onFail: (Int) -> Unit) {
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text != null) onText(text) else onFail(SpeechRecognizer.ERROR_NO_MATCH)
            }
            override fun onError(e: Int) { onFail(e) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale(lang).toLanguageTag())
            if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
        client.stopAllEndpoints()
        leaveRoom()
        translators.values.forEach { it.close() }
    }
}
