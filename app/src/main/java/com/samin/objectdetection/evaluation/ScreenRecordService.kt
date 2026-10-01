package com.samin.objectdetection.evaluation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.samin.objectdetection.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class ScreenRecordService : Service() {
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording(sendBroadcast = true, stopProjection = false)
            stopSelf()
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null
    private var isStoppingProjection = false
    private var recorderStarted = false
    private val galleryExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundForMediaProjection()
                startRecording(intent)
            }
            ACTION_STOP -> {
                stopRecording(sendBroadcast = true, stopProjection = true)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording(sendBroadcast = false, stopProjection = true)
        // Already submitted copies finish even after the service stops.
        galleryExecutor.shutdown()
        super.onDestroy()
    }

    private fun startForegroundForMediaProjection() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(intent: Intent) {
        if (mediaRecorder != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
        val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)

        if (resultCode == 0 || resultData == null || outputPath.isNullOrBlank()) {
            broadcastState(ACTION_RECORDING_ERROR, "Missing screen recording permission data")
            stopSelf()
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        try {
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection permission was not granted")
            mediaProjection = projection
            val file = File(outputPath)
            outputFile = file
            val size = resolveRecordSize()
            val recorder = buildMediaRecorder(file, size.width, size.height)
            mediaRecorder = recorder

            projection.registerCallback(projectionCallback, null)
            val display = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                size.width,
                size.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            virtualDisplay = display
            recorder.start()
            recorderStarted = true
            broadcastState(ACTION_RECORDING_STARTED, file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "start screen recording failed", e)
            broadcastState(ACTION_RECORDING_ERROR, e.message ?: "Screen recording failed")
            stopRecording(sendBroadcast = false, stopProjection = true)
            stopSelf()
        }
    }

    private fun buildMediaRecorder(outputFile: File, width: Int, height: Int): MediaRecorder {
        outputFile.parentFile?.mkdirs()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoSize(width, height)
                prepare()
            }
            return recorder
        } catch (error: Exception) {
            safeReleaseRecorder(recorder, stop = false)
            throw error
        }
    }

    private fun resolveRecordSize(): RecordSize {
        val displayMetrics = resources.displayMetrics
        val rawWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val rawHeight = displayMetrics.heightPixels.coerceAtLeast(1)
        val longestSide = maxOf(rawWidth, rawHeight)
        val scale = if (longestSide > MAX_RECORD_LONG_SIDE) {
            MAX_RECORD_LONG_SIDE / longestSide.toFloat()
        } else {
            1f
        }

        return RecordSize(
            width = makeEven((rawWidth * scale).toInt().coerceAtLeast(2)),
            height = makeEven((rawHeight * scale).toInt().coerceAtLeast(2))
        )
    }

    private fun stopRecording(sendBroadcast: Boolean, stopProjection: Boolean) {
        val file = outputFile
        val projection = mediaProjection
        val recorder = mediaRecorder
        val wasStarted = recorderStarted
        outputFile = null
        mediaRecorder = null
        mediaProjection = null
        recorderStarted = false

        runCatching { virtualDisplay?.release() }
            .onFailure { Log.e(TAG, "release virtual display failed", it) }
        virtualDisplay = null

        val finalized = recorder?.let { safeReleaseRecorder(it, stop = wasStarted) } == true && wasStarted

        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            if (stopProjection && !isStoppingProjection) {
                try {
                    isStoppingProjection = true
                    projection.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "stop media projection failed", e)
                } finally {
                    isStoppingProjection = false
                }
            }
        }
        val validRecording = finalized && file != null && file.isFile && file.length() > 0L
        if (validRecording) {
            galleryExecutor.execute { publishVideoSafely(requireNotNull(file)) }
        }

        if (sendBroadcast) {
            if (validRecording) {
                broadcastState(ACTION_RECORDING_STOPPED, file?.absolutePath)
            } else if (recorder != null || file != null) {
                broadcastState(ACTION_RECORDING_ERROR, "Recording did not finalize; gallery publish skipped")
            }
        }
    }

    private fun safeReleaseRecorder(recorder: MediaRecorder, stop: Boolean): Boolean {
        return try {
            if (stop) {
                recorder.stop()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop media recorder failed", e)
            false
        } finally {
            try {
                recorder.reset()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun publishVideoSafely(file: File) {
        // minSdk is 33; all supported devices use the Android 10+ MediaStore contract.
        if (!file.isFile || file.length() <= 0L) return
        val relativePath = "${Environment.DIRECTORY_MOVIES}/GOTORO/Recordings"
        val resolver = contentResolver
        var pendingUri: android.net.Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IOException("MediaStore video insert returned null")
            pendingUri = uri
            resolver.openOutputStream(uri, "w")?.use { output ->
                val copied = file.inputStream().use { input -> input.copyTo(output) }
                if (copied <= 0L || copied != file.length()) throw IOException("Incomplete video copy: $copied bytes")
            } ?: throw IOException("MediaStore video OutputStream is null")
            val completed = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            if (resolver.update(uri, completed, null, null) <= 0) {
                throw IOException("Failed to publish pending video: $uri")
            }
            Log.i(TAG, "gallery video saved uri=$uri path=$relativePath/${file.name}")
        } catch (error: Exception) {
            pendingUri?.let { uri ->
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { Log.e(TAG, "failed to delete pending video uri=$uri", it) }
            }
            Log.e(TAG, "gallery video publish failed privatePath=${file.absolutePath}", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen recording",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ObjectDetection screen recording")
            .setContentText("Recording the app screen for evaluation.")
            .setOngoing(true)
            .build()
    }

    private fun broadcastState(action: String, message: String?) {
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun makeEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }

    private data class RecordSize(
        val width: Int,
        val height: Int
    )

    companion object {
        const val ACTION_START = "com.samin.objectdetection.action.START_SCREEN_RECORDING"
        const val ACTION_STOP = "com.samin.objectdetection.action.STOP_SCREEN_RECORDING"
        const val ACTION_RECORDING_STARTED = "com.samin.objectdetection.action.SCREEN_RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.samin.objectdetection.action.SCREEN_RECORDING_STOPPED"
        const val ACTION_RECORDING_ERROR = "com.samin.objectdetection.action.SCREEN_RECORDING_ERROR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_MESSAGE = "extra_message"

        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1002
        private const val VIRTUAL_DISPLAY_NAME = "ObjectDetectionScreenRecording"
        private const val MAX_RECORD_LONG_SIDE = 1920
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_BIT_RATE = 8_000_000
    }
}
