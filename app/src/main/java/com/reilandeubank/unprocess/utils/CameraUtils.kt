package com.reilandeubank.unprocess.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Gathers all back-facing cameras that are logical cameras and returns a map of their
 * physical camera IDs to their respective zoom ratios.
 */
fun getLogicalCameraZoomRatios(context: Context): Map<String, FloatArray> {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val logicalCameraZoomRatios = mutableMapOf<String, FloatArray>()

    // Iterate over all available cameras
    cameraManager.cameraIdList.forEach { cameraId ->
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

        // Check if it's a back-facing logical camera
        if (capabilities != null &&
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
            lensFacing == CameraCharacteristics.LENS_FACING_BACK
        ) {
            // Get the physical cameras that make up this logical camera
            val physicalCameraIds = characteristics.physicalCameraIds

            if (physicalCameraIds.isNotEmpty()) {
                // Find the focal length of the widest-angle camera to use as the base for zoom ratio calculations.
                // A smaller focal length corresponds to a wider field of view.
                val baseFocalLength = physicalCameraIds.minOf { physicalId ->
                    val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                    physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: Float.MAX_VALUE
                }

                // Calculate the zoom ratio for each physical camera relative to the widest one.
                val zoomRatios = physicalCameraIds.map { physicalId ->
                    val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                    val focalLength = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: baseFocalLength
                    focalLength / baseFocalLength
                }.toFloatArray()

                logicalCameraZoomRatios[cameraId] = zoomRatios
            }
        }
    }
    return logicalCameraZoomRatios
}
