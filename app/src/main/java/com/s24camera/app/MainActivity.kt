package com.s24camera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    // --- Vues ---
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var countdownText: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var topBar: View
    private lateinit var btnFlash: ImageButton
    private lateinit var btnTimer: TextView
    private lateinit var btnRatio: TextView
    private lateinit var btnGrid: ImageButton
    private lateinit var recordingIndicator: View
    private lateinit var recDot: View
    private lateinit var recordTime: TextView
    private lateinit var zoomBar: LinearLayout
    private lateinit var frontWideBar: View
    private lateinit var btnFrontNormal: ImageButton
    private lateinit var btnFrontWide: ImageButton
    private lateinit var modeBar: View
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var galleryThumb: ImageView
    private lateinit var btnSnapshot: ImageButton
    private lateinit var shutter: ShutterButton
    private lateinit var btnSwitch: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var flashOverlay: View

    // --- Caméra ---
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isPaused = false
    private lateinit var cameraExecutor: ExecutorService

    // --- Réglages ---
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode = Mode.PHOTO
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var ratio43 = true
    private var gridOn = false
    private var frontWide = true          // selfie grand angle par défaut
    private var mirrorSelfie = true       // enregistre le selfie tel qu'il apparaît à l'écran

    private var lastMediaUri: Uri? = null
    private var countdown: CountDownTimer? = null
    private val shutterSound = MediaActionSound()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var orientationListener: OrientationEventListener? = null
    private var currentRotation = Surface.ROTATION_0
    private val zoomChips = mutableListOf<Pair<Float, TextView>>()
    private val prefs by lazy { getSharedPreferences("s24camera", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // =====================================================================
    // Cycle de vie
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        bindViews()
        loadPrefs()
        setupListeners()
        refreshTopBar()
        refreshModeUi()

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != currentRotation) {
                    currentRotation = rotation
                    imageCapture?.targetRotation = rotation
                    if (recording == null) videoCapture?.targetRotation = rotation
                    rotateIcons(rotation)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        refreshLastMedia()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        countdown?.cancel()
        countdownText.visibility = View.GONE
        recording?.stop()
        savePrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shutterSound.release()
    }

    /** Touches volume = déclencheur, comme sur l'appli Samsung. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) onShutterPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // =====================================================================
    // Initialisation UI
    // =====================================================================

    private fun bindViews() {
        previewContainer = findViewById(R.id.previewContainer)
        previewView = findViewById(R.id.previewView)
        gridOverlay = findViewById(R.id.gridOverlay)
        focusRing = findViewById(R.id.focusRing)
        countdownText = findViewById(R.id.countdownText)
        zoomLabel = findViewById(R.id.zoomLabel)
        topBar = findViewById(R.id.topBar)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnRatio = findViewById(R.id.btnRatio)
        btnGrid = findViewById(R.id.btnGrid)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        recDot = findViewById(R.id.recDot)
        recordTime = findViewById(R.id.recordTime)
        zoomBar = findViewById(R.id.zoomBar)
        frontWideBar = findViewById(R.id.frontWideBar)
        btnFrontNormal = findViewById(R.id.btnFrontNormal)
        btnFrontWide = findViewById(R.id.btnFrontWide)
        modeBar = findViewById(R.id.modeBar)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        galleryThumb = findViewById(R.id.galleryThumb)
        btnSnapshot = findViewById(R.id.btnSnapshot)
        shutter = findViewById(R.id.shutter)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnPause = findViewById(R.id.btnPause)
        flashOverlay = findViewById(R.id.flashOverlay)
        galleryThumb.clipToOutline = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        shutter.setOnClickListener { onShutterPressed() }
        btnSnapshot.setOnClickListener { takePhoto(duringVideo = true) }
        btnSwitch.setOnClickListener { switchCamera() }
        btnPause.setOnClickListener { togglePause() }
        galleryThumb.setOnClickListener { openGallery() }
        modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        modeVideo.setOnClickListener { setMode(Mode.VIDEO) }

        btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            applyFlash()
            refreshTopBar()
        }
        btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
            refreshTopBar()
        }
        btnRatio.setOnClickListener {
            if (mode == Mode.PHOTO) {
                ratio43 = !ratio43
                refreshTopBar()
                bindCamera()
            }
        }
        btnGrid.setOnClickListener {
            gridOn = !gridOn
            refreshTopBar()
        }
        btnFrontWide.setOnClickListener { setFrontWide(true) }
        btnFrontNormal.setOnClickListener { setFrontWide(false) }

        // Pincer pour zoomer, toucher pour la mise au point,
        // glisser gauche/droite = changer de mode, haut/bas = changer de caméra.
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zs = cam.cameraInfo.zoomState.value ?: return false
                    val target = (zs.zoomRatio * detector.scaleFactor)
                        .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                    return true
                }
            })
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || recording != null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val min = 120 * resources.displayMetrics.density
                if (abs(dx) > abs(dy) && abs(dx) > min) {
                    setMode(if (dx < 0) Mode.VIDEO else Mode.PHOTO)
                } else if (abs(dy) > min) {
                    switchCamera()
                }
                return true
            }
        })
        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    // =====================================================================
    // Caméra
    // =====================================================================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val photoAspect = if (ratio43) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val aspect = if (mode == Mode.VIDEO) AspectRatio.RATIO_16_9 else photoAspect
        setPreviewRatio(aspect)

        val aspectStrategy = if (aspect == AspectRatio.RATIO_4_3)
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build())
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // En mode photo : pleine résolution. En vidéo : capture rapide (photo pendant le film).
        val ic = ImageCapture.Builder()
            .setCaptureMode(
                if (mode == Mode.PHOTO) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectStrategy)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setFlashMode(if (lensFacing == CameraSelector.LENS_FACING_FRONT) ImageCapture.FLASH_MODE_OFF else flashMode)
            .setTargetRotation(currentRotation)
            .build()

        val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            widestFrontSelector(provider) else CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        imageCapture = null
        videoCapture = null
        try {
            if (mode == Mode.PHOTO) {
                camera = provider.bindToLifecycle(this, selector, preview, ic)
                imageCapture = ic
            } else {
                // Résolution vidéo maximale proposée par la caméra en cours
                // (8K si le téléphone l'expose aux applis, sinon 4K, etc.).
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HIGHEST,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                        )
                    )
                    .build()
                val vc = VideoCapture.Builder(recorder)
                    .setTargetRotation(currentRotation)
                    .build()
                videoCapture = vc
                camera = try {
                    // Aperçu + Vidéo + Photo simultanés (supporté par le S24 Ultra)
                    provider.bindToLifecycle(this, selector, preview, vc, ic).also { imageCapture = ic }
                } catch (e: IllegalArgumentException) {
                    // Combinaison refusée : on garde la vidéo, la photo se fera depuis l'aperçu.
                    Log.w(TAG, "Vidéo + photo simultanées non supportées, mode de secours", e)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, vc)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'ouverture de la caméra", e)
            Toast.makeText(this, R.string.error_camera, Toast.LENGTH_LONG).show()
            return
        }
        onCameraBound()
    }

    /**
     * Le S24 Ultra n'a qu'un capteur frontal, mais on choisit quand même la caméra frontale
     * au champ le plus large si le téléphone en expose plusieurs. Le « grand angle » se fait
     * ensuite en utilisant tout le capteur (zoom minimum) au lieu du recadrage par défaut.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun widestFrontSelector(provider: ProcessCameraProvider): CameraSelector {
        val fronts = provider.availableCameraInfos.filter {
            it.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        if (fronts.size <= 1) return CameraSelector.DEFAULT_FRONT_CAMERA
        val widestId = fronts.maxByOrNull { horizontalFov(it) }
            ?.let { Camera2CameraInfo.from(it).cameraId }
            ?: return CameraSelector.DEFAULT_FRONT_CAMERA
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == widestId }
                    .ifEmpty { infos }
            }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun horizontalFov(info: CameraInfo): Double {
        val c2 = Camera2CameraInfo.from(info)
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return 0.0
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return 0.0
        return 2 * atan(size.width / (2.0 * focal))
    }

    private fun onCameraBound() {
        val cam = camera ?: return
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        zoomBar.visibility = if (isFront) View.GONE else View.VISIBLE
        frontWideBar.visibility = if (isFront) View.VISIBLE else View.GONE
        btnFlash.alpha = 1f

        if (isFront) applyFrontZoom() else buildZoomChips()
        refreshTopBar()

        cam.cameraInfo.zoomState.removeObservers(this)
        cam.cameraInfo.zoomState.observe(this) { zs ->
            if (!isFront) updateZoomChips(zs.zoomRatio)
        }
        // Le zoom ne s'applique qu'une fois la caméra ouverte : on réapplique le grand angle à ce moment-là.
        cam.cameraInfo.cameraState.removeObservers(this)
        cam.cameraInfo.cameraState.observe(this) { state ->
            if (isFront && state.type == CameraState.Type.OPEN) applyFrontZoom()
        }
    }

    // ---------------------------------------------------------------------
    // Zoom
    // ---------------------------------------------------------------------

    /** Raccourcis du S24 Ultra : 0.6x (ultra grand angle), 1x, 2x, 3x (télé), 5x (périscope), 10x. */
    private fun buildZoomChips() {
        val zs = camera?.cameraInfo?.zoomState?.value
        val min = zs?.minZoomRatio ?: 1f
        val max = zs?.maxZoomRatio ?: 10f
        val presets = listOf(0.6f, 1f, 2f, 3f, 5f, 10f)
            .map { if (it < 1f) min.coerceAtMost(1f) else it }
            .filter { it in min..max }
            .distinct()

        zoomBar.removeAllViews()
        zoomChips.clear()
        val size = dp(40)
        presets.forEach { value ->
            val chip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                }
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = formatZoom(value, withX = false)
                setOnClickListener { camera?.cameraControl?.setZoomRatio(value) }
            }
            zoomBar.addView(chip)
            zoomChips += value to chip
        }
        updateZoomChips(zs?.zoomRatio ?: 1f)
    }

    private fun updateZoomChips(ratio: Float) {
        if (zoomChips.isEmpty()) return
        // Le raccourci actif est le plus grand inférieur ou égal au zoom courant.
        val active = zoomChips.lastOrNull { ratio >= it.first - 0.01f } ?: zoomChips.first()
        zoomChips.forEach { (value, chip) ->
            val selected = chip === active.second
            chip.background = if (selected) ContextCompat.getDrawable(this, R.drawable.bg_chip_selected) else null
            chip.setTextColor(
                if (selected) ContextCompat.getColor(this, R.color.accent_yellow)
                else android.graphics.Color.WHITE
            )
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            chip.text = if (selected) formatZoom(ratio, withX = true) else formatZoom(value, withX = false)
        }
        showZoomLabel(ratio)
    }

    private var lastShownZoom = -1f
    private val hideZoomLabel = Runnable { zoomLabel.visibility = View.GONE }

    private fun showZoomLabel(ratio: Float) {
        if (lastShownZoom < 0f) { lastShownZoom = ratio; return }
        if (abs(ratio - lastShownZoom) < 0.01f) return
        lastShownZoom = ratio
        zoomLabel.text = formatZoom(ratio, withX = true)
        zoomLabel.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideZoomLabel)
        mainHandler.postDelayed(hideZoomLabel, 900)
    }

    private fun formatZoom(v: Float, withX: Boolean): String {
        val s = if (v < 10f && abs(v - Math.round(v)) > 0.05f)
            String.format(Locale.US, "%.1f", v) else Math.round(v).toString()
        return if (withX) "${s}x" else s
    }

    // ---------------------------------------------------------------------
    // Selfie grand angle
    // ---------------------------------------------------------------------

    private fun setFrontWide(wide: Boolean) {
        frontWide = wide
        applyFrontZoom()
    }

    /**
     * Grand angle = tout le champ du capteur frontal (zoom minimum).
     * Recadré = léger zoom (~1.25x), comme le cadrage « serré » par défaut de Samsung.
     */
    private fun applyFrontZoom() {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val min = zs?.minZoomRatio ?: 1f
        val max = zs?.maxZoomRatio ?: 1f
        val target = if (frontWide) min else (min * 1.25f).coerceAtMost(max)
        cam.cameraControl.setZoomRatio(target)
        btnFrontWide.background = if (frontWide) ContextCompat.getDrawable(this, R.drawable.bg_chip_selected) else null
        btnFrontNormal.background = if (!frontWide) ContextCompat.getDrawable(this, R.drawable.bg_chip_selected) else null
        btnFrontWide.alpha = if (frontWide) 1f else 0.6f
        btnFrontNormal.alpha = if (!frontWide) 1f else 0.6f
    }

    // ---------------------------------------------------------------------
    // Mise au point
    // ---------------------------------------------------------------------

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            scaleX = 1.4f; scaleY = 1.4f; alpha = 1f
            visibility = View.VISIBLE
            animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction {
                animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                    visibility = View.INVISIBLE
                }.start()
            }.start()
        }
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private fun onShutterPressed() {
        // Un appui pendant le compte à rebours l'annule.
        if (countdown != null) {
            countdown?.cancel(); countdown = null
            countdownText.visibility = View.GONE
            return
        }
        when (mode) {
            Mode.PHOTO -> withTimer { capturePhotoWithFlash() }
            Mode.VIDEO -> if (recording != null) recording?.stop() else withTimer { startRecording() }
        }
    }

    private fun withTimer(action: () -> Unit) {
        if (timerSeconds == 0) { action(); return }
        countdownText.visibility = View.VISIBLE
        countdown = object : CountDownTimer(timerSeconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                countdownText.text = ((ms + 999) / 1000).toString()
                countdownText.scaleX = 1.3f; countdownText.scaleY = 1.3f
                countdownText.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            }
            override fun onFinish() {
                countdown = null
                countdownText.visibility = View.GONE
                action()
            }
        }.start()
    }

    /** Selfie : flash d'écran blanc plein écran (comme Samsung), sinon capture directe. */
    private fun capturePhotoWithFlash() {
        val frontFlash = lensFacing == CameraSelector.LENS_FACING_FRONT &&
            flashMode != ImageCapture.FLASH_MODE_OFF
        if (!frontFlash) { takePhoto(duringVideo = false); return }

        val lp = window.attributes
        val oldBrightness = lp.screenBrightness
        lp.screenBrightness = 1f
        window.attributes = lp
        flashOverlay.alpha = 1f
        flashOverlay.visibility = View.VISIBLE
        mainHandler.postDelayed({
            takePhoto(duringVideo = false) {
                flashOverlay.visibility = View.GONE
                window.attributes = window.attributes.also { it.screenBrightness = oldBrightness }
            }
        }, 600)
    }

    private fun takePhoto(duringVideo: Boolean, onCaptured: (() -> Unit)? = null) {
        val ic = imageCapture
        if (ic == null) {
            // Secours : capture de l'image de l'aperçu
            snapshotFromPreview()
            onCaptured?.invoke()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName("IMG"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_DIR)
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorSelfie
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).setMetadata(metadata).build()

        ic.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                runOnUiThread {
                    if (!duringVideo) shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    blinkEffect()
                    onCaptured?.invoke()
                }
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    output.savedUri?.let { lastMediaUri = it; loadThumbnail(it) }
                    if (duringVideo) Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "Erreur photo", e)
                runOnUiThread {
                    onCaptured?.invoke()
                    Toast.makeText(this@MainActivity,
                        getString(R.string.error_capture, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun snapshotFromPreview() {
        val bmp = previewView.bitmap ?: return
        blinkEffect()
        cameraExecutor.execute {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName("IMG"))
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_DIR)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                runOnUiThread {
                    lastMediaUri = uri
                    loadThumbnail(uri)
                    Toast.makeText(this, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        vc.targetRotation = currentRotation
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName("VID"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_DIR)
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, output)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                    isPaused = false
                    setRecordingUi(true)
                }
                is VideoRecordEvent.Status -> updateRecordTime(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Pause -> { isPaused = true; refreshPauseUi() }
                is VideoRecordEvent.Resume -> { isPaused = false; refreshPauseUi() }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    setRecordingUi(false)
                    if (!event.hasError()) {
                        lastMediaUri = event.outputResults.outputUri
                        loadThumbnail(event.outputResults.outputUri)
                    } else {
                        Log.e(TAG, "Erreur vidéo ${event.error}", event.cause)
                        Toast.makeText(this, getString(R.string.error_capture, event.cause?.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun togglePause() {
        val rec = recording ?: return
        if (isPaused) rec.resume() else rec.pause()
    }

    private fun switchCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        btnSwitch.animate().rotationBy(180f).setDuration(300).start()
        lastShownZoom = -1f
        refreshTopBar()
        bindCamera()
    }

    private fun setMode(newMode: Mode) {
        if (newMode == mode || recording != null) return
        mode = newMode
        refreshModeUi()
        refreshTopBar()
        bindCamera()
    }

    private fun applyFlash() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) imageCapture?.flashMode = flashMode
    }

    // =====================================================================
    // UI
    // =====================================================================

    private fun setPreviewRatio(aspect: Int) {
        val lp = previewContainer.layoutParams as ConstraintLayout.LayoutParams
        lp.dimensionRatio = if (aspect == AspectRatio.RATIO_4_3) "3:4" else "9:16"
        lp.topMargin = if (aspect == AspectRatio.RATIO_4_3) dp(72) else dp(24)
        previewContainer.layoutParams = lp
    }

    private fun refreshModeUi() {
        val white = android.graphics.Color.WHITE
        val grey = ContextCompat.getColor(this, R.color.mode_inactive)
        modePhoto.setTextColor(if (mode == Mode.PHOTO) white else grey)
        modeVideo.setTextColor(if (mode == Mode.VIDEO) white else grey)
        shutter.state = if (mode == Mode.PHOTO) ShutterButton.State.PHOTO else ShutterButton.State.VIDEO
    }

    private fun refreshTopBar() {
        btnFlash.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                else -> R.drawable.ic_flash_off
            }
        )
        btnTimer.text = if (timerSeconds == 0) "" else "${timerSeconds}s"
        btnTimer.setTextColor(
            if (timerSeconds == 0) android.graphics.Color.WHITE
            else ContextCompat.getColor(this, R.color.accent_yellow)
        )
        btnRatio.text = if (mode == Mode.VIDEO) videoResolutionLabel() else if (ratio43) "3:4" else "9:16"
        btnRatio.alpha = 1f
        btnGrid.alpha = if (gridOn) 1f else 0.5f
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    /** Résolution vidéo réellement obtenue, ex. « 8K », « UHD », « FHD ». */
    private fun videoResolutionLabel(): String {
        val info = camera?.cameraInfo ?: return "UHD"
        val qualities = try {
            Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        } catch (e: Exception) { emptyList() }
        val res = qualities.mapNotNull { QualitySelector.getResolution(info, it) }
            .maxByOrNull { it.width.toLong() * it.height } ?: return "UHD"
        val long: Int = maxOf(res.width, res.height)
        return when {
            long >= 7680 -> "8K"
            long >= 3840 -> "UHD"
            long >= 1920 -> "FHD"
            long >= 1280 -> "HD"
            else -> "${minOf(res.width, res.height)}p"
        }
    }

    private fun setRecordingUi(rec: Boolean) {
        shutter.state = if (rec) ShutterButton.State.RECORDING else ShutterButton.State.VIDEO
        recordingIndicator.visibility = if (rec) View.VISIBLE else View.GONE
        topBar.visibility = if (rec) View.INVISIBLE else View.VISIBLE
        modeBar.visibility = if (rec) View.INVISIBLE else View.VISIBLE
        galleryThumb.visibility = if (rec) View.GONE else View.VISIBLE
        btnSwitch.visibility = if (rec) View.GONE else View.VISIBLE
        // Le bouton « photo pendant la vidéo » remplace la galerie, comme chez Samsung.
        btnSnapshot.visibility = if (rec) View.VISIBLE else View.GONE
        btnPause.visibility = if (rec) View.VISIBLE else View.GONE
        if (rec) { recordTime.text = "00:00"; refreshPauseUi() } else recDot.clearAnimation()
    }

    private fun refreshPauseUi() {
        btnPause.setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        recDot.alpha = if (isPaused) 0.3f else 1f
    }

    private fun updateRecordTime(nanos: Long) {
        val total = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        recordTime.text = if (h > 0) String.format(Locale.FRANCE, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.FRANCE, "%02d:%02d", m, s)
        if (!isPaused) recDot.alpha = if (total % 2 == 0L) 1f else 0.35f
    }

    private fun blinkEffect() {
        previewView.animate().alpha(0.2f).setDuration(60).withEndAction {
            previewView.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    private fun rotateIcons(rotation: Int) {
        val deg = when (rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
        listOf<View>(btnFlash, btnTimer, btnRatio, btnGrid, galleryThumb, btnSnapshot,
            btnPause, btnFrontWide, btnFrontNormal, countdownText, zoomLabel)
            .plus(zoomChips.map { it.second })
            .forEach { it.animate().rotation(deg).setDuration(250).start() }
    }

    // =====================================================================
    // Galerie
    // =====================================================================

    private fun loadThumbnail(uri: Uri) {
        cameraExecutor.execute {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } catch (e: Exception) { null }
            runOnUiThread { bmp?.let { galleryThumb.setImageBitmap(it) } }
        }
    }

    /** Retrouve la dernière photo/vidéo prise par l'appli (pas besoin de permission pour ses propres fichiers). */
    private fun refreshLastMedia() {
        cameraExecutor.execute {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            val uri = try {
                contentResolver.query(
                    collection, projection, selection, arrayOf("$SAVE_DIR%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    if (!c.moveToFirst()) null else {
                        val id = c.getLong(0)
                        val type = c.getInt(1)
                        val base = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        android.content.ContentUris.withAppendedId(base, id)
                    }
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                lastMediaUri = uri
                if (uri != null) loadThumbnail(uri) else galleryThumb.setImageDrawable(null)
            }
        }
    }

    private fun openGallery() {
        val uri = lastMediaUri
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        try { startActivity(intent) } catch (e: Exception) { Log.w(TAG, "Pas de galerie", e) }
    }

    // =====================================================================
    // Utilitaires
    // =====================================================================

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun fileName(prefix: String) =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadPrefs() {
        flashMode = prefs.getInt("flash", ImageCapture.FLASH_MODE_OFF)
        timerSeconds = prefs.getInt("timer", 0)
        ratio43 = prefs.getBoolean("ratio43", true)
        gridOn = prefs.getBoolean("grid", false)
        frontWide = prefs.getBoolean("frontWide", true)
        lensFacing = prefs.getInt("lens", CameraSelector.LENS_FACING_BACK)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("flash", flashMode)
            .putInt("timer", timerSeconds)
            .putBoolean("ratio43", ratio43)
            .putBoolean("grid", gridOn)
            .putBoolean("frontWide", frontWide)
            .putInt("lens", lensFacing)
            .apply()
    }

    companion object {
        private const val TAG = "S24Camera"
        private const val SAVE_DIR = "DCIM/S24Camera/"
    }
}
