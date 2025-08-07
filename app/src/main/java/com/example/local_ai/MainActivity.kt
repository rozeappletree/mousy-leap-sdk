package com.example.local_ai

import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.LeapModelLoadingException
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.gson.LeapGson
import ai.liquid.leap.gson.registerLeapAdapters
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leap.message.ChatMessageContent
import ai.liquid.leap.message.MessageResponse
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.example.local_ai.ui.theme.LocalaiTheme
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234

    private companion object {
        const val MODEL_SLUG = "lfm2-1.2b" // Or any other suitable model
        const val QUANTIZATION_SLUG = "lfm2-1.2b-20250710-8da4w" // Or the corresponding quantization
        const val SYSTEM_PROMPT = "Generate a short, insightful, and unique quote."
        const val QUOTE_REFRESH_INTERVAL_MS = 5000L
    }

    private val modelRunner = MutableLiveData<ModelRunner?>(null)
    private var conversation: Conversation? = null
    private val currentQuote = MutableLiveData("Loading quote...")
    private val modelLoadingStatus = MutableLiveData("Initializing model loading...")
    private var quoteGenerationJob: Job? = null
    private val gson = GsonBuilder().registerLeapAdapters().create()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        loadModelAndGenerateQuotes()

        setContent {
            LocalaiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val quoteText by currentQuote.observeAsState()
                    val loadingStatus by modelLoadingStatus.observeAsState()
                    val isModelLoaded = modelRunner.observeAsState().value != null

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isModelLoaded) {
                            Text(text = quoteText ?: "No quote yet")
                        } else {
                            Text(text = loadingStatus ?: "Loading...")
                        }

                        // Button for overlay permission (optional, can be removed if not needed)
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                            } else {
                                startFloatingIconService()
                            }
                        }) {
                            Text("Show Floating Icon")
                        }
                    }
                }
            }
        }
    }

    private fun loadModelAndGenerateQuotes() {
        lifecycleScope.launch {
            loadModel(
                onError = { error ->
                    modelLoadingStatus.value = "Error loading model: ${error.message}"
                    Log.e("MainActivity", "Model loading error", error)
                },
                onStatusChange = { status ->
                    modelLoadingStatus.value = status
                }
            )
            // Once model is loaded, modelRunner will be updated, triggering quote generation
        }

        modelRunner.observe(this) { runner ->
            if (runner != null) {
                modelLoadingStatus.value = "Model loaded. Generating quote..."
                startPeriodicQuoteGeneration()
            }
        }
    }

    private fun startPeriodicQuoteGeneration() {
        quoteGenerationJob?.cancel() // Cancel any existing job
        quoteGenerationJob = lifecycleScope.launch {
            while (true) {
                generateQuote()
                delay(QUOTE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadModel(onError: (Throwable) -> Unit, onStatusChange: (String) -> Unit) {
        try {
            val modelToUse = LeapDownloadableModel.resolve(MODEL_SLUG, QUANTIZATION_SLUG)
            if (modelToUse == null) {
                throw RuntimeException("Model $QUANTIZATION_SLUG not found in Leap Model Library!")
            }
            val modelDownloader = LeapModelDownloader(this@MainActivity)
            onStatusChange("Requesting model download...")
            modelDownloader.requestDownloadModel(modelToUse)

            var isModelAvailable = false
            while (!isModelAvailable) {
                val status = modelDownloader.queryStatus(modelToUse)
                when (status.type) {
                    LeapModelDownloader.ModelDownloadStatusType.NOT_ON_LOCAL -> {
                        onStatusChange("Model not downloaded. Waiting for download...")
                    }
                    LeapModelDownloader.ModelDownloadStatusType.DOWNLOAD_IN_PROGRESS -> {
                        onStatusChange(
                            "Downloading model: ${String.format("%.2f", status.progress * 100.0)}%"
                        )
                    }
                    LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED -> {
                        onStatusChange("Model downloaded.")
                        isModelAvailable = true
                    }
                }
                delay(500) // Check status every 500ms
            }

            val modelFile = modelDownloader.getModelFile(modelToUse)
            onStatusChange("Loading model from: ${modelFile.path}")
            modelRunner.postValue(LeapClient.loadModel(modelFile.path)) // Use postValue for background thread
        } catch (e: Exception) { // Catch generic exception for broader error handling
            onError(e)
        }
    }

    private fun generateQuote() {
        val runner = modelRunner.value ?: return // Ensure model is loaded

        // Initialize conversation if it's null or use existing
        if (conversation == null) {
            conversation = runner.createConversation(SYSTEM_PROMPT)
        }

        quoteGenerationJob = lifecycleScope.launch {
            currentQuote.value = "Generating new quote..." // Show generating state
            val responseBuffer = StringBuilder()
            try {
                conversation!!.generateResponse("Tell me a quote.") // Simple prompt for a quote
                    .onEach { response ->
                        when (response) {
                            is MessageResponse.Chunk -> responseBuffer.append(response.text)
                            else -> {} // Handle other response types if necessary
                        }
                    }
                    .onCompletion { throwable ->
                        if (throwable == null) {
                            currentQuote.value = responseBuffer.toString().trim()
                        } else {
                            currentQuote.value = "Error generating quote: ${throwable.message}"
                            Log.e("MainActivity", "Quote generation error", throwable)
                        }
                    }
                    .catch { e ->
                        currentQuote.value = "Exception during quote generation: ${e.message}"
                        Log.e("MainActivity", "Quote generation exception", e)
                    }
                    .collect()
            } catch (e: Exception) {
                 currentQuote.value = "Failed to start quote generation: ${e.message}"
                 Log.e("MainActivity", "Failed to start quote generation", e)
            }
        }
    }


    private fun startFloatingIconService() {
        startService(Intent(this, FloatingIconService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingIconService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        quoteGenerationJob?.cancel()
        lifecycleScope.launch {
            modelRunner.value?.unload()
        }
    }
}

// Keeping Greeting and Preview for now, can be removed if not needed.
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LocalaiTheme {
        Greeting("Android")
    }
}
