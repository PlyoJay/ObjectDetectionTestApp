package com.samin.objectdetection.distance

data class DepthSamplePoint(val x: Float, val y: Float, val role: String, val valid: Boolean, val meters: Float?,
    val viewX: Float? = null, val viewY: Float? = null)
data class DepthClusterSummary(val meters: Float, val count: Int, val coreCount: Int, val regions: Int,
    val backgroundFraction: Float, val spreadMeters: Float)
data class DepthCandidateSummary(val method: DepthMethod?, val meters: Float?, val count: Int, val status: String,
    val timestampNs: Long?, val confidenceMin: Int? = null, val confidenceMean: Float? = null,
    val confidenceMax: Int? = null, val lowConfidenceCount: Int = 0,
    val clusters: List<DepthClusterSummary> = emptyList(), val confidenceHistogram: List<Int> = emptyList())
data class DepthAnalysis(val full: DepthCandidateSummary?, val raw: DepthCandidateSummary?, val selectionReason: String,
    val points: List<DepthSamplePoint> = emptyList(), val maxMappingErrorPixels: Float? = null,
    val meanMappingErrorPixels: Float? = null, val roundTripErrorPixels: Float? = null, val maxViewMappingErrorPixels: Float? = null)

/** User-supplied lens-forward Z; never inferred from the model or copied to multiple objects. */
data class DepthGroundTruth(val meters: Float, val objectLabel: String)
