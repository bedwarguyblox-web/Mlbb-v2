package com.counterpick.mlbb.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.counterpick.mlbb.R
import com.counterpick.mlbb.data.RankTier
import com.counterpick.mlbb.data.StatsRepository
import com.counterpick.mlbb.engine.DraftEventBus
import com.counterpick.mlbb.engine.RecommendationEngine
import com.counterpick.mlbb.overlay.OverlayService
import com.counterpick.mlbb.vision.DraftLayoutConfig
import com.counterpick.mlbb.vision.DraftScanner
import com.counterpick.mlbb.vision.HeroIconMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class ScreenCaptureService : LifecycleService() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var scanner: DraftScanner
    private lateinit var engine: RecommendationEngine
    private var rank: RankTier = RankTier.MYTHIC

    // Android 14+ (API 34) throws a SecurityException from createVirtualDisplay() if the
    // MediaProjection doesn't have a registered callback yet — this is mandatory, not optional,
    // as of targetSdk 34. It also doubles as our signal for "user stopped sharing from the
    // system status bar/notification" so we can tear down instead of polling a dead surface.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        rank = RankTier.entries.getOrElse(intent?.getIntExtra(EXTRA_RANK_ORDINAL, RankTier.MYTHIC.ordinal) ?: RankTier.MYTHIC.ordinal) { RankTier.MYTHIC }

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture(resultCode, resultData)
        startOverlay()
        return START_STICKY
    }

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java))
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, android.os.Handler(android.os.Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "mlbb-counterpick-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        DraftEventBus.setCapturing(true)

        lifecycleScope.launch(Dispatchers.Default) {
            val repo = StatsRepository.get(this@ScreenCaptureService)
            val layout = DraftLayoutConfig.loadOrCreateDefault(this@ScreenCaptureService)
            val matcher = HeroIconMatcher(this@ScreenCaptureService, repo.heroesById.values)
            scanner = DraftScanner(layout, matcher)
            engine = RecommendationEngine(repo)

            // Draft phase is slow (bans/picks take seconds each); polling ~1.2s keeps
            // CPU/battery reasonable without feeling laggy to the user.
            while (isActive) {
                val bitmap = captureFrame(reader)
                if (bitmap != null) {
                    val draft = scanner.scan(bitmap, rank)
                    bitmap.recycle()
                    if (draft.lockedAllyPicks().isNotEmpty() || draft.lockedEnemyPicks().isNotEmpty()) {
                        // StatsRepository throttles this internally (TTL caches per rank / per
                        // locked hero), so calling it on every ~1.2s poll is cheap once warm —
                        // it only actually hits the network when something's gone stale.
                        repo.refreshLive(rank, draft.allyPicks, draft.enemyPicks)
                        val recs = engine.recommend(draft)
                        withContext(Dispatchers.Main) { DraftEventBus.publish(recs) }
                    }
                }
                delay(1200)
            }
            matcher.release()
        }
    }

    private fun captureFrame(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                bitmap
            } else {
                // The padded bitmap above is only scratch space once cropped — most devices
                // report nonzero row padding, so leaving this unrecycled leaked a full-screen
                // bitmap on effectively every ~1.2s poll during live capture.
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            }
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.capture_channel_desc)
                }
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Reading draft screen…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        DraftEventBus.setCapturing(false)
        stopService(Intent(this, OverlayService::class.java))
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RANK_ORDINAL = "extra_rank_ordinal"
        private const val NOTIFICATION_ID = 4201

        fun buildStartIntent(context: Context, resultCode: Int, resultData: Intent, rank: RankTier): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_RANK_ORDINAL, rank.ordinal)
            }
    }
}
