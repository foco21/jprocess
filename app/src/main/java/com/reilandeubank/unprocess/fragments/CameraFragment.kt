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
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
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
        fragmentCameraBinding.zoom4x?.backgroundTintList = backgroundColorStateList
        fragmentCameraBinding.zoom4x?.setTextColor(textColorStateList)
        fragmentCameraBinding.lensFront?.backgroundTintList = backgroundColorStateList
        fragmentCameraBinding.lensFront?.setTextColor(textColorStateList)
        fragmentCameraBinding.lensLogical?.backgroundTintList = backgroundColorStateList
        fragmentCameraBinding.lensLogical?.setTextColor(textColorStateList)


        val prefs = requireContext().getSharedPreferences("unprocess_prefs", Context.MODE_PRIVATE)
        val betaDialogShown = prefs.getBoolean("beta_dialog_shown", false)

        if (BuildConfig.BETA && !betaDialogShown) {
            AlertDialog.Builder(requireContext())
                .setTitle("Beta Version")
                .setMessage("this is a beta version of an overhaul of an update. now all the features might work but please report any errors to me make sure to specifiy the phone model!")
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
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivity(intent)
        }

        fragmentCameraBinding.zoomToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.zoom_1x -> setZoom(1.0f)
                    R.id.zoom_2x -> setZoom(telephotoZoom)
                    R.id.zoom_4x -> setZoom(telephotoZoom)
                }
            }
        }

        fragmentCameraBinding.lensToggleGroup?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val lens = when (checkedId) {
                    R.id.lens_front -> availableLenses.find { it.name == "Front" }
                    R.id.lens_logical -> availableLenses.find { it.name == "Logical" }
                    else -> null
                }
                if (lens != null && selectedLens?.cameraId != lens.cameraId) {
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
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        availableLenses = enumerateCameras(cameraManager)

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

        _fragmentCameraBinding?.lensFront?.visibility = if (availableLenses.any { it.name == "Front" }) View.VISIBLE else View.GONE
        _fragmentCameraBinding?.lensLogical?.visibility = if (availableLenses.any { it.name == "Logical" }) View.VISIBLE else View.GONE
    }

    @SuppressLint("InlinedApi")
    private fun enumerateCameras(cameraManager: CameraManager): List<CameraInfo> {
        val cameraInfoList = mutableListOf<CameraInfo>()

        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
            val outputFormats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

            val supportedFormats = mutableListOf<String>()
            if (outputFormats.contains(ImageFormat.RAW_SENSOR)) {
                supportedFormats.add("RAW")
                supportedFormats.add("PNG") // PNG is derived from RAW
            }
            if (outputFormats.contains(ImageFormat.JPEG)) {
                supportedFormats.add("JPEG")
            }

            if (supportedFormats.isNotEmpty()) {
                val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                val isLogical = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                var lensName = when {
                    isLogical -> "Logical"
                    orientation == CameraCharacteristics.LENS_FACING_FRONT -> "Front"
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

                cameraInfoList.add(CameraInfo(lensName, id, supportedFormats.distinct(), isLogical, physicalCameraIds))
            }
        }

        // Filter out physical cameras that are part of a logical camera
        val allPhysicalIds = cameraInfoList.filter { it.isLogicalCamera }.flatMap { it.physicalCameraIds }.toSet()
        return cameraInfoList.filter { it.isLogicalCamera || it.cameraId !in allPhysicalIds }
    }

    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        val prefs = requireContext().getSharedPreferences("unprocess_prefs", Context.MODE_PRIVATE)
        isWatermarkEnabled = prefs.getBoolean("watermark_enabled", false)
        selectedFormat = prefs.getString("preferred_format", "JPEG")
        preferredAspectRatio = prefs.getString("preferred_aspect_ratio", "3:4")
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
        val cameraId = selectedLens!!.cameraId
        characteristics = cameraManager.getCameraCharacteristics(cameraId)

        _fragmentCameraBinding?.lensChipGroup?.let {
            for (i in 0 until it.childCount) {
                val chip = it.getChildAt(i) as Chip
                chip.isChecked = (chip.text == selectedLens?.name)
            }
        }

        _fragmentCameraBinding?.lensToggleGroup?.let {
            if (selectedLens?.name == "Front") {
                it.check(R.id.lens_front)
            } else if (selectedLens?.name == "Logical") {
                it.check(R.id.lens_logical)
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
                fragmentCameraBinding.zoom4x?.text = "${telephotoZoom.roundToInt()}x"
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

        val pixelFormat = when (selectedFormat) {
            "RAW", "PNG" -> ImageFormat.RAW_SENSOR
            "JPEG" -> ImageFormat.JPEG
            else -> {
                // Default to JPEG if no format is selected
                selectedFormat = "JPEG"
                ImageFormat.JPEG
            }
        }

        val outputSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(pixelFormat)
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

        setZoom(1.0f)

        fragmentCameraBinding.captureButton.setOnClickListener {
            it.isEnabled = false
            lifecycleScope.launch(Dispatchers.Main) {
                // Flash the screen to indicate that a photo is being taken
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
                        it.post { it.isEnabled = true }
                    }
                }
            }
        }
    }

    private fun setZoom(zoom: Float) {
        if (!::session.isInitialized) return

        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(fragmentCameraBinding.viewFinder.holder.surface)

        val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (zoomRange != null) {
            currentZoom = zoom.coerceIn(zoomRange.lower, zoomRange.upper)
            captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
        } else {
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val centerX = rect.width() / 2
            val centerY = rect.height() / 2
            currentZoom = zoom.coerceIn(1f, maxZoom)
            val deltaX = (0.5f * rect.width() / currentZoom).toInt()
            val deltaY = (0.5f * rect.height() / currentZoom).toInt()
            val crop = Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop)
        }

        session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
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

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        val outputFormat = selectedFormat ?: run {
            cont.resumeWithException(RuntimeException("No output format selected"))
            return@suspendCoroutine
        }
        var orientation = result.orientation

        var bitmap: Bitmap? = null

        // Get the image data
        when (result.format) {
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                if (outputFormat == "RAW") {
                    dngCreator.setOrientation(orientation)
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
            ImageFormat.JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> cont.resumeWithException(RuntimeException("Unknown image format: ${result.image.format}"))
        }

        if (bitmap != null) {
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                bitmap = rotateBitmap(bitmap, rotationDegrees)
                orientation = ExifInterface.ORIENTATION_NORMAL
            }

            if (isWatermarkEnabled) {
                bitmap = addWatermark(bitmap)
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
                            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
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
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
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
