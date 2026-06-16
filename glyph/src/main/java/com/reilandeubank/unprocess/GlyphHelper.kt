package com.reilandeubank.unprocess

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import kotlin.math.cos
import kotlin.math.sin

class GlyphHelper(private val context: Context) {

    private var manager: GlyphMatrixManager? = null
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            try {
                manager?.register(Glyph.DEVICE_25111p)
                isConnected = true
                Log.d(TAG, "Glyph Matrix connected")
            } catch (e: Exception) {
                Log.w(TAG, "Glyph register failed: ${e.message}")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isConnected = false
        }
    }

    fun init() {
        try {
            manager = GlyphMatrixManager.getInstance(context)
            manager?.init(callback)
        } catch (e: Throwable) {
            Log.d(TAG, "Glyph not available: ${e.message}")
        }
    }

    fun captureShutter() {
        if (!isConnected) return
        try {
            handler.removeCallbacksAndMessages(null)

            at(0)   { irisFrame(open = 0.75f, rot = 0f,   br = 100) }
            at(60)  { irisFrame(open = 0.52f, rot = 18f,  br = 120) }
            at(120) { irisFrame(open = 0.28f, rot = 36f,  br = 145) }
            at(175) { irisFrame(open = 0.08f, rot = 54f,  br = 160) }
            at(220) { irisFrame(open = 0.00f, rot = 72f,  br = 160) }
            at(310) { irisFrame(open = 0.30f, rot = 88f,  br = 130) }
            at(360) { irisFrame(open = 0.62f, rot = 106f, br = 85)  }
            at(410) { quietOff() }
        } catch (e: Exception) {
            Log.w(TAG, "captureShutter: ${e.message}")
        }
    }

    fun countdownTick(secondsLeft: Int) {
        if (!isConnected) return
        try {
            handler.removeCallbacksAndMessages(null)
            val bmp = drawDigitBitmap(secondsLeft)
            val frame = GlyphMatrixFrame.Builder()
                .addTop(makeObj(bmp, 120))
                .addMid(makeObj(bmp, 120))
                .addLow(makeObj(bmp, 120))
                .build(context)
            manager?.setMatrixFrame(frame.render())
            at(900) { quietOff() }
        } catch (e: Exception) {
            Log.w(TAG, "countdownTick: ${e.message}")
        }
    }

    fun photoSavedPulse() {
        if (!isConnected) return
        try {
            handler.removeCallbacksAndMessages(null)
            irisFrame(open = 0.4f, rot = 0f, br = 100)
            at(350) { quietOff() }
        } catch (e: Exception) {
            Log.w(TAG, "photoSavedPulse: ${e.message}")
        }
    }



    fun cleanup() {
        try {
            handler.removeCallbacksAndMessages(null)
            quietOff()
            manager?.unInit()
        } catch (e: Exception) { }
        manager = null
        isConnected = false
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private fun irisFrame(open: Float, rot: Float, br: Int) {
        val full = drawIrisBitmap(open, rot)
        try {
            val frame = GlyphMatrixFrame.Builder()
                .addTop(makeObj(full, br))
                .addMid(makeObj(full, br))
                .addLow(makeObj(full, br))
                .build(context)
            manager?.setMatrixFrame(frame.render())
        } catch (e: Exception) {
            Log.e(TAG, "irisFrame failed", e)
        }
    }

    private fun drawIrisBitmap(open: Float, rot: Float): Bitmap {
        val bmp = Bitmap.createBitmap(MATRIX_SIZE, MATRIX_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = MATRIX_SIZE / 2f
        val cy = MATRIX_SIZE / 2f
        val outerR = cx - 0.3f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, outerR, paint)

        if (open > 0.04f) {
            val holeR = outerR * open * 0.88f
            val path = Path()
            val rotRad = rot * (Math.PI / 180.0).toFloat()
            for (i in 0 until BLADES) {
                val angle = rotRad + i * (2f * Math.PI.toFloat() / BLADES)
                val x = cx + holeR * cos(angle)
                val y = cy + holeR * sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.color = Color.BLACK
            canvas.drawPath(path, paint)
        }

        return bmp
    }

    private fun drawDigitBitmap(digit: Int): Bitmap {
        val bmp = Bitmap.createBitmap(MATRIX_SIZE, MATRIX_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE }

        if (digit == 10) {
            // Two 3×5 mini digits side by side, total width 7, centered
            val totalW = 7  // 3 + 1 gap + 3
            val xOff = (MATRIX_SIZE - totalW) / 2
            val yOff = (MATRIX_SIZE - 5) / 2
            drawPattern(canvas, MINI_DIGITS[1]!!, xOff, yOff, paint)
            drawPattern(canvas, MINI_DIGITS[0]!!, xOff + 4, yOff, paint)
        } else {
            // 5×7 pixel-art digit, centered — 4 LEDs off on each side, 3 off top/bottom
            val pattern = DIGIT_PATTERNS[digit] ?: return bmp
            val xOff = (MATRIX_SIZE - 5) / 2
            val yOff = (MATRIX_SIZE - 7) / 2
            drawPattern(canvas, pattern, xOff, yOff, paint)
        }
        return bmp
    }

    private fun drawPattern(canvas: Canvas, pattern: Array<IntArray>, xOff: Int, yOff: Int, paint: Paint) {
        for (row in pattern.indices) {
            for (col in pattern[row].indices) {
                if (pattern[row][col] == 1) {
                    canvas.drawRect(
                        (xOff + col).toFloat(), (yOff + row).toFloat(),
                        (xOff + col + 1).toFloat(), (yOff + row + 1).toFloat(),
                        paint
                    )
                }
            }
        }
    }

    private fun makeObj(bmp: Bitmap, brightness: Int): GlyphMatrixObject =
        GlyphMatrixObject.Builder()
            .setImageSource(bmp)
            .setBrightness(brightness)
            .setScale(200)
            .setPosition(0, 0)
            .build()

    private fun at(delayMs: Long, block: () -> Unit) {
        handler.postDelayed({
            try { block() } catch (e: Exception) { Log.e(TAG, "Glyph frame error", e) }
        }, delayMs)
    }

    private fun quietOff() {
        try { manager?.turnOff() } catch (e: Exception) { }
    }

    companion object {
        private const val TAG = "GlyphHelper"
        private const val MATRIX_SIZE = 13
        private const val BLADES = 5

        // 5×7 pixel-art font — each 1 = one LED lit, centered on 13×13 grid
        private val DIGIT_PATTERNS = mapOf(
            1 to arrayOf(
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,1,1,0,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,1,1,1,0)
            ),
            2 to arrayOf(
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,0,0,0,1),
                intArrayOf(0,0,0,1,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,1,0,0,0),
                intArrayOf(1,1,1,1,1)
            ),
            3 to arrayOf(
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,0,0,0,1),
                intArrayOf(0,0,1,1,0),
                intArrayOf(0,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0)
            ),
            4 to arrayOf(
                intArrayOf(0,0,0,1,0),
                intArrayOf(0,0,1,1,0),
                intArrayOf(0,1,0,1,0),
                intArrayOf(1,0,0,1,0),
                intArrayOf(1,1,1,1,1),
                intArrayOf(0,0,0,1,0),
                intArrayOf(0,0,0,1,0)
            ),
            5 to arrayOf(
                intArrayOf(1,1,1,1,1),
                intArrayOf(1,0,0,0,0),
                intArrayOf(1,1,1,1,0),
                intArrayOf(0,0,0,0,1),
                intArrayOf(0,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0)
            ),
            6 to arrayOf(
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,0),
                intArrayOf(1,0,0,0,0),
                intArrayOf(1,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0)
            ),
            7 to arrayOf(
                intArrayOf(1,1,1,1,1),
                intArrayOf(0,0,0,0,1),
                intArrayOf(0,0,0,1,0),
                intArrayOf(0,0,1,0,0),
                intArrayOf(0,1,0,0,0),
                intArrayOf(0,1,0,0,0),
                intArrayOf(0,1,0,0,0)
            ),
            8 to arrayOf(
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0)
            ),
            9 to arrayOf(
                intArrayOf(0,1,1,1,0),
                intArrayOf(1,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,1),
                intArrayOf(0,0,0,0,1),
                intArrayOf(1,0,0,0,1),
                intArrayOf(0,1,1,1,0)
            )
        )

        // 3×5 mini font for the "10" display
        private val MINI_DIGITS = mapOf(
            0 to arrayOf(
                intArrayOf(1,1,1),
                intArrayOf(1,0,1),
                intArrayOf(1,0,1),
                intArrayOf(1,0,1),
                intArrayOf(1,1,1)
            ),
            1 to arrayOf(
                intArrayOf(0,1,0),
                intArrayOf(1,1,0),
                intArrayOf(0,1,0),
                intArrayOf(0,1,0),
                intArrayOf(1,1,1)
            )
        )
    }
}
