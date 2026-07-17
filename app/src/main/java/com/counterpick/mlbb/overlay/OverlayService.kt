package com.counterpick.mlbb.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.counterpick.mlbb.R
import com.counterpick.mlbb.engine.DraftEventBus
import com.counterpick.mlbb.engine.HeroRecommendation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildView()
        windowManager.addView(rootView, params)

        lifecycleScope.launch {
            DraftEventBus.recommendations.collect { recs -> renderRecommendations(recs) }
        }
    }

    private fun buildView() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 16, 20, 24))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Best Pick"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dragHandle = TextView(this).apply {
            text = "⠿"
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(title)
        header.addView(dragHandle)
        rootView.addView(header)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootView.addView(listContainer)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            dp(220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        makeDraggable(dragHandle)
    }

    /** Drag-to-reposition on the handle, since the panel otherwise sits over gameplay. */
    private fun makeDraggable(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    windowManager.updateViewLayout(rootView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun renderRecommendations(recs: List<HeroRecommendation>) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        listContainer.removeAllViews()
        if (recs.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Waiting for picks…"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }

        recs.forEachIndexed { index, rec ->
            val pct = (rec.estimatedWinChance * 100).roundToInt()
            // Small risk flag when there are still open enemy slots and this pick is meaningfully
            // exploitable by what's left in the pool — cheap early-pick signal without needing
            // a separate "which pick number am I" input from the user.
            val riskFlag = if (rec.openEnemySlots > 0 && rec.exploitationRisk > 0.02) " ⚠" else ""
            val row = TextView(this).apply {
                text = "${index + 1}. ${rec.hero.name} — $pct%$riskFlag"
                setTextColor(if (index == 0) Color.parseColor("#FFD54F") else Color.WHITE)
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = 13f
                setPadding(0, dp(3), 0, dp(3))
            }
            listContainer.addView(row)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_MIN)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Suggestion overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (::rootView.isInitialized) {
            runCatching { windowManager.removeView(rootView) }
        }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4202
    }
}
