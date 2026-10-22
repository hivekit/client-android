package io.hivekit.sample

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.*
import io.hivekit.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainActivity : ComponentActivity() {
    private val model: FleetViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val endpoint = EditText(this).apply { hint = "WebSocket URL"; setText("ws://10.0.2.2:8090/ws") }
        val token = EditText(this).apply {
            hint = "Hivekit access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val realm = EditText(this).apply { hint = "Existing realm ID" }
        val connect = Button(this).apply {
            text = "Connect and observe"
            setOnClickListener {
                model.connect(endpoint.text.toString(), token.text.toString(), realm.text.toString())
                token.text.clear()
            }
        }
        val output = TextView(this)
        listOf(endpoint, token, realm, connect, output).forEach(panel::addView)
        setContentView(ScrollView(this).apply { addView(panel) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.output.collect { output.text = it }
            }
        }
    }
}
class FleetViewModel : ViewModel() {
    private var client: HivekitClient? = null
    private var connection: Job? = null
    private val mutableOutput = MutableStateFlow("Enter a token and an existing realm to observe objects.")
    val output: StateFlow<String> = mutableOutput.asStateFlow()
    fun connect(url: String, token: String, realmId: String) {
        connection?.cancel()
        client?.close()
        connection = viewModelScope.launch {
            val active = HivekitClient(HivekitConfig(webSocketUrl = url))
            client = active
            try {
                active.connect()
                active.authenticate(token)
                val realm = active.realms.get(realmId)
                realm.objects.observe().collect { state ->
                    mutableOutput.value = when {
                        state.error != null -> state.error!!.message ?: "Subscription error"
                        !state.initialized -> "Loading objects..."
                        else -> (if (state.stale) "Reconnecting; showing previous data\n" else "") +
                            state.data.orEmpty().values.joinToString("\n") { obj ->
                                (obj.label ?: obj.id) + ": " + obj.data.toString()
                            }.ifBlank { "No objects in this realm." }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableOutput.value = e.message ?: "Connection failed" }
        }
    }
    override fun onCleared() { client?.close() }
}
