package com.example.local_ai

import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.gson.registerLeapAdapters
import ai.liquid.leap.message.MessageResponse
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FloatingIconService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingIconText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private val clickThreshold = 10

    private lateinit var binView: ImageView
    private lateinit var binParams: WindowManager.LayoutParams
    private var screenHeight: Int = 0
    private var screenWidth: Int = 0

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var modelRunner: ModelRunner? = null
    private var conversation: Conversation? = null
    private var quoteGenerationJob: Job? = null
    private val gson = GsonBuilder().registerLeapAdapters().create() // LeapGson.get() could also be used if preferred

    companion object {
        const val MODEL_SLUG = "lfm2-1.2b"
        const val QUANTIZATION_SLUG = "lfm2-1.2b-20250710-8da4w"
        const val SYSTEM_PROMPT = "Generate a short, insightful, and unique quote."
        const val QUOTE_REFRESH_INTERVAL_MS = 5000L
        const val NOTIFICATION_CHANNEL_ID = "FloatingIconServiceChannel"
        const val NOTIFICATION_ID = 1
        const val TAG = "FloatingIconService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        setupWindowManagerAndFloatingView()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing service..."))

        Log.d(TAG, "Service created. Starting model loading and quote generation.")
        serviceScope.launch {
            loadModelAndStartGeneration(
                onStatusChange = { status ->
                    Log.i(TAG, "Model loading status: $status")
                    updateNotification("Model: $status")
                },
                onError = { error ->
                    Log.e(TAG, "Model loading failed", error)
                    updateNotification("Error: Model load failed")
                    updateText("Error loading model.") // Update UI
                }
            )
        }
    }

    private fun setupWindowManagerAndFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_icon_layout, null)
        floatingIconText = floatingView.findViewById(R.id.floating_icon_text)

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        binView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
        }
        binParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 50
            y = 50
        }

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels

        updateText("Loading...")

        floatingView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                showBinIcon()
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX // Keep X coordinate constant
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(floatingView, params)
                if (isViewOverlapping(floatingView, binView)) {
                    binView.setColorFilter(getColor(R.color.red)) // Ensure you have this color
                } else {
                    binView.clearColorFilter()
                }
            }
            MotionEvent.ACTION_UP -> {
                hideBinIcon()
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                if (kotlin.math.abs(deltaX) < clickThreshold && kotlin.math.abs(deltaY) < clickThreshold) {
                    Toast.makeText(this@FloatingIconService, "Quote Service Running", Toast.LENGTH_SHORT).show()
                } else {
                    if (isViewOverlapping(floatingView, binView)) {
                        stopSelf()
                    }
                }
            }
        }
    }


    private suspend fun loadModelAndStartGeneration(onStatusChange: (String) -> Unit, onError: (Throwable) -> Unit) {
        try {
            onStatusChange("Resolving model...")
            val modelToUse = LeapDownloadableModel.resolve(MODEL_SLUG, QUANTIZATION_SLUG)
            if (modelToUse == null) {
                throw RuntimeException("Model $QUANTIZATION_SLUG not found in Leap Model Library!")
            }

            val modelDownloader = LeapModelDownloader(applicationContext)
            onStatusChange("Checking model download status...")

            if (modelDownloader.queryStatus(modelToUse).type != LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED) {
                onStatusChange("Requesting model download...")
                modelDownloader.requestDownloadModel(modelToUse)
                var isModelAvailable = false
                while (!isModelAvailable) {
                    val status = modelDownloader.queryStatus(modelToUse)
                    when (status.type) {
                        LeapModelDownloader.ModelDownloadStatusType.NOT_ON_LOCAL -> {
                            onStatusChange("Model not downloaded. Waiting...")
                        }
                        LeapModelDownloader.ModelDownloadStatusType.DOWNLOAD_IN_PROGRESS -> {
                            onStatusChange(
                                "Downloading: ${String.format("%.2f", status.progress * 100.0)}%"
                            )
                        }
                        LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED -> {
                            onStatusChange("Model downloaded.")
                            isModelAvailable = true
                        }
                    }
                    delay(1000) // Check status every second
                }
            } else {
                onStatusChange("Model already downloaded.")
            }

            val modelFile = modelDownloader.getModelFile(modelToUse)
            onStatusChange("Loading model from: ${modelFile.path}")
            this.modelRunner = LeapClient.loadModel(modelFile.path) // this. refers to service instance
            onStatusChange("Model loaded. Starting quote generation.")
            updateText("Generating quote...")
            startPeriodicQuoteGeneration()
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadModelAndStartGeneration", e)
            onError(e)
        }
    }

    private fun startPeriodicQuoteGeneration() {
        quoteGenerationJob?.cancel()
        quoteGenerationJob = serviceScope.launch {
            while (true) {
                generateQuote()
                delay(QUOTE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun generateQuote() {
        val runner = this.modelRunner ?: run {
            Log.w(TAG, "ModelRunner not available for quote generation.")
            updateText("Model not ready.")
            return
        }

        if (this.conversation == null) {
            this.conversation = runner.createConversation(SYSTEM_PROMPT)
        }

        val responseBuffer = StringBuilder()
        try {
            Log.d(TAG, "Generating new quote...")
            this.conversation!!.generateResponse("Tell me a quote.")
                .onEach { response ->
                    if (response is MessageResponse.Chunk) {
                        responseBuffer.append(response.text)
                    }
                }
                .onCompletion { throwable ->
                    if (throwable == null) {
                        val quote = responseBuffer.toString().trim()
                        Log.i(TAG, "Generated quote: $quote")
                        updateText(quote)
                        updateNotification("Last quote: ${quote.take(20)}...")
                    } else {
                        Log.e(TAG, "Quote generation error (onCompletion)", throwable)
                        updateText("Error generating quote.")
                    }
                }
                .catch { e ->
                    Log.e(TAG, "Quote generation exception (catch)", e)
                    updateText("Error: Quote generation failed.")
                }
                .collect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start quote generation flow", e)
            updateText("Error: Could not start generation.")
        }
    }

    private fun updateText(text: String) {
        // Ensure UI updates are on the main thread
        Handler(Looper.getMainLooper()).post {
            if (::floatingIconText.isInitialized) {
                if (text.isNotEmpty()) {
                    floatingIconText.text = text
                    floatingIconText.visibility = View.VISIBLE
                } else {
                    floatingIconText.visibility = View.GONE
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Floating Icon Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Opens MainActivity on tap
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Quote Service Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand received.")
        // If service is killed and restarted, this ensures model loading attempts again if needed.
        // The main logic is already triggered in onCreate.
        // We return START_STICKY to ensure the service restarts if killed by the system.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        quoteGenerationJob?.cancel()
        serviceScope.cancel() // Cancels all coroutines launched in this scope

        // Unload model - can be time-consuming, run blocking or in a new temporary scope if needed
        runBlocking { // Using runBlocking for simplicity here, consider a separate scope for longer ops
            try {
                modelRunner?.unload()
                Log.i(TAG, "Model unloaded.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model", e)
            }
        }

        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
        if (::binView.isInitialized && binView.parent != null) {
            windowManager.removeView(binView)
        }
        stopForeground(true)
        Log.d(TAG, "Service destroyed.")
    }

    // Bin icon helper methods (copied from original, ensure they work with current context)
    private fun showBinIcon() {
        if (!::binView.isInitialized) return
        Handler(Looper.getMainLooper()).post {
            if (binView.parent == null) {
                 try { windowManager.addView(binView, binParams) } catch (e: Exception) { Log.e(TAG, "Error adding binView", e)}
            }
            binView.visibility = View.VISIBLE
        }
    }

    private fun hideBinIcon() {
         if (!::binView.isInitialized) return
        Handler(Looper.getMainLooper()).post {
            binView.visibility = View.GONE
        }
    }

   private fun isViewOverlapping(view1: View, view2: View): Boolean {
        if (!::windowManager.isInitialized || view1.parent == null || view2.parent == null && view2.height == 0 && view2.width == 0) {
             // If views are not attached, they cannot overlap in a meaningful way for UI.
             // Or if windowManager isn't ready yet (shouldn't happen if called from touch listener on attached view)
            return false
        }
        val rect1 = Rect()
        view1.getHitRect(rect1) // rect1 is in view1's coordinates

        // Get absolute screen coordinates for view1's rect
        val location1 = IntArray(2)
        view1.getLocationOnScreen(location1)
        rect1.offsetTo(location1[0], location1[1])

        // For binView, its params.x and params.y are relative to its gravity (BOTTOM|START).
        // We need its absolute screen coordinates.
        // Since binParams.gravity is BOTTOM|START, its (0,0) is bottom-left of the screen.
        // y is offset from bottom, x is offset from left.
        val binScreenX = binParams.x
        // binView.height might be 0 if not measured yet. Let's assume it's measured or use a fixed size.
        val binHeight = if (view2.height == 0) 100 else view2.height // Estimate or get from layout
        val binWidth = if (view2.width == 0) 100 else view2.width

        val binScreenY = screenHeight - binHeight - binParams.y // y from top

        val rect2Screen = Rect(
            binScreenX,
            binScreenY,
            binScreenX + binWidth,
            binScreenY + binHeight
        )
        return Rect.intersects(rect1, rect2Screen)
    }
}
