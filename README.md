![logo-sm](https://github.com/foco21/jprocess/blob/main/uncompress_J.png)

junprocess
===========================

This project is based on https://github.com/android/camera-samples/tree/main/Camera2Basic and is a
simple, open source solution to taking unprocessed images on your Android phone, free of
modern devices' excessive computational photography.

Introduction
------------

unprocess uses the [Camera2 API][1] to capture raw sensor data from the camera before being
converted to a human-viewable file format.

The app now allows users to select individual physical cameras (wide, telephoto, ultra-wide, etc.) from a list and switch between them directly on the camera screen. Users can choose between RAW (.dng), JPEG (.jpg), or PNG (.png) for the final, saved file, and can optionally enable a watermark with device and shot information. This ensures every photo is purely optical and unprocessed.

[1]: https://developer.android.com/reference/android/hardware/camera2/package-summary.html

Pre-requisites
--------------

- Android SDK 34+
- Android Studio 3.5+

Screenshots
-------------

None at the moment

Getting Started
---------------

This sample uses the Gradle build system. To build this project, use the
"gradlew build" command or use "Import Project" in Android Studio.

Support
-------

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub.

This is my first foray into Android app development, so the project may start a little rough
around the edges. If there is community interest, I will do my best to keep it going and
take in any improvements that are able to be contributed. 
