package com.sanjeevani.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.sanjeevani.model.NormalizedLandmark
import com.sanjeevani.model.PerceptionFrame

class MediaPipeController(private val context: Context) {

    private var poseLandmarker: PoseLandmarker? = null
    private var handLandmarker: HandLandmarker? = null
    private var latestPoseResult: PoseLandmarkerResult? = null
    private var latestHandResult: HandLandmarkerResult? = null

    fun initialize() {
        initPose()
        initHands()
    }

    private fun initPose() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> latestPoseResult = result }
            .build()
        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    private fun initHands() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> latestHandResult = result }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun processFrame(bitmap: Bitmap, timestampMs: Long, width: Int, height: Int): PerceptionFrame {
        val mpImage = BitmapImageBuilder(bitmap).build()

        poseLandmarker?.detectAsync(mpImage, timestampMs)
        handLandmarker?.detectAsync(mpImage, timestampMs)

        // Read latest results (may lag by 1–2 frames in live stream mode)
        val poseResult = latestPoseResult
        val handResult = latestHandResult

        val poseLandmarks = poseResult?.landmarks()
            ?.firstOrNull()
            ?.map { lm -> NormalizedLandmark(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(1f)) }

        var leftHandLandmarks: List<NormalizedLandmark>? = null
        var rightHandLandmarks: List<NormalizedLandmark>? = null

        handResult?.let { hr ->
            for (i in hr.handednesses().indices) {
                val handedness = hr.handednesses()[i].firstOrNull()?.categoryName() ?: continue
                val landmarks = hr.landmarks()[i].map { lm ->
                    NormalizedLandmark(lm.x(), lm.y(), lm.z())
                }
                // MediaPipe reports from camera perspective (mirrored), so Left = user's right
                if (handedness == "Left") rightHandLandmarks = landmarks
                else leftHandLandmarks = landmarks
            }
        }

        return PerceptionFrame(
            timestamp = timestampMs,
            poseLandmarks = poseLandmarks,
            leftHandLandmarks = leftHandLandmarks,
            rightHandLandmarks = rightHandLandmarks,
            imageWidth = width,
            imageHeight = height
        )
    }

    fun close() {
        poseLandmarker?.close()
        handLandmarker?.close()
    }
}
