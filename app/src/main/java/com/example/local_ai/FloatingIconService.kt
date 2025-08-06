package com.example.local_ai

import android.annotation.SuppressLint
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class FloatingIconService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingIconText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private val clickThreshold = 10 // Threshold to differentiate click from drag

    private lateinit var binView: ImageView
    private lateinit var binParams: WindowManager.LayoutParams
    private var screenHeight: Int = 0
    private var screenWidth: Int = 0

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
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
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        // Initialize bin icon
        binView = ImageView(this)
        binView.setImageResource(android.R.drawable.ic_menu_delete) // Using a system icon for simplicity
        binParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        binParams.gravity = Gravity.BOTTOM or Gravity.START
        binParams.x = 50 // Adjust as needed
        binParams.y = 50 // Adjust as needed

        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels


        // Show dummy text and hide after 3 seconds
        updateText("Dummy Text")
        Handler(Looper.getMainLooper()).postDelayed({
            updateText("")
        }, 3000)

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    showBinIcon()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - initialTouchY
                    params.y = initialY + deltaY.toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    // Check for overlap with bin icon
                    if (isViewOverlapping(floatingView, binView)) {
                        binView.setColorFilter(getColor(R.color.red)) // Highlight bin when icon is over it
                    } else {
                        binView.clearColorFilter()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideBinIcon()
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (Math.abs(deltaX) < clickThreshold && Math.abs(deltaY) < clickThreshold) {
                        // Click detected
                        Toast.makeText(this@FloatingIconService, "Icon Clicked!", Toast.LENGTH_SHORT).show()
                        // Handle click action here
                    } else {
                        // Drag detected
                        if (isViewOverlapping(floatingView, binView)) {
                            stopSelf() // Remove floating icon and text
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showBinIcon() {
        if (binView.parent == null) {
            windowManager.addView(binView, binParams)
        }
        binView.visibility = View.VISIBLE
    }

    private fun hideBinIcon() {
        binView.visibility = View.GONE
    }

    private fun isViewOverlapping(view1: View, view2: View): Boolean {
        val rect1 = Rect()
        view1.getHitRect(rect1)

        val rect2 = Rect()
        view2.getHitRect(rect2)

        // Get absolute coordinates for view1
        val location1 = IntArray(2)
        view1.getLocationOnScreen(location1)
        rect1.offsetTo(location1[0], location1[1])


        // Get absolute coordinates for view2 (binView is already in absolute coords due to WindowManager)
        // For binView, its params.x and params.y are relative to its gravity.
        // We need to convert its gravity-based (bottom-left) coordinates to screen coordinates.
        val binScreenX = binParams.x
        val binScreenY = screenHeight - binView.height - binParams.y // screenHeight - viewHeight - marginBottom

        val rect2Screen = Rect(binScreenX, binScreenY,
            binScreenX + view2.width, binScreenY + view2.height)

        return Rect.intersects(rect1, rect2Screen)
    }


    private fun updateText(text: String) {
        if (text.isNotEmpty()) {
            floatingIconText.text = text
            floatingIconText.visibility = View.VISIBLE
        } else {
            floatingIconText.visibility = View.GONE
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("text_to_display")?.let {
            Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
            updateText(it)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
        if (::binView.isInitialized && binView.parent != null) {
            windowManager.removeView(binView)
        }
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
    }
}
