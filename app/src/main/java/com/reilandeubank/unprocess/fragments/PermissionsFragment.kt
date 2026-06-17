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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.reilandeubank.unprocess.R
import kotlinx.coroutines.launch

private const val PERMISSIONS_REQUEST_CODE = 10

/**
 * CAMERA is the only hard requirement (plus legacy storage-write on API <= 28, which is needed
 * to save files before scoped storage). On API 29+ photos save via MediaStore with no storage
 * permission at all.
 */
private val PERMISSIONS_REQUIRED = mutableListOf(Manifest.permission.CAMERA).apply {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}.toTypedArray()

/**
 * Media-read permissions are OPTIONAL — they only power the in-app gallery thumbnail. Denying
 * them must NOT close the app; capture and saving still work without them on modern Android.
 */
private val PERMISSIONS_OPTIONAL = mutableListOf<String>().apply {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
}.toTypedArray()

private val PERMISSIONS_ALL = PERMISSIONS_REQUIRED + PERMISSIONS_OPTIONAL

/**
 * This [Fragment] requests permissions and, once the required ones are granted, navigates on.
 */
class PermissionsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasPermissions(requireContext())) {
            // Request camera (required) and media (optional) permissions together
            requestPermissions(PERMISSIONS_ALL, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions(requireContext())) {
            navigateToCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) return

        val deniedRequired = permissions.zip(grantResults.toList())
            .any { (perm, result) ->
                perm in PERMISSIONS_REQUIRED && result != PackageManager.PERMISSION_GRANTED
            }

        if (!deniedRequired && hasPermissions(requireContext())) {
            // Camera granted — proceed even if optional photo access was declined
            navigateToCamera()
        } else {
            Toast.makeText(
                context,
                "Camera permission is required to use JuneProcess",
                Toast.LENGTH_LONG
            ).show()
            requireActivity().finish()
        }
    }

    private fun navigateToCamera() {
        lifecycleScope.launch {
            if (findNavController().currentDestination?.id == R.id.permissions_fragment) {
                findNavController().navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
            }
        }
    }

    companion object {
        /** True once all REQUIRED permissions (camera, + legacy storage on old APIs) are granted. */
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
