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
    private val log = mutableStateListOf<String>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        tts = TextToSpeech(this) {}
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { resume() }
            override fun onDone(id: String?) { resume() }
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
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (myLang == "en") Button({ myLang = "en" }) { Text("I speak English") } else OutlinedButton({ myLang = "en" }) { Text("I speak English") }
                if (myLang == "es") Button({ myLang = "es" }) { Text("Hablo español") } else OutlinedButton({ myLang = "es" }) { Text("Hablo español") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ host() }) { Text("Host") }
                OutlinedButton({ join() }) { Text("Join") }
            }
            Button({ micOn = !micOn; if (micOn) listen() else recognizer?.destroy() }, Modifier.fillMaxWidth()) {
                Text(if (micOn) "Mic ON (tap to stop)" else "Start talking")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(log.reversed()) { Text(it) } }
        }
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
        tts?.language = Locale(myLang)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u")
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
            .addOnSuccessListener { t.translate(text).addOnSuccessListener(done) }
            .addOnFailureListener { status = "Translation model needs internet once to download" }
    }

    private fun send(text: String) {
        log.add("You: $text")
        peer?.let { client.sendPayload(it, Payload.fromBytes("$myLang|$text".toByteArray(Charsets.UTF_8))) }
    }

    private fun listen() {
        if (!micOn || speaking) return
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { send(it) }
                listen()
            }
            override fun onError(e: Int) {
                if (e != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    Handler(Looper.getMainLooper()).postDelayed({ listen() }, 400)
            }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (myLang == "es") "es-ES" else "en-US")
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
