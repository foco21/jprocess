/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reilandeubank.unprocess.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.withContext
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.hardware.camera2.params.MeteringRectangle
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.google.android.material.chip.Chip
import com.reilandeubank.unprocess.BuildConfig
import com.reilandeubank.unprocess.GlyphHelper
import com.reilandeubank.unprocess.MainActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.convertYUV420888toRGB
import com.reilandeubank.unprocess.utils.decodeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private lateinit var characteristics: CameraCharacteristics

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    // State variables
    private data class CameraInfo(
        val name: String,
        val cameraId: String,
        val supportedFormats: List<String>,
        val hasRawSupport: Boolean,
        val hasYuvSupport: Boolean,
        val isLogicalCamera: Boolean,
        val physicalCameraIds: Set<String> = emptySet()
    )

    private lateinit var availableLenses: List<CameraInfo>
    private var selectedLens: CameraInfo? = null
    private var selectedFormat: String? = null
    private var isWatermarkEnabled: Boolean = false
    private var currentZoom: Float = 1.0f
    private var telephotoZoom: Float = 1.0f
    private var preferredAspectRatio: String? = null
    private var currentAfRegions: Array<MeteringRectangle>? = null
    private var currentAeRegions: Array<MeteringRectangle>? = null
    private var glyphHelper: GlyphHelper? = null
    private var isGlyphEnabled = true
    private var isGridEnabled = false
    private var timerSeconds = 0
    private var countdownJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val container = view?.parent as? ViewGroup
        container?.let {
            val newView = onCreateView(layoutInflater.cloneInContext(requireContext()), it, null)
            it.removeView(view)
            it.addView(newView)
            onViewCreated(newView, null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            navController.navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (glyphHelper == null) {
            glyphHelper = GlyphHelper(requireContext()).also { it.init() }
        }

        // Programmatically set the colors for the zoom buttons to avoid inflation errors
        val colorSurface = com.google.android.material.R.attr.colorSurface
        val colorSecondaryContainer = com.google.android.material.R.attr.colorSecondaryContainer
        val colorOnSurface = com.google.android.material.R.attr.colorOnSurface
        val colorOnSecondaryContainer = com.google.android.material.R.attr.colorOnSecondaryContainer

        val ta = requireContext().obtainStyledAttributes(intArrayOf(colorSurface, colorSecondaryContainer, colorOnSurface, colorOnSecondaryContainer))
        val colorSurfaceValue = ta.getColor(0, 0)
        val colorSecondaryContainerValue = ta.getColor(1, 0)
        val colorOnSurfaceValue = ta.getColor(2, 0)
        val colorOnSecondaryContainerValue = ta.getColor(3, 0)
        ta.recycle()

        val backgroundColorStateList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                colorSecondaryContainerValue,
                Color.argb( (Color.alpha(colorSurfaceValue) * 0.8f).toInt(), Color.red(colorSurfaceValue), Color.green(colorSurfaceValue), Color.blue(colorSurfaceValue) )
            )
        )

        val textColorStateList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                colorOnSecondaryContainerValue,
                colorOnSurfaceValue
            )
        )

        fragmentCameraBinding.zoom1x.backgroundTintList = backgroundColorStateList
        fragmentCameraBinding.zoom1x.setTextColor(textColorStateList)
        fragmentCameraBinding.zoom2x?.backgroundTintList = backgroundColorStateList
        fragmentCameraBinding.zoom2x?.setTextColor(textColorStateList)


        val prefs = requireContext().getSharedPreferences("unprocess_prefs", Context.MODE_PRIVATE)
        val betaDialogShown = prefs.getBoolean("beta_dialog_shown", false)

        if (BuildConfig.BETA && !betaDialogShown) {
            AlertDialog.Builder(requireContext())
                .setTitle("Welcome")
                .setMessage("all the features might work but please report any errors to me make sure to specifiy the phone model!")
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    prefs.edit().putBoolean("beta_dialog_shown", true).apply()
                }
                .show()
        }

        fragmentCameraBinding.settingsButton.setOnClickListener {
            navController.navigate(R.id.action_camera_to_settings)
        }

        fragmentCameraBinding.galleryButton?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "No gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
        updateGalleryThumbnail()

        fragmentCameraBinding.timerButton?.setOnClickListener {
            countdownJob?.cancel()
            countdownJob = null
            hideCountdown()
            fragmentCameraBinding.captureButton.isEnabled = true
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateTimerLabel()
        }

        fragmentCameraBinding.zoomToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.zoom_1x -> setZoom(1.0f)
                    R.id.zoom_2x -> setZoom(telephotoZoom)

                }
            }
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                lifecycleScope.launch(Dispatchers.Main) {
                    setupUI()
                    initializeCamera()

                    val rawUnsupportedDialogShown = prefs.getBoolean("raw_unsupported_dialog_shown", false)
                    if (!availableLenses.any { it.hasRawSupport } && !rawUnsupportedDialogShown) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Compatibility Notice")
                            .setMessage("Not all features are available for your device. RAW capture is not supported.")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                prefs.edit().putBoolean("raw_unsupported_dialog_shown", true).apply()
                            }
                            .show()
                    }
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        availableLenses = enumerateCameras(cameraManager)

        val allSupportedFormats = availableLenses.flatMap { it.supportedFormats }.toSet()
        val prefs = requireContext().getSharedPreferences("unprocess_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("supported_formats", allSupportedFormats).apply()

        _fragmentCameraBinding?.lensChipGroup?.removeAllViews()
        availableLenses.forEach { lens ->
            _fragmentCameraBinding?.lensChipGroup?.let {
                val chip = Chip(requireContext()).apply {
                    text = lens.name
                    isCheckable = true
                    setOnClickListener { _ ->
                        if (selectedLens?.cameraId != lens.cameraId) {
                            selectedLens = lens
                            selectedFormat = null // Force re-selection of format
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (::camera.isInitialized) camera.close()
                                if (::imageReader.isInitialized) imageReader.close()
                                initializeCamera()
                            }
                        }
                    }
                }
                it.addView(chip)
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun enumerateCameras(cameraManager: CameraManager): List<CameraInfo> {
        val cameraInfoList = mutableListOf<CameraInfo>()

        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
            val outputFormats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

            val hasRawSupport = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val hasYuvSupport = outputFormats.contains(ImageFormat.YUV_420_888)
            val supportedFormats = mutableListOf<String>()
            if (hasRawSupport && outputFormats.contains(ImageFormat.RAW_SENSOR)) {
                supportedFormats.add("RAW")
            }
            if (hasYuvSupport) {
                supportedFormats.add("PNG")
            }
            if (outputFormats.contains(ImageFormat.JPEG)) {
                supportedFormats.add("JPEG")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && outputFormats.contains(ImageFormat.HEIC)) {
                supportedFormats.add("HEIC")
            }

            if (supportedFormats.isNotEmpty()) {
                val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                val isLogical = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                var lensName = when {
                    isLogical -> "Logical"
                    orientation == CameraCharacteristics.LENS_FACING_FRONT -> {
                        if (focalLengths != null && focalLengths.isNotEmpty() && focalLengths.minOrNull()!! < 2.2f) {
                            "Front Wide"
                        } else {
                            "Front"
                        }
                    }
                    orientation == CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    orientation == CameraCharacteristics.LENS_FACING_BACK -> {
                        if (focalLengths != null && focalLengths.isNotEmpty()) {
                            when {
                                focalLengths.minOrNull()!! < 3.0f -> "Ultra-Wide"
                                focalLengths.minOrNull()!! < 6.0f -> "Wide"
                                else -> "Telephoto"
                            }
                        } else {
                            "Back"
                        }
                    }
                    else -> "Unknown"
                }

                val physicalCameraIds = if (isLogical) characteristics.physicalCameraIds else emptySet()

                cameraInfoList.add(CameraInfo(lensName, id, supportedFormats.distinct(), hasRawSupport, hasYuvSupport, isLogical, physicalCameraIds))
            }
        }

        // Filter out physical cameras that are part of a logical camera,
        // but keep Ultra-Wide since it is usually not covered by the logical camera's switching range.
        val allPhysicalIds = cameraInfoList.filter { it.isLogicalCamera }.flatMap { it.physicalCameraIds }.toSet()
        val filtered = cameraInfoList.filter { camera ->
            camera.isLogicalCamera || camera.cameraId !in allPhysicalIds || camera.name == "Ultra-Wide"
        }
        // Deduplicate cameras with the same name (e.g. duplicate Front entries on Samsung)
        val seen = mutableSetOf<String>()
        return filtered.filter { seen.add(it.name) }
    }

    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        val prefs = requireContext().getSharedPreferences("unprocess_prefs", Context.MODE_PRIVATE)
        isWatermarkEnabled = prefs.getBoolean("watermark_enabled", false)
        isGlyphEnabled = prefs.getBoolean("glyph_enabled", true)
        isGridEnabled = prefs.getBoolean("grid_enabled", false)
        fragmentCameraBinding.gridOverlay?.visibility = if (isGridEnabled) View.VISIBLE else View.GONE
        selectedFormat = prefs.getString("preferred_format", "JPEG")
        preferredAspectRatio = prefs.getString("preferred_aspect_ratio", "9:16")
        val aspectRatio = when (preferredAspectRatio) {
            "3:4" -> 4f / 3f
            "9:16" -> 16f / 9f
            "1:1" -> 1f
            else -> null
        }


        if (selectedLens == null) {
            if (availableLenses.isNotEmpty()) {
                selectedLens = availableLenses[0]
            } else {
                Log.e(TAG, "No suitable cameras found.")
                return@launch
            }
        }

        if (selectedLens?.hasRawSupport == false && selectedFormat == "RAW") {
            selectedFormat = "JPEG"
        }
        if (selectedLens?.hasYuvSupport == false && selectedFormat == "PNG") {
            selectedFormat = "JPEG"
        }
        if (selectedLens?.supportedFormats?.contains("HEIC") != true && selectedFormat == "HEIC") {
            selectedFormat = "JPEG"
        }

        val cameraId = selectedLens!!.cameraId
        characteristics = cameraManager.getCameraCharacteristics(cameraId)

        _fragmentCameraBinding?.lensChipGroup?.let {
            for (i in 0 until it.childCount) {
                val chip = it.getChildAt(i) as Chip
                chip.isChecked = (chip.text == selectedLens?.name)
            }
        }

        // Determine the telephoto zoom factor for logical cameras
        if (selectedLens!!.name.contains("Telephoto") || selectedLens!!.isLogicalCamera) {
            var wideAngleFocalLength = -1f
            var telephotoFocalLength = -1f

            for (physicalId in selectedLens!!.physicalCameraIds) {
                val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                val focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focalLengths != null && focalLengths.isNotEmpty()) {
                    val minFocalLength = focalLengths.minByOrNull { it }!!
                    if (minFocalLength < 6.0f) { // Wide-angle lens
                        if (wideAngleFocalLength == -1f || minFocalLength < wideAngleFocalLength) {
                            wideAngleFocalLength = minFocalLength
                        }
                    } else { // Telephoto lens
                        if (telephotoFocalLength == -1f || minFocalLength > telephotoFocalLength) {
                            telephotoFocalLength = minFocalLength
                        }
                    }
                }
            }

            if (wideAngleFocalLength != -1f && telephotoFocalLength != -1f) {
                telephotoZoom = (telephotoFocalLength / wideAngleFocalLength)
                fragmentCameraBinding.zoomToggleGroup.visibility = View.VISIBLE
                fragmentCameraBinding.zoom2x?.text = "${telephotoZoom.roundToInt()}x"

            } else {
                fragmentCameraBinding.zoomToggleGroup.visibility = View.GONE
            }
        } else {
            fragmentCameraBinding.zoomToggleGroup.visibility = View.GONE
        }

        if (::relativeOrientation.isInitialized) {
            relativeOrientation.removeObservers(viewLifecycleOwner)
        }
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }

        val previewSize = getPreviewOutputSize(
            fragmentCameraBinding.viewFinder.display,
            characteristics,
            SurfaceHolder::class.java,
            aspectRatio = aspectRatio
        )
        fragmentCameraBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

        try {
            camera = openCamera(cameraManager, cameraId, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera $cameraId: ${e.message}")
            return@launch
        }

        var pixelFormat = when (selectedFormat) {
            "RAW" -> ImageFormat.RAW_SENSOR
            "PNG" -> if (selectedLens?.hasRawSupport == true) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
            "JPEG" -> ImageFormat.JPEG
            "HEIC" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ImageFormat.HEIC else { selectedFormat = "JPEG"; ImageFormat.JPEG }
            else -> {
                selectedFormat = "JPEG"
                ImageFormat.JPEG
            }
        }

        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val outputSizes = configMap.getOutputSizes(pixelFormat) ?: run {
            Log.w(TAG, "Format $pixelFormat not supported by this camera, falling back to JPEG")
            selectedFormat = "JPEG"
            pixelFormat = ImageFormat.JPEG
            configMap.getOutputSizes(ImageFormat.JPEG)!!
        }
        val size = if (aspectRatio != null) {
            outputSizes.filter { s ->
                val ratio = s.width.toFloat() / s.height.toFloat()
                (ratio - aspectRatio).let { kotlin.math.abs(it) < 0.01f }
            }.maxByOrNull { it.height * it.width }
        } else {
            outputSizes.maxByOrNull { it.height * it.width }
        } ?: outputSizes.maxByOrNull { it.height * it.width }!!

        imageReader = ImageReader.newInstance(size.width, size.height, pixelFormat, IMAGE_BUFFER_SIZE)

        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        session = createCaptureSession(camera, targets, cameraHandler)

        currentAfRegions = null
        currentAeRegions = null
        setZoom(1.0f)
        setupTouchGestures()

        (requireActivity() as? MainActivity)?.onVolumePressed = {
            _fragmentCameraBinding?.captureButton?.performClick()
        }

        fragmentCameraBinding.captureButton.setOnClickListener {
            it.isEnabled = false
            if (timerSeconds == 0) {
                triggerCapture { it.isEnabled = true }
            } else {
                startCountdown(timerSeconds) { triggerCapture { it.isEnabled = true } }
            }
        }
    }

    private fun triggerCapture(onDone: () -> Unit) {
        if (isGlyphEnabled) glyphHelper?.captureShutter()
        lifecycleScope.launch(Dispatchers.Main) {
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            val color = typedValue.data
            fragmentCameraBinding.overlay.setBackgroundColor(color)
            fragmentCameraBinding.overlay.alpha = 0.5f
            fragmentCameraBinding.overlay.visibility = View.VISIBLE
            fragmentCameraBinding.overlay.animate().alpha(0f).setDuration(250).withEndAction {
                fragmentCameraBinding.overlay.visibility = View.GONE
            }.start()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    takePhoto().use { result ->
                        Log.d(TAG, "Result received: $result")
                        saveResult(result)
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isGlyphEnabled) glyphHelper?.photoSavedPulse()
                            updateGalleryThumbnail()
                            val banner = fragmentCameraBinding.notificationBanner
                            banner.alpha = 0f
                            banner.visibility = View.VISIBLE
                            banner.animate()
                                .alpha(1f)
                                .setDuration(300)
                                .withEndAction {
                                    banner.postDelayed({
                                        banner.animate()
                                            .alpha(0f)
                                            .setDuration(300)
                                            .withEndAction { banner.visibility = View.GONE }
                                            .start()
                                    }, 2000)
                                }
                                .start()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking photo", e)
                } finally {
                    lifecycleScope.launch(Dispatchers.Main) { onDone() }
                }
            }
        }
    }

    private fun startCountdown(seconds: Int, onDone: () -> Unit) {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch(Dispatchers.Main) {
            for (i in seconds downTo 1) {
                showCountdown(i)
                if (isGlyphEnabled) glyphHelper?.countdownTick(i)
                delay(1000L)
                if (!isActive) {
                    hideCountdown()
                    return@launch
                }
            }
            hideCountdown()
            onDone()
        }
    }

    private fun showCountdown(n: Int) {
        fragmentCameraBinding.countdownText?.let {
            it.text = n.toString()
            it.visibility = View.VISIBLE
        }
    }

    private fun hideCountdown() {
        fragmentCameraBinding.countdownText?.visibility = View.GONE
    }

    private fun updateTimerLabel() {
        val lbl = fragmentCameraBinding.timerLabel ?: return
        val btn = fragmentCameraBinding.timerButton ?: return
        if (timerSeconds == 0) {
            lbl.visibility = View.GONE
            btn.alpha = 0.5f
        } else {
            lbl.text = "${timerSeconds}s"
            lbl.visibility = View.VISIBLE
            btn.alpha = 1.0f
        }
    }

    private fun updateGalleryThumbnail() {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                requireContext().contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null, sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    } else null
                }
            } ?: return@launch
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            _fragmentCameraBinding?.galleryButton?.let { btn ->
                btn.imageTintList = null
                btn.setPadding(0, 0, 0, 0)
                Glide.with(requireContext()).load(uri).centerCrop().into(btn)
            }
        }
    }

    private fun setZoom(zoom: Float) {
        currentZoom = zoom
        applyPreviewSettings()
    }

    private fun applyPreviewSettings() {
        if (!::session.isInitialized || !::camera.isInitialized || !::characteristics.isInitialized) return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(fragmentCameraBinding.viewFinder.holder.surface)

            val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom.coerceIn(zoomRange.lower, zoomRange.upper))
            } else {
                val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                val cx = rect.width() / 2
                val cy = rect.height() / 2
                currentZoom = currentZoom.coerceIn(1f, maxZoom)
                val dx = (0.5f * rect.width() / currentZoom).toInt()
                val dy = (0.5f * rect.height() / currentZoom).toInt()
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cx - dx, cy - dy, cx + dx, cy + dy))
            }

            val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxAfRegions > 0) currentAfRegions?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
            val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            if (maxAeRegions > 0) currentAeRegions?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, it) }

            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyPreviewSettings skipped: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!::characteristics.isInitialized) return false
                    val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    val minZ = zoomRange?.lower ?: 1f
                    val maxZ = zoomRange?.upper
                        ?: (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f)
                    setZoom((currentZoom * detector.scaleFactor).coerceIn(minZ, maxZ))
                    return true
                }
            })

        val tapDetector = GestureDetector(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    triggerFocusAt(e.x, e.y)
                    return true
                }
            })

        fragmentCameraBinding.viewFinder.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            true
        }
    }

    private fun triggerFocusAt(x: Float, y: Float) {
        if (!::session.isInitialized || !::camera.isInitialized || !::characteristics.isInitialized) return
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val vw = fragmentCameraBinding.viewFinder.width.toFloat().takeIf { it > 0 } ?: return
        val vh = fragmentCameraBinding.viewFinder.height.toFloat().takeIf { it > 0 } ?: return

        val sx = (x / vw * sensorSize.width()).toInt().coerceIn(0, sensorSize.width() - 1)
        val sy = (y / vh * sensorSize.height()).toInt().coerceIn(0, sensorSize.height() - 1)
        val half = (sensorSize.width() * 0.08f).toInt()
        val left = (sx - half).coerceAtLeast(0)
        val top = (sy - half).coerceAtLeast(0)
        val right = (sx + half).coerceAtMost(sensorSize.width())
        val bottom = (sy + half).coerceAtMost(sensorSize.height())
        val region = MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX)

        currentAfRegions = arrayOf(region)
        currentAeRegions = arrayOf(region)

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxAfRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            if (maxAeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom.coerceIn(zoomRange.lower, zoomRange.upper))
            }
            session.capture(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "triggerFocusAt capture skipped: ${e.message}")
            currentAfRegions = null
            currentAeRegions = null
        }

        showFocusRing(x, y)

        lifecycleScope.launch {
            delay(3000)
            currentAfRegions = null
            currentAeRegions = null
            applyPreviewSettings()
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = _fragmentCameraBinding?.focusRing ?: return
        val size = (72 * resources.displayMetrics.density)
        ring.x = x - size / 2f
        ring.y = y - size / 2f
        ring.alpha = 1f
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.visibility = View.VISIBLE
        ring.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(180)
            .withEndAction {
                ring.postDelayed({
                    ring.animate().alpha(0f).setDuration(250)
                        .withEndAction { ring.visibility = View.GONE }.start()
                }, 1200)
            }.start()
    }


    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) = cont.resume(device)
                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera $cameraId has been disconnected")
                    requireActivity().finish()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val msg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Device policy"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)
                    if (cont.isActive) cont.resumeWithException(exc)
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera $cameraId: ${e.message}")
            cont.resumeWithException(e)
        }
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun takePhoto(): CombinedCaptureResult = suspendCancellableCoroutine { cont ->
        while (imageReader.acquireNextImage() != null) {}

        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            .apply {
                addTarget(imageReader.surface)
                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
                } else {
                    val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                    val centerX = rect.width() / 2
                    val centerY = rect.height() / 2
                    val deltaX = (0.5f * rect.width() / currentZoom).toInt()
                    val deltaY = (0.5f * rect.height() / currentZoom).toInt()
                    val crop = Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
                    set(CaptureRequest.SCALER_CROP_REGION, crop)
                }
            }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                lifecycleScope.launch(cont.context) {
                    while (true) {
                        val image = imageQueue.take()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue

                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        cont.resume(
                            CombinedCaptureResult(
                                image,
                                result,
                                exifOrientation,
                                imageReader.imageFormat
                            )
                        )
                    }
                }
            }
        }, cameraHandler)
    }

    private fun addWatermark(bitmap: Bitmap): Bitmap {
        val newBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(newBitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            isAntiAlias = true
        }
        val text = "${Build.MODEL} - ${selectedLens?.name} - $selectedFormat - $preferredAspectRatio"
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText(text, 20f, newBitmap.height - 80f, paint)
        canvas.drawText(dateText, 20f, newBitmap.height - 30f, paint)
        return newBitmap
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        val outputFormat = selectedFormat ?: run {
            cont.resumeWithException(RuntimeException("No output format selected"))
            return@suspendCoroutine
        }

        var bitmap: Bitmap? = null

        // Get the image data
        when (result.format) {
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                if (outputFormat == "RAW") {
                    dngCreator.setOrientation(result.orientation)
                    val filename = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.dng"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = android.content.ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                        }
                        val resolver = requireContext().contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            ?: throw IOException("Failed to create MediaStore entry")
                        resolver.openOutputStream(uri)?.use { stream -> dngCreator.writeImage(stream, result.image) }
                        cont.resume(File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"), filename))
                    } else {
                        val file = File(
                            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() },
                            filename
                        )
                        FileOutputStream(file).use { outputStream -> dngCreator.writeImage(outputStream, result.image) }
                        cont.resume(file)
                    }
                    return@suspendCoroutine
                } else {
                    val tempDngFile = File(requireContext().cacheDir, "temp.dng")
                    FileOutputStream(tempDngFile).use { outputStream ->
                        dngCreator.writeImage(outputStream, result.image)
                    }
                    bitmap = BitmapFactory.decodeFile(tempDngFile.absolutePath)
                    tempDngFile.delete()
                }
            }
            ImageFormat.YUV_420_888 -> {
                bitmap = convertYUV420888toRGB(result.image)
            }
            ImageFormat.JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.HEIC -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.heic"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/heic")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    }
                    val resolver = requireContext().contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create MediaStore entry")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    cont.resume(File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"), filename))
                } else {
                    val file = File(
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() },
                        filename
                    )
                    FileOutputStream(file).use { it.write(bytes) }
                    cont.resume(file)
                }
                return@suspendCoroutine
            }
            else -> cont.resumeWithException(RuntimeException("Unknown image format: ${result.image.format}"))
        }

        if (bitmap != null) {
            val matrix = decodeExifOrientation(result.orientation)
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            if (isWatermarkEnabled) {
                bitmap = addWatermark(rotatedBitmap)
            } else {
                bitmap = rotatedBitmap
            }

            val (extension, mimeType, format) = when (outputFormat) {
                "JPEG" -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
                "PNG" -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
                else -> throw RuntimeException("Unsupported format for bitmap saving")
            }
            val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$extension"

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(format, 100, outputStream)
            val finalBytes = outputStream.toByteArray()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                }
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry")
                resolver.openOutputStream(uri)?.use { it.write(finalBytes) }

                if (outputFormat == "JPEG") {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        ExifInterface(pfd.fileDescriptor).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                            saveAttributes()
                        }
                    }
                }

                cont.resume(File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"), filename))
            } else {
                val file = File(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() },
                    filename
                )
                FileOutputStream(file).use { it.write(finalBytes) }
                if (outputFormat == "JPEG") {
                    ExifInterface(file.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        saveAttributes()
                    }
                }
                cont.resume(file)
            }
            bitmap.recycle()
        }
    }


    override fun onStop() {
        super.onStop()
        try {
            if (::camera.isInitialized) {
                camera.close()
            }
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        try { (activity as? MainActivity)?.onVolumePressed = null } catch (e: Exception) { /* detached */ }
        countdownJob?.cancel()
        countdownJob = null
        glyphHelper?.cleanup()
        glyphHelper = null
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName
        private const val IMAGE_BUFFER_SIZE: Int = 3
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }
    }
}
