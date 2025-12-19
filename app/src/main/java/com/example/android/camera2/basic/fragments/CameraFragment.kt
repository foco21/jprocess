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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.reilandeubank.unprocess.CameraActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

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
    private data class CameraInfo(val name: String, val cameraId: String)
    private lateinit var availableLenses: List<CameraInfo>
    private val outputFormats = listOf("RAW", "JPEG", "PNG")
    private var selectedLens: CameraInfo? = null
    private var selectedFormat: String = outputFormats[0]

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceCreated(holder: SurfaceHolder) {
                lifecycleScope.launch(Dispatchers.Main) {
                    setupSpinners()
                    initializeCamera()
                }
            }
        })

        // The switch camera button is now gone
        fragmentCameraBinding.switchCameraButton.visibility = View.GONE
    }

    private fun setupSpinners() {
        availableLenses = enumerateCameras(cameraManager)
        val lensNames = availableLenses.map { it.name }

        // Setup lens spinner
        val lensAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, lensNames)
        lensAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fragmentCameraBinding.lensSpinner.adapter = lensAdapter
        fragmentCameraBinding.lensSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLens = availableLenses[position]
                lifecycleScope.launch(Dispatchers.Main) {
                    if (::camera.isInitialized) camera.close()
                    if (::imageReader.isInitialized) imageReader.close()
                    initializeCamera()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup format spinner
        val formatAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, outputFormats)
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fragmentCameraBinding.formatSpinner.adapter = formatAdapter
        fragmentCameraBinding.formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFormat = outputFormats[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("InlinedApi")
    private fun enumerateCameras(cameraManager: CameraManager): List<CameraInfo> {
        val cameraInfoList = mutableListOf<CameraInfo>()
        cameraManager.cameraIdList.filter {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
        }.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
            val outputFormats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

            if (orientation == CameraCharacteristics.LENS_FACING_BACK &&
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                outputFormats.contains(ImageFormat.RAW_SENSOR)) {
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val lensType = if (focalLengths != null && focalLengths.isNotEmpty()) {
                    when {
                        focalLengths.minOrNull()!! < 3.0f -> "Ultra-Wide"
                        focalLengths.minOrNull()!! < 6.0f -> "Wide"
                        else -> "Telephoto"
                    }
                } else {
                    "Back"
                }
                cameraInfoList.add(CameraInfo(lensType, id))
            }
        }
        if (cameraInfoList.isEmpty()) {
            // Fallback to first compatible back camera if no specific physical lens found
            val fallbackId = cameraManager.cameraIdList.firstOrNull {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (fallbackId != null) {
                cameraInfoList.add(CameraInfo("Back Camera", fallbackId))
            }
        }
        return cameraInfoList
    }

    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
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
            SurfaceHolder::class.java
        )
        fragmentCameraBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)

        camera = openCamera(cameraManager, cameraId, cameraHandler)

        val pixelFormat = ImageFormat.RAW_SENSOR
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(size.width, size.height, pixelFormat, IMAGE_BUFFER_SIZE)

        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }

        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        fragmentCameraBinding.captureButton.setOnClickListener {
            it.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(
                            CameraFragmentDirections.actionCameraToJpegViewer(output.absolutePath)
                                .setOrientation(result.orientation)
                                .setDepth(result.format == ImageFormat.DEPTH_JPEG)
                        )
                    }
                }
                it.post { it.isEnabled = true }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
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

    private suspend fun takePhoto(): CombinedCaptureResult = suspendCoroutine { cont ->
        while (imageReader.acquireNextImage() != null) {}

        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            .apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
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
                            image.timestamp != resultTimestamp) continue

                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)
                        while (imageQueue.size > 0) { imageQueue.take().close() }

                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        cont.resume(CombinedCaptureResult(image, result, exifOrientation, imageReader.imageFormat))
                    }
                }
            }
        }, cameraHandler)
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    when (selectedFormat) {
                        "JPEG", "PNG" -> {
                            val rawImage = result.image
                            // Create a temporary DNG file for bitmap conversion
                            val tempDngFile = File(requireContext().cacheDir, "temp.dng")
                            FileOutputStream(tempDngFile).use { outputStream ->
                                dngCreator.writeImage(outputStream, rawImage)
                            }

                            val bitmap = BitmapFactory.decodeFile(tempDngFile.absolutePath)
                            tempDngFile.delete()

                            val (extension, mimeType, format) = when (selectedFormat) {
                                "JPEG" -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
                                "PNG" -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
                                else -> throw RuntimeException("Unknown format")
                            }
                            val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$extension"

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val contentValues = android.content.ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                                }
                                val resolver = requireContext().contentResolver
                                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                    ?: throw IOException("Failed to create MediaStore entry")
                                resolver.openOutputStream(uri)?.use { stream -> bitmap.compress(format, 100, stream) }

                                if (selectedFormat == "JPEG") {
                                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                        ExifInterface(pfd.fileDescriptor).apply {
                                            setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                            saveAttributes()
                                        }
                                    }
                                }
                                cont.resume(File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"), filename))
                            } else {
                                val file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }, filename)
                                FileOutputStream(file).use { stream -> bitmap.compress(format, 100, stream) }
                                if (selectedFormat == "JPEG") {
                                    ExifInterface(file.absolutePath).apply {
                                        setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                        saveAttributes()
                                    }
                                }
                                cont.resume(file)
                            }
                            bitmap.recycle()
                        }
                        "RAW" -> {
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
                                val file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }, filename)
                                FileOutputStream(file).use { outputStream -> dngCreator.writeImage(outputStream, result.image) }
                                cont.resume(file)
                            }
                        }
                    }
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write image to external storage", exc)
                    cont.resumeWithException(exc)
                }
            }
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::camera.isInitialized) { camera.close() }
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
