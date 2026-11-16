package com.example.procam60

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var cameraEngine: ProCameraEngine

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                initCameraWhenReady()
            } else {
                Toast.makeText(this, "Izin kamera & audio diperlukan", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        cameraEngine = ProCameraEngine(this, textureView)

        setupControls()

        if (hasAllPermissions()) {
            initCameraWhenReady()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun hasAllPermissions(): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun initCameraWhenReady() {
        if (textureView.isAvailable) {
            cameraEngine.openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    cameraEngine.openCamera()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun setupControls() {
        val tvStatusBadge = findViewById<TextView>(R.id.tvStatusBadge)
        val switchStabilizer = findViewById<Switch>(R.id.switchStabilizer)
        val proPanel = findViewById<LinearLayout>(R.id.proControlsPanel)
        val btnModeVideo = findViewById<Button>(R.id.btnModeVideo)
        val btnModePhoto = findViewById<Button>(R.id.btnModePhoto)
        val btnCapture = findViewById<Button>(R.id.btnCapture)

        val seekIso = findViewById<SeekBar>(R.id.seekIso)
        val tvIsoLabel = findViewById<TextView>(R.id.tvIsoLabel)
        val seekShutter = findViewById<SeekBar>(R.id.seekShutter)
        val tvShutterLabel = findViewById<TextView>(R.id.tvShutterLabel)
        val seekFocus = findViewById<SeekBar>(R.id.seekFocus)
        val tvFocusLabel = findViewById<TextView>(R.id.tvFocusLabel)

        switchStabilizer.setOnCheckedChangeListener { _, isChecked ->
            cameraEngine.isSmartStabilizerEnabled = isChecked
            cameraEngine.updatePreview()
        }

        btnModeVideo.setOnClickListener {
            cameraEngine.currentMode = ProCameraEngine.CameraMode.VIDEO_1080P_60FPS
            proPanel.visibility = View.GONE
            tvStatusBadge.text = "1080P • 60FPS LOCK • 28MBPS"
            btnCapture.text = "REC"
            cameraEngine.startPreviewSession()
        }

        btnModePhoto.setOnClickListener {
            cameraEngine.currentMode = ProCameraEngine.CameraMode.PRO_PHOTO
            proPanel.visibility = View.VISIBLE
            tvStatusBadge.text = "PRO PHOTO • JPEG 100% • SHARP ISP"
            btnCapture.text = "SNAP"
            cameraEngine.startPreviewSession()
        }

        btnCapture.setOnClickListener {
            if (cameraEngine.currentMode == ProCameraEngine.CameraMode.VIDEO_1080P_60FPS) {
                cameraEngine.toggleVideoRecording { recording ->
                    runOnUiThread {
                        btnCapture.text = if (recording) "STOP" else "REC"
                    }
                }
            } else {
                cameraEngine.captureProPhoto()
            }
        }

        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    cameraEngine.isManualExposure = false
                    tvIsoLabel.text = "ISO: Auto"
                } else {
                    cameraEngine.isManualExposure = true
                    cameraEngine.manualIso = progress.coerceAtLeast(50)
                    tvIsoLabel.text = "ISO: ${cameraEngine.manualIso}"
                }
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    cameraEngine.isManualExposure = false
                    tvShutterLabel.text = "Shutter: Auto"
                } else {
                    cameraEngine.isManualExposure = true
                    val denominator = (progress * 10).coerceAtLeast(10)
                    cameraEngine.manualShutterSpeedNs = 1_000_000_000L / denominator
                    tvShutterLabel.text = "Shutter: 1/${denominator}s"
                }
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    cameraEngine.isManualFocus = false
                    tvFocusLabel.text = "Fokus: Auto"
                } else {
                    cameraEngine.isManualFocus = true
                    cameraEngine.manualFocusDistance = progress / 10.0f
                    tvFocusLabel.text = "Fokus: ${cameraEngine.manualFocusDistance}D"
                }
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        cameraEngine.startBackgroundThread()
        if (hasAllPermissions() && textureView.isAvailable) {
            cameraEngine.openCamera()
        }
    }

    override fun onPause() {
        cameraEngine.closeCamera()
        cameraEngine.stopBackgroundThread()
        super.onPause()
    }
}
