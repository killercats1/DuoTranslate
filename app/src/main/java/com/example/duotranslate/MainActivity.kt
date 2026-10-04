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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.*
import java.util.Locale

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

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        for (lang in accentChoices.keys) getPreferences(MODE_PRIVATE).getString("accent_$lang", null)?.let { accents[lang] = it }
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

        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    fun Screen() {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Duo Translate", style = MaterialTheme.typography.headlineSmall)
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
                    OutlinedButton({ host() }) { Text("Host") }
                    OutlinedButton({ join() }) { Text("Join") }
                }
                Button({ micOn = !micOn; if (micOn) listen() else recognizer?.destroy() }, Modifier.fillMaxWidth()) {
                    Text(if (micOn) "Mic ON (tap to stop)" else "Start talking")
                }
            }
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
        status = if (one) "Tap the language being spoken" else if (peer != null) "Connected" else "Not connected"
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

    private fun host() {
        status = "Starting host…"
        client.startAdvertising("phone", serviceId, connCb, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { status = "Waiting for the other phone…" }
            .addOnFailureListener { status = "Host failed: ${it.message}" }
    }

    private fun join() {
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
        translators.values.forEach { it.close() }
    }
}
