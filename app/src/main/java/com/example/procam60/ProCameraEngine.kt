package com.example.procam60

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class ProCameraEngine(
    private val context: Context,
    private val textureView: TextureView
) {
    enum class CameraMode { VIDEO_1080P_60FPS, PRO_PHOTO }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String = "0"
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaRecorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var isRecording = false
    var currentMode = CameraMode.VIDEO_1080P_60FPS

    var isSmartStabilizerEnabled = true
    var isManualExposure = false
    var manualIso: Int = 200
    var manualShutterSpeedNs: Long = 1_000_000_000L / 60L
    var isManualFocus = false
    var manualFocusDistance: Float = 0.0f
    var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO

    private val videoSize = Size(1920, 1080)
    private var photoSize = Size(4000, 3000)

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("ProCameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera() {
        selectBestBackCamera()
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        setupHighestPhotoResolution()

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun selectBestBackCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id
                break
            }
        }
    }

    private fun setupHighestPhotoResolution() {
        val map = cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        if (!sizes.isNullOrEmpty()) {
            photoSize = sizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
        }
    }

    fun startPreviewSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        captureSession?.close()

        val targetSize = if (currentMode == CameraMode.VIDEO_1080P_60FPS) videoSize else photoSize
        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)

        val surfaces = mutableListOf(previewSurface)

        if (currentMode == CameraMode.PRO_PHOTO) {
            imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                savePhotoToGallery(bytes)
            }, backgroundHandler)
            surfaces.add(imageReader!!.surface)
        }

        val template = if (currentMode == CameraMode.VIDEO_1080P_60FPS) {
            CameraDevice.TEMPLATE_RECORD
        } else {
            CameraDevice.TEMPLATE_PREVIEW
        }

        previewRequestBuilder = device.createCaptureRequest(template).apply {
            addTarget(previewSurface)
            applyProAndSharpnessSettings(this)
        }

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                updatePreview()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                showToast("Gagal mengonfigurasi sesi kamera")
            }
        }, backgroundHandler)
    }

    fun updatePreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        applyProAndSharpnessSettings(builder)
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    private fun applyProAndSharpnessSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )

        if (isSmartStabilizerEnabled) {
            val availableOis = cameraCharacteristics?.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            ) ?: intArrayOf()
            if (availableOis.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }

            val availableEis = cameraCharacteristics?.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            ) ?: intArrayOf()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                availableEis.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            ) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                )
            } else if (availableEis.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            }
        } else {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

        if (currentMode == CameraMode.VIDEO_1080P_60FPS) {
            val locked60Fps = findBest60FpsRange()
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, locked60Fps)
        }

        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        if (currentMode == CameraMode.PRO_PHOTO && isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterSpeedNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        if (isManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        } else {
            val afMode = if (currentMode == CameraMode.VIDEO_1080P_60FPS) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }
    }

    private fun findBest60FpsRange(): Range<Int> {
        val ranges = cameraCharacteristics?.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return Range(60, 60)

        return ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
            ?: ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
            ?: Range(30, 60)
    }

    fun toggleVideoRecording(onStateChanged: (Boolean) -> Unit) {
        if (isRecording) {
            stopVideoRecording()
            onStateChanged(false)
        } else {
            startVideoRecording()
            onStateChanged(true)
        }
    }

    private fun startVideoRecording() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        captureSession?.close()
        setupMediaRecorder()

        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)
        val recorderSurface = mediaRecorder!!.surface

        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
            applyProAndSharpnessSettings(this)
        }

        device.createCaptureSession(
            listOf(previewSurface, recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                    mediaRecorder?.start()
                    isRecording = true
                    showToast("Merekam 1080p 60fps Terkunci")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    showToast("Gagal memulai rekaman 60fps")
                }
            },
            backgroundHandler
        )
    }

    private fun stopVideoRecording() {
        try {
            isRecording = false
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            showToast("Video 1080p 60fps tersimpan di Galeri")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        startPreviewSession()
    }

    private fun setupMediaRecorder() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_1080P60_$timestamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam60")
            }
        }

        val videoUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return

        val pfd = context.contentResolver.openFileDescriptor(videoUri, "w") ?: return

        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncodingBitRate(28_000_000)
            setVideoFrameRate(60)
            setVideoSize(1920, 1080)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(256_000)
            setAudioSamplingRate(48_000)
            setOrientationHint(90)
            prepare()
        }
    }

    fun captureProPhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applyProAndSharpnessSettings(this)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, 90)
        }

        session.stopRepeating()
        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                showToast("Foto Profesional Tajam Tersimpan")
                updatePreview()
            }
        }, backgroundHandler)
    }

    private fun savePhotoToGallery(bytes: ByteArray) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_PRO_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam60")
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(bytes)
        }
    }

    private fun showToast(msg: String) {
        Handler(context.mainLooper).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
