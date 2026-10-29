package com.samin.objectdetection.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.model.DetectionSource
import com.samin.objectdetection.warning.RiskObjectCategory
import java.util.Locale
import android.os.SystemClock
import com.samin.objectdetection.metrics.FrameTrace
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.mlkit.MlKitDiagnostics
import com.samin.objectdetection.distance.ViewportProjection

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val yoloState = OverlayFrameState<List<DetectionResult>>(DEFAULT_MAX_OVERLAY_AGE_MS)
    private val mlKitState = OverlayFrameState<List<DetectionResult>>(DEFAULT_MAX_OVERLAY_AGE_MS)
    private var inferenceTimeMs: Long = 0L
    private var fps: Int = 0
    private var analyzerFps = 0.0
    private var inferenceFps = 0.0
    private var frameTrace: FrameTrace? = null
    private var drawnFrameId: Long? = null
    var onFrameDrawn: ((FrameTrace, Long, Long, Boolean) -> Unit)? = null
    private var enabled = true
    private var recognitionRegion: FrameRoi? = null
    private var recognitionWidth = 1
    private var recognitionHeight = 1
    private var recognitionProjection: ViewportProjection? = null
    private var yoloProjection: ViewportProjection? = null
    private var mlKitProjection: ViewportProjection? = null
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f
    }

    fun updateRecognitionRegion(frameWidth: Int, frameHeight: Int, roi: FrameRoi, show: Boolean, projection: ViewportProjection? = null) {
        recognitionWidth = frameWidth; recognitionHeight = frameHeight
        recognitionProjection = projection
        recognitionRegion = roi.takeIf { show }
        postInvalidateOnAnimation()
    }
    private var showYoloBoxes = true
    private var showMlKitBoxes = true
    private var showConfidence = false
    private var showDistance = false
    private var depthAnalysisEnabled = false
    fun setDepthAnalysisEnabled(value: Boolean) { depthAnalysisEnabled = value; postInvalidateOnAnimation() }
    private val depthPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBounds = mutableListOf<RectF>()
    private var showFps = false
    private var staleTimeoutMs = DEFAULT_MAX_OVERLAY_AGE_MS
    var diagnosticLog: ((String) -> Unit)? = null
    internal var mlKitDiagnostics: MlKitDiagnostics? = null
    private var loggedYoloDraw: Long? = null
    private var loggedMlKitDraw: Long? = null
    private var debugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE
    private val clearYoloRunnable: Runnable = Runnable { clearYoloIfStale() }
    private val clearMlKitRunnable: Runnable = Runnable { clearMlKitIfStale() }

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00BFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = CornerPathEffect(15f)
        isAntiAlias = true
    }

    private val mlKitBoxPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = CornerPathEffect(15f)
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val infoPaint = Paint().apply {
        color = Color.parseColor("#00FF7F")
        textSize = 42f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(8f, 3f, 3f, Color.BLACK)
    }

    fun updateDetections(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        inferenceTimeMs: Long,
        fps: Int,
        trace: FrameTrace,
        analyzerFps: Double = fps.toDouble(),
        inferenceFps: Double = fps.toDouble(),
        projection: ViewportProjection? = null
    ) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val previous = yoloState.frame
        val decision = yoloState.update(detections, frameWidth, frameHeight, trace, nowNs)
        logUpdate(DetectionSource.YOLO, trace, nowNs, decision)
        if (decision == OverlayFrameState.Update.OUT_OF_ORDER) return
        yoloProjection = projection
        if (previous != null && previous.trace.frameId != drawnFrameId) {
            diagnosticLog?.invoke("[OVERLAY_SKIPPED] source=YOLO frame=${previous.trace.frameId} reason=REPLACED_BEFORE_DRAW")
        }
        this.inferenceTimeMs = inferenceTimeMs
        this.fps = fps
        this.frameTrace = trace
        this.analyzerFps = analyzerFps
        this.inferenceFps = inferenceFps
        if (debugMode == OverlayDebugMode.FULL) logDetectionDetails(detections, DetectionSource.YOLO)
        expireAndSchedule(yoloState, DetectionSource.YOLO, clearYoloRunnable)
        postInvalidateOnAnimation()
    }

    fun updateMlKitDetections(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        trace: FrameTrace,
        projection: ViewportProjection? = null
    ) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val decision = mlKitState.update(detections, frameWidth, frameHeight, trace, nowNs)
        logUpdate(DetectionSource.ML_KIT, trace, nowNs, decision)
        mlKitDiagnostics?.event(trace.frameId, "OVERLAY_UPDATE") {
            "receivedCount=${detections.size} decision=$decision enabled=$enabled boxes=$showMlKitBoxes " +
                "timeoutMs=$staleTimeoutMs captureAgeMs=${trace.captureAgeMsAt(nowNs)} " +
                "analyzerAgeMs=${(nowNs - trace.analyzerReceivedElapsedNs) / 1_000_000L} " +
                "frameSize=${frameWidth}x$frameHeight view=${width}x$height " +
                "visibility=$visibility shown=$isShown attached=$isAttachedToWindow windowVisibility=$windowVisibility"
        }
        if (decision == OverlayFrameState.Update.OUT_OF_ORDER) return
        mlKitProjection = projection
        if (debugMode == OverlayDebugMode.FULL) logDetectionDetails(detections, DetectionSource.ML_KIT)
        expireAndSchedule(mlKitState, DetectionSource.ML_KIT, clearMlKitRunnable)
        postInvalidateOnAnimation()
    }

    fun setDebugMode(debugMode: OverlayDebugMode) {
        this.debugMode = debugMode
        postInvalidateOnAnimation()
    }

    fun updateFrameRates(analyzerFps: Double, inferenceFps: Double) {
        this.analyzerFps = analyzerFps; this.inferenceFps = inferenceFps
        if (showFps) postInvalidateOnAnimation()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        this.enabled = enabled
        configureStates()
        postInvalidateOnAnimation()
    }

    fun configure(
        enabled: Boolean,
        showYoloBoxes: Boolean,
        showMlKitBoxes: Boolean,
        showConfidence: Boolean,
        showFps: Boolean,
        staleTimeoutMs: Long,
        showDistance: Boolean = false
    ) {
        this.enabled = enabled
        this.showYoloBoxes = showYoloBoxes
        this.showMlKitBoxes = showMlKitBoxes
        this.showConfidence = showConfidence
        this.showDistance = showDistance
        this.showFps = showFps
        this.staleTimeoutMs = staleTimeoutMs.coerceIn(100L, 60_000L)
        configureStates()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        labelBounds.clear()
        // Recognition guide is independent of bbox visibility and detector result expiry.
        recognitionRegion?.let { roi ->
            val transform = calculateFitCenterTransform(recognitionWidth, recognitionHeight, width, height, recognitionProjection)
            canvas.drawRect(transform.offsetX + roi.left * transform.scale, transform.offsetY + roi.top * transform.scaleY,
                transform.offsetX + roi.right * transform.scale, transform.offsetY + roi.bottom * transform.scaleY, regionPaint)
        }

        // 중앙 감시 구역 가이드
//        val guidePaint = Paint().apply {
//            color = Color.parseColor("#00E676")
//            strokeWidth = 5f
//            alpha = 180
//        }
//        canvas.drawLine(width * 0.33f, 0f, width * 0.33f, height.toFloat(), guidePaint)
//        canvas.drawLine(width * 0.67f, 0f, width * 0.67f, height.toFloat(), guidePaint)

        // Timers may be delayed by a busy main thread. Recheck before every actual draw.
        expireAndSchedule(yoloState, DetectionSource.YOLO, clearYoloRunnable)
        expireAndSchedule(mlKitState, DetectionSource.ML_KIT, clearMlKitRunnable)
        if (!enabled) {
            notifyFrameDrawn(false)
            return
        }

        val yoloFrame = yoloState.frame
        val mlKitFrame = mlKitState.frame
        val transform = calculateFitCenterTransform(yoloFrame?.width ?: 1, yoloFrame?.height ?: 1, width, height, yoloProjection)
        val mlKitTransform = calculateFitCenterTransform(mlKitFrame?.width ?: 1, mlKitFrame?.height ?: 1, width, height, mlKitProjection)
        val freshDetections = yoloFrame?.value.orEmpty()
        val freshMlKitDetections = mlKitFrame?.value.orEmpty()
        if (yoloFrame != null && loggedYoloDraw != yoloFrame.trace.frameId) {
            logDraw(DetectionSource.YOLO, yoloFrame)
            loggedYoloDraw = yoloFrame.trace.frameId
        }
        if (mlKitFrame != null && loggedMlKitDraw != mlKitFrame.trace.frameId) {
            logDraw(DetectionSource.ML_KIT, mlKitFrame)
            mlKitDiagnostics?.event(mlKitFrame.trace.frameId, "OVERLAY_DRAW") {
                val drawable = freshMlKitDetections.count { box ->
                    val left = mlKitTransform.offsetX + box.left * mlKitTransform.scale
                    val top = mlKitTransform.offsetY + box.top * mlKitTransform.scaleY
                    val right = mlKitTransform.offsetX + box.right * mlKitTransform.scale
                    val bottom = mlKitTransform.offsetY + box.bottom * mlKitTransform.scaleY
                    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                        right > left && bottom > top && right > 0 && bottom > 0 && left < width && top < height
                }
                "drawCalls=${if (showMlKitBoxes) freshMlKitDetections.size else 0} " +
                    "inViewCount=${if (showMlKitBoxes && width > 0 && height > 0) drawable else 0} " +
                    "reason=${if (!showMlKitBoxes) "BOXES_DISABLED" else if (width <= 0 || height <= 0) "VIEW_EMPTY" else if (freshMlKitDetections.isEmpty()) "EMPTY_RESULT" else if (drawable == 0) "OUTSIDE_VIEW_OR_INVALID_BOX" else "DRAWN"} " +
                    "ageMs=${mlKitFrame.ageMs(SystemClock.elapsedRealtimeNanos())} " +
                    "scale=${mlKitTransform.scale} offset=[${mlKitTransform.offsetX},${mlKitTransform.offsetY}] " +
                    "screenBoxes=${freshMlKitDetections.take(5).joinToString { box ->
                        "[${mlKitTransform.offsetX + box.left * mlKitTransform.scale},${mlKitTransform.offsetY + box.top * mlKitTransform.scaleY}," +
                            "${mlKitTransform.offsetX + box.right * mlKitTransform.scale},${mlKitTransform.offsetY + box.bottom * mlKitTransform.scaleY}]"
                    }}"
            }
            loggedMlKitDraw = mlKitFrame.trace.frameId
        }

        if (showYoloBoxes) freshDetections.forEach { res ->
            val left = transform.offsetX + res.left * transform.scale
            val top = transform.offsetY + res.top * transform.scaleY
            val right = transform.offsetX + res.right * transform.scale
            val bottom = transform.offsetY + res.bottom * transform.scaleY

            boxPaint.color = when {
                res.isIgnored -> Color.parseColor("#888888")
                res.label == "person" || res.label == "사람" -> Color.parseColor("#FF69B4")
                res.label == "cat" -> Color.parseColor("#BA68C8")
                res.label == "dog" -> Color.parseColor("#26C6DA")
                res.riskObjectCategory == RiskObjectCategory.VEHICLE_RISK -> Color.parseColor("#FF9800")
                res.riskObjectCategory == RiskObjectCategory.TRAFFIC_CONTROL -> Color.parseColor("#00E676")
                else -> Color.parseColor("#00BFFF")
            }

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, boxPaint)
            if (depthAnalysisEnabled) res.depthEstimation?.analysis?.points?.forEach { p ->
                depthPointPaint.color = when {
                    !p.valid -> Color.RED
                    p.role == "SELECTED" -> Color.GREEN
                    p.role == "BACKGROUND" -> Color.CYAN
                    else -> Color.YELLOW
                }
                val x = p.viewX?.times(width) ?: (transform.offsetX + p.x * transform.scale)
                val y = p.viewY?.times(height) ?: (transform.offsetY + p.y * transform.scaleY)
                if (x in 0f..width.toFloat() && y in 0f..height.toFloat()) canvas.drawCircle(x, y, 4f, depthPointPaint)
            }
            drawLabelIfNeeded(canvas, res, DetectionSource.YOLO, left, top, bottom)
        }

        if (showMlKitBoxes) freshMlKitDetections.forEach { res ->
            val left = mlKitTransform.offsetX + res.left * mlKitTransform.scale
            val top = mlKitTransform.offsetY + res.top * mlKitTransform.scaleY
            val right = mlKitTransform.offsetX + res.right * mlKitTransform.scale
            val bottom = mlKitTransform.offsetY + res.bottom * mlKitTransform.scaleY

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, mlKitBoxPaint)
            drawLabelIfNeeded(canvas, res, DetectionSource.ML_KIT, left, top, bottom)
        }
        if (showFps) {
            canvas.drawText(String.format(Locale.US, "FPS=%.1f A=%.1f inference=%dms F=%d", inferenceFps, analyzerFps, inferenceTimeMs, frameTrace?.frameId ?: 0), 24f, 56f, infoPaint)
        }
        notifyFrameDrawn(showYoloBoxes && yoloFrame != null)
    }

    private fun notifyFrameDrawn(drawingEnabled: Boolean) {
        val trace = frameTrace ?: return
        if (drawnFrameId == trace.frameId) return
        drawnFrameId = trace.frameId
        onFrameDrawn?.invoke(trace, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), drawingEnabled)
    }

    private fun drawLabelIfNeeded(
        canvas: Canvas,
        detection: DetectionResult,
        source: DetectionSource,
        left: Float,
        top: Float,
        bottom: Float
    ) {
        val label = buildLabel(detection, source) ?: return
        if (!left.isFinite() || !top.isFinite() || !bottom.isFinite() || width <= 20 || height <= 20) return
        val labelPaint = TextPaint(textPaint).apply { textSize = minOf(textSize, width / 15f) }
        val maxTextWidth = (width - LABEL_HORIZONTAL_PADDING_PX).toInt().coerceAtLeast(1)
        val textWidth = kotlin.math.ceil(labelPaint.measureText(label).toDouble()).toInt().coerceIn(1, maxTextWidth)
        val layout = StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(if (depthAnalysisEnabled && detection.depthEstimation != null) 8 else 4).setEllipsize(TextUtils.TruncateAt.END).build()
        val labelWidth = textWidth + LABEL_HORIZONTAL_PADDING_PX
        val labelHeight = layout.height + 12f
        if (labelHeight > height) return
        val x = left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
        // Prefer outside the box; an inset fallback avoids covering the stroke at a screen edge.
        val candidates = listOf(top - labelHeight - 6f, bottom + 6f, top + 8f) +
            labelBounds.map { it.bottom + 4f }
        val bounds = candidates.asSequence().filter { it >= 0f && it + labelHeight <= height }
            .map { RectF(x, it, x + labelWidth, it + labelHeight) }
            .firstOrNull { candidate -> labelBounds.none { RectF.intersects(candidate, it) } } ?: return
        labelBounds.add(bounds)
        canvas.drawRoundRect(bounds, 8f, 8f, bgPaint)
        canvas.save()
        canvas.translate(bounds.left + LABEL_TEXT_LEFT_PADDING_PX, bounds.top + 6f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLabel(
        detection: DetectionResult,
        source: DetectionSource
    ): String? {
        val distance = detection.estimatedDistanceMeters?.takeIf { it.isFinite() && it > 0f }
            ?.takeIf { showDistance && source == DetectionSource.YOLO }
            ?.takeIf { detection.depthEstimation?.isFresh(SystemClock.elapsedRealtimeNanos()) != false }
        val base = when (debugMode) {
            OverlayDebugMode.NONE -> null
            OverlayDebugMode.SIMPLE -> {
                if (source == DetectionSource.ML_KIT) {
                    detection.label
                } else {
                    "${detection.label} ${detection.riskLevel}" +
                        if (showConfidence) " ${String.format(Locale.US, "%.2f", detection.confidence)}" else ""
                }
            }
            OverlayDebugMode.FULL -> buildFullDebugLabel(detection, source)
        }
        fun debugSuffix(label: String?): String? {
            val d = detection.depthEstimation
            if (!depthAnalysisEnabled || source != DetectionSource.YOLO || d == null) return label
            fun meters(value: Float?) = value?.let { String.format(Locale.US, "%.2f", it) } ?: "null"
            val a = d.analysis
            return (label?.substringBefore('\n') ?: detection.label) + "\n${d.method ?: "NONE"} n=${d.sampleCount} raw=${meters(d.rawMeters)} Z=${meters(d.meters)}" +
                "\n${d.status} / ${d.filterStatus}" +
                "\nFULL=${meters(a?.full?.meters)} RAW=${meters(a?.raw?.meters)} ${a?.selectionReason.orEmpty()}" +
                (a?.maxMappingErrorPixels?.let { "\nmapΔ=${String.format(Locale.US, "%.3f", it)}px" } ?: "") +
                (d.groundTruthMeters?.let { " GT=${meters(it)}m" } ?: "") +
                (label?.takeIf { it.contains('\n') }?.let { "\n" + it.substringAfter('\n') } ?: "")
        }
        if (distance == null) return debugSuffix(base)
        val distanceText = if (distance < .05f) "0.1m 미만" else "${String.format(Locale.US, "%.1f", distance)}m"
        val distanceLabel = "${detection.label} 약 $distanceText"
        return debugSuffix(if (base == null) distanceLabel +
            (if (showConfidence) " ${String.format(Locale.US, "%.2f", detection.confidence)}" else "")
        else distanceLabel + base.removePrefix(detection.label))
    }

    private fun buildFullDebugLabel(
        detection: DetectionResult,
        source: DetectionSource
    ): String {
        return buildString {
            append("${detection.label} ${source.name} ")
            append("c=${String.format(Locale.US, "%.2f", detection.confidence)} ")
            append("a=${String.format(Locale.US, "%.3f", detection.bboxAreaRatio)} ")
            append("h=${String.format(Locale.US, "%.3f", detection.bboxHeightRatio)} ")
            append("p=${detection.objectPriority} ")
            append("${detection.proximityLevel}/${detection.riskLevel}/${detection.horizontalPosition} ")
            append("motion=${detection.motionDirection} ")
            append("scenario=${detection.warningScenario} ")
            append("b=${detection.warningFeedback.beepLevel} ")
            append("v=${detection.warningFeedback.vibrationLevel} ")
            append("voice=${detection.warningFeedback.voiceLevel} ")
            append("notify=${detection.warningFeedback.shouldNotify} ")
            append("msg=${detection.warningFeedback.message ?: "none"}")
            if (detection.isIgnored) append(" IGNORED")
        }
    }

    private fun logDetectionDetails(
        detections: List<DetectionResult>,
        source: DetectionSource
    ) {
        detections.forEach { detection ->
            Log.d(
                WARNING_DETAIL_TAG,
                "source=${source.name} label=${detection.label} " +
                    "confidence=${String.format(Locale.US, "%.2f", detection.confidence)} " +
                    "bboxAreaRatio=${String.format(Locale.US, "%.3f", detection.bboxAreaRatio)} " +
                    "bboxHeightRatio=${String.format(Locale.US, "%.3f", detection.bboxHeightRatio)} " +
                    "centerXRatio=${String.format(Locale.US, "%.3f", detection.centerXRatio)} " +
                    "centerYRatio=${String.format(Locale.US, "%.3f", detection.centerYRatio)} " +
                    "horizontalPosition=${detection.horizontalPosition} " +
                    "riskObjectCategory=${detection.riskObjectCategory} " +
                    "objectPriority=${detection.objectPriority} " +
                    "proximityLevel=${detection.proximityLevel} " +
                    "riskLevel=${detection.riskLevel} " +
                    "motionDirection=${detection.motionDirection} " +
                    "scenario=${detection.warningScenario} " +
                    "beep=${detection.warningFeedback.beepLevel} " +
                    "vibration=${detection.warningFeedback.vibrationLevel} " +
                    "voice=${detection.warningFeedback.voiceLevel} " +
                    "shouldNotify=${detection.warningFeedback.shouldNotify} " +
                    "message=${detection.warningFeedback.message ?: "none"} " +
                    "ignored=${detection.isIgnored}"
            )
        }
    }

    private fun calculateFitCenterTransform(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        projection: ViewportProjection? = null
    ): PreviewTransform {
        if (projection != null) return PreviewTransform(
            projection.scaleX * viewWidth / frameWidth.coerceAtLeast(1), projection.offsetX * viewWidth,
            projection.offsetY * viewHeight, projection.scaleX * viewWidth, projection.scaleY * viewHeight,
            projection.scaleY * viewHeight / frameHeight.coerceAtLeast(1))
        val safeFrameWidth = frameWidth.coerceAtLeast(1)
        val safeFrameHeight = frameHeight.coerceAtLeast(1)
        val safeViewWidth = viewWidth.coerceAtLeast(1)
        val safeViewHeight = viewHeight.coerceAtLeast(1)
        val scale = minOf(
            safeViewWidth / safeFrameWidth.toFloat(),
            safeViewHeight / safeFrameHeight.toFloat()
        )
        val displayedWidth = safeFrameWidth * scale
        val displayedHeight = safeFrameHeight * scale
        val offsetX = (safeViewWidth - displayedWidth) / 2f
        val offsetY = (safeViewHeight - displayedHeight) / 2f

        if (debugMode == OverlayDebugMode.FULL) {
            logTransformIfAspectMismatch(
                safeFrameWidth,
                safeFrameHeight,
                safeViewWidth,
                safeViewHeight,
                scale,
                offsetX,
                offsetY
            )
        }

        return PreviewTransform(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            displayedWidth = displayedWidth,
            displayedHeight = displayedHeight
        )
    }

    private fun logTransformIfAspectMismatch(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val frameAspect = frameWidth / frameHeight.toFloat()
        val viewAspect = viewWidth / viewHeight.toFloat()
        val swappedFrameAspect = frameHeight / frameWidth.toFloat()
        val currentDelta = kotlin.math.abs(frameAspect - viewAspect)
        val swappedDelta = kotlin.math.abs(swappedFrameAspect - viewAspect)
        if (currentDelta > 0.05f || swappedDelta < currentDelta) {
            Log.d(
                TRANSFORM_TAG,
                "fitCenter frame=${frameWidth}x$frameHeight view=${viewWidth}x$viewHeight " +
                    "scale=$scale offsetX=$offsetX offsetY=$offsetY swapSuggested=${swappedDelta < currentDelta}"
            )
        }
    }

    private fun clearYoloIfStale() {
        expireAndSchedule(yoloState, DetectionSource.YOLO, clearYoloRunnable)
        postInvalidateOnAnimation() // A Depth deadline may precede the BBox deadline.
    }

    private fun clearMlKitIfStale(): Unit = expireAndSchedule(mlKitState, DetectionSource.ML_KIT, clearMlKitRunnable)

    private fun expireAndSchedule(
        state: OverlayFrameState<List<DetectionResult>>,
        source: DetectionSource,
        timer: Runnable
    ) {
        removeCallbacks(timer)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        state.expire(nowNs)?.let { expired ->
            if (source == DetectionSource.ML_KIT) mlKitDiagnostics?.event(expired.trace.frameId, "OVERLAY_CLEARED") {
                "reason=EXPIRED ageMs=${expired.ageMs(nowNs)} basis=${expired.timeBasis} count=${expired.value.size}"
            }
            diagnosticLog?.invoke("[OVERLAY_CLEARED] source=$source frame=${expired.trace.frameId} " +
                "reason=EXPIRED ageMs=${expired.ageMs(nowNs)} basis=${expired.timeBasis} elapsedNs=$nowNs")
            postInvalidateOnAnimation()
        }
        state.remainingDelayMs(nowNs)?.let { boxDelay ->
            val depthDelay = if (source == DetectionSource.YOLO) state.frame?.value.orEmpty().mapNotNull { it.depthEstimation }
                .filter { it.meters != null && it.expiresElapsedNs > nowNs }
                .minOfOrNull { (it.expiresElapsedNs - nowNs + 999_999) / 1_000_000 } else null
            postDelayed(timer, minOf(boxDelay, depthDelay ?: boxDelay).coerceAtLeast(1L))
        }
    }

    private fun configureStates() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        // Log expiry before reconfiguration can clear the state.
        clearYoloIfStale()
        clearMlKitIfStale()
        configureState(yoloState, DetectionSource.YOLO, enabled && showYoloBoxes, nowNs)
        configureState(mlKitState, DetectionSource.ML_KIT, enabled && showMlKitBoxes, nowNs)
        clearYoloIfStale()
        clearMlKitIfStale()
    }

    private fun configureState(state: OverlayFrameState<List<DetectionResult>>, source: DetectionSource, active: Boolean, nowNs: Long) {
        val previous = state.frame
        state.configure(active, staleTimeoutMs, nowNs)
        if (previous != null && state.frame == null) {
            if (source == DetectionSource.ML_KIT) mlKitDiagnostics?.event(previous.trace.frameId, "OVERLAY_CLEARED") {
                "reason=CONFIG_CHANGED active=$active timeoutMs=$staleTimeoutMs"
            }
            diagnosticLog?.invoke("[OVERLAY_CLEARED] source=$source frame=${previous.trace.frameId} " +
                "reason=CONFIG_CHANGED active=$active timeoutMs=$staleTimeoutMs ageMs=${previous.ageMs(nowNs)}")
        }
    }

    /** Discards callbacks, payloads and draw bookkeeping at settings restart / Activity teardown. */
    fun clearDetections() {
        mlKitState.frame?.let { frame -> mlKitDiagnostics?.event(frame.trace.frameId, "OVERLAY_CLEARED") { "reason=RESET" } }
        recognitionRegion = null
        recognitionProjection = null; yoloProjection = null; mlKitProjection = null
        removeCallbacks(clearYoloRunnable)
        removeCallbacks(clearMlKitRunnable)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        yoloState.clear(nowNs)
        mlKitState.clear(nowNs)
        frameTrace = null
        drawnFrameId = null
        loggedYoloDraw = null
        loggedMlKitDraw = null
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        clearDetections()
        super.onDetachedFromWindow()
    }

    private fun logUpdate(source: DetectionSource, trace: FrameTrace, nowNs: Long, decision: OverlayFrameState.Update) {
        diagnosticLog?.invoke("[OVERLAY_UPDATE] source=$source sessionId=${trace.sessionId} frame=${trace.frameId} " +
            "cameraTimestampNs=${trace.cameraTimestampNs} captureTimestampReliable=${trace.captureTimestampReliable} " +
            "analyzerReceivedElapsedNs=${trace.analyzerReceivedElapsedNs} completedElapsedNs=${trace.completedElapsedNs} " +
            "updateElapsedNs=$nowNs analyzerAgeMs=${(nowNs - trace.analyzerReceivedElapsedNs) / 1_000_000L} " +
            "captureAgeMs=${trace.captureAgeMsAt(nowNs)} reason=$decision")
    }

    private fun logDraw(source: DetectionSource, frame: OverlayFrameState.Frame<List<DetectionResult>>) {
        val log = diagnosticLog ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        log("[OVERLAY_DRAW] source=$source sessionId=${frame.trace.sessionId} frame=${frame.trace.frameId} " +
            "cameraTimestampNs=${frame.trace.cameraTimestampNs} completedElapsedNs=${frame.trace.completedElapsedNs} " +
            "updateElapsedNs=${frame.updatedElapsedNs} drawElapsedNs=$nowNs " +
            "ageMs=${frame.ageMs(nowNs)} basis=${frame.timeBasis} " +
            "visible=${frame.value.size} view=${width}x$height frameSize=${frame.width}x${frame.height} transform=FIT_CENTER " +
            "otherFrame=${if (source == DetectionSource.YOLO) mlKitState.frame?.trace?.frameId else yoloState.frame?.trace?.frameId}")
    }

    companion object {
        private const val DEFAULT_MAX_OVERLAY_AGE_MS = 1500L
        private const val TRANSFORM_TAG = "OverlayTransform"
        private const val WARNING_DETAIL_TAG = "GotoroWarning"
        private const val LABEL_HORIZONTAL_PADDING_PX = 20f
        private const val LABEL_TEXT_LEFT_PADDING_PX = 10f
    }

    private data class PreviewTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val displayedWidth: Float,
        val displayedHeight: Float,
        val scaleY: Float = scale
    )
}
