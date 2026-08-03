package com.ivi.car.navigation.service

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import java.lang.Double.max
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// An intermedia surface that downscales the input surface to the destination surface. Note that
// this is all done in software, so it is not very efficient and is measured at about 13ms per frame
// for the Samsung SM-S901U and ~13ms for Motorola Moto E6. However it is fast enough given that the
// capture rate is ~1 fps.
class SurfaceDownscaler(
    private val srcWidth: Int, private val srcHeight: Int,
    private val dstWidth: Int, private val dstHeight: Int,
    private val fps: Float) {

    companion object {
        private const val TAG = "SurfaceDownscaler"
        private const val LOG_PERFORMANCE = true
    }

    var inputSurface: Surface

    fun setDestinationSurface(surface: Surface?) {
        synchronized(surfaceLock) {
            surfaceDestination = surface
        }
    }

    fun pause() {
        paused.set(true)
    }
    fun resume() {
        paused.set(false)
    }

    private val paused = AtomicBoolean(false)
    private val surfaceLock = Any()
    private var surfaceDestination: Surface? = null
    private var imageFormat: Int = PixelFormat.RGBA_8888
    private var imageReader: ImageReader
    private val recentImage: AtomicReference<Image?> = AtomicReference(null)
    private lateinit var largeBitmap: Bitmap
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val imageListener: ImageReader.OnImageAvailableListener = ImageReader.OnImageAvailableListener {
        val image: Image? = it.acquireLatestImage()
        if (image == null) {
            return@OnImageAvailableListener
        }
        val prevImage: Image? = recentImage.getAndSet(image)
        if (prevImage != null) {
            prevImage.close()
        }
    }

    init {
        require(srcWidth > 0)
        require(srcHeight > 0)
        require(dstWidth > 0)
        require(dstHeight > 0)
        require(fps > 0)
        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(srcWidth, srcHeight, imageFormat, 3)
        imageReader.setOnImageAvailableListener(imageListener, null)
        //timerAllowImage.start()
        //onTick()
        inputSurface = imageReader.surface
        paint.isFilterBitmap = true
    }

    fun close() {
        //timerAllowImage.stop()
        imageReader.close()
        recentImage.getAndSet(null)?.close()
        if (::largeBitmap.isInitialized) {
            largeBitmap.recycle()
        }
    }


    private fun drawImageAndRelease(image: Image) {
        val startTime = System.currentTimeMillis()
        val planes = image.planes
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding: Int = rowStride - pixelStride * image.width
        if (!::largeBitmap.isInitialized) {
            largeBitmap = Bitmap.createBitmap(
                srcWidth + rowPadding / pixelStride, srcHeight,
                Bitmap.Config.ARGB_8888
            )
        }
        largeBitmap.copyPixelsFromBuffer(planes[0].buffer)
        image.close()
        synchronized(surfaceLock) {
            val surface = surfaceDestination
            if (surface?.isValid == true) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    val dstRect = Rect(0, 0, dstWidth, dstHeight)
                    canvas.drawBitmap(largeBitmap, null, dstRect, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
        }
        Log.d(TAG, "drawImageAndRelease largeBitmap: $largeBitmap")
        val duration = System.currentTimeMillis() - startTime
        if (LOG_PERFORMANCE) {
            Log.d(TAG, "drawImageAndRelease duration: $duration")
        }
    }

     fun onTick(): Long {
        val startTimeAll = System.currentTimeMillis()
        val image = recentImage.getAndSet(null)
        if (image != null) {
            if (paused.get()) {
                image.close()
            } else {
                drawImageAndRelease(image)
            }
        }
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTimeAll
        val sleepTime = max(0.0, (1000.0 / fps) - duration.toDouble())
        if (LOG_PERFORMANCE) {
            Log.d(TAG, "duration: $duration")
        }
        return sleepTime.toLong()
    }

}
