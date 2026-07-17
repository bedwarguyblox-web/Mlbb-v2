package com.counterpick.mlbb.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.counterpick.mlbb.capture.ScreenCaptureService
import com.counterpick.mlbb.data.RankTier
import com.counterpick.mlbb.engine.DraftEventBus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var selectedRank: RankTier = RankTier.MYTHIC
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = ScreenCaptureService.buildStartIntent(this, result.resultCode, result.data!!, selectedRank)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        observeCaptureState()
        requestNotificationPermissionIfNeeded()
    }

    private fun buildUi(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val title = TextView(this).apply {
            text = "MLBB Counter Pick"
            setTextColor(Color.WHITE)
            textSize = 22f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Select your rank, then start the overlay before entering draft."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(24))
        }
        root.addView(subtitle)

        val rankLabel = TextView(this).apply {
            text = "Rank"
            setTextColor(Color.WHITE)
        }
        root.addView(rankLabel)

        val ranks = RankTier.entries.map { it.label }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ranks)
            setSelection(RankTier.entries.indexOf(RankTier.MYTHIC))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedRank = RankTier.entries[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(spinner)

        statusText = TextView(this).apply {
            text = "Overlay: stopped"
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(statusText)

        toggleButton = Button(this).apply {
            text = "Start overlay"
            setOnClickListener { onToggleClicked() }
        }
        root.addView(toggleButton)

        val calibrationNote = TextView(this).apply {
            text = "First-time setup: grant \"draw over other apps\", then drop hero icon " +
                "crops into assets/hero_icons/<name>.png before building, and adjust " +
                "draft_layout.json (in the app's internal storage) if slot detection misses."
            setTextColor(Color.GRAY)
            textSize = 11f
            gravity = Gravity.START
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(calibrationNote)

        return root
    }

    private fun onToggleClicked() {
        if (DraftEventBus.isCapturing.value) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant \"draw over other apps\" first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun observeCaptureState() {
        lifecycleScope.launch {
            DraftEventBus.isCapturing.collect { active ->
                statusText.text = if (active) "Overlay: running — switch to MLBB" else "Overlay: stopped"
                toggleButton.text = if (active) "Stop overlay" else "Start overlay"
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
