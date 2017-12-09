package com.opticalgenesis.jfelt.g6camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener {

    private val permissionRequestCode = 420
    private val storageRequestCode = 350

    private lateinit var file: File
    private lateinit var imageSize: Size

    private var mCameraCaptureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var textureView: AutofitTextureVew? = null
    private var cameraDevice: CameraDevice? = null
    private var capReqBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var reader: ImageReader? = null
    private var previewSize: Size? = null
    private var capReq: CaptureRequest? = null

    private var state = STATE_PREVIEW

    private var isInManualFocus = false

    private val cameraOpenCloseLock = Semaphore(1)

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
        val ORIENTATIONS = SparseIntArray()

        val STATE_PREVIEW = 0
        val STATE_WAITING_LOCK = 1
        val STATE_WAITING_PRECAPTURE = 2
        val STATE_WAITING_NON_PRECAPTURE = 3
        val STATE_PICTURE_TAKEN = 4

        private class ImageSaver(val image: Image, val file: File) : Runnable {
            override fun run() {
                val buffer = image.planes[0].buffer
                val bytes = byteArrayOf(buffer.remaining().toByte())
                buffer[bytes]
                var outputStream: FileOutputStream? = null
                try {
                    outputStream = FileOutputStream(file)
                    outputStream.write(bytes)
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    image.close()
                    if (outputStream != null) {
                        try {
                            outputStream.close()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        private fun compareSizesByArea(lhs: Size, rhs: Size) = signum((lhs.width.toLong() * lhs.height.toLong()) - (rhs.width.toLong() * rhs.height.toLong()))

        fun chooseOptimalSize(sizes: Array<Size>, textureViewWidth: Int, textureViewHeight: Int,
                              maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
            val bigEnough = ArrayList<Size>()
            val notBigEnough = ArrayList<Size>()

            val w = aspectRatio.width
            val h = aspectRatio.height
            sizes.forEach {
                if (it.width <= maxWidth && it.height <= maxHeight && it.height == it.width * h / w) {
                    if (it.width >= textureViewWidth && it.height >= textureViewHeight) bigEnough.add(it) else notBigEnough.add(it)
                }
            }
            if (bigEnough.size > 0) return Collections.max(bigEnough, { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            else if (notBigEnough.size > 0) return Collections.max(notBigEnough, { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            return sizes[0]
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initCameraLayout()

        if (checkPermissions()) {
            initOrientations()
        } else {
            requestPermissions()
        }

/*        // Example of a call to a native method
        sample_text.text = stringFromJNI()*/
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            permissionRequestCode -> initOrientations()
            storageRequestCode -> setupFile()
        }
    }

    override fun onResume() {
        super.onResume()
        initBackgroundThread()
        if (textureView?.isAvailable!!) {
            openCamera(0, textureView?.width!!, textureView?.height!!)
        } else {
            textureView?.surfaceTextureListener = this
        }
    }

    override fun onPause() {
        closeCamera()
        haltBackgroundThread()
        super.onPause()
    }


    override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
        openCamera(0, p1, p2)
    }

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {
        if (cameraDevice != null) {
            closeCamera()
            openCamera(0, p1, p2)
        }
    }

    override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {}

    override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
        return false
    }

    override fun onClick(p0: View?) {
        val w = textureView?.width
        val h = textureView?.height
        when (p0?.id) {
            R.id.debug_cam_switch_front -> {
                closeCamera()
                openCamera(0, w, h)
            }
            R.id.debug_cam_switch_rear -> {
                closeCamera()
                openCamera(1, w, h)
            }
            R.id.debug_cam_switch_rear_wide -> {
                closeCamera()
                openCamera(2, w, h)
            }
            else -> {
                closeCamera()
                openCamera(0, w, h)
            }
        }
    }
/*
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        debugLog(TAG, "ViewTouched")
        val actionMasked = event.actionMasked
        if (actionMasked != MotionEvent.ACTION_DOWN) return false
        if (isInManualFocus) return true

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = mgr.getCameraCharacteristics(cameraId!!)
        val sensorArraySizes = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]

        var isSwapped = false
        val dispRot = windowManager.defaultDisplay.rotation
        val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]

        val x: Int
        val y: Int

        when (dispRot) {
            Surface.ROTATION_0 -> if (sensorRot == 90 || sensorRot == 270) isSwapped = true
            Surface.ROTATION_180 -> if (sensorRot == 90 || sensorRot == 270) isSwapped = true
            Surface.ROTATION_90 -> if (sensorRot == 0 || sensorRot == 180) isSwapped = true
            Surface.ROTATION_270 -> if (sensorRot == 0 || sensorRot == 180) isSwapped = true
        }

        if (isSwapped) {
            y = (event.x / v.width * sensorArraySizes.height()).toInt()
            x = (event.y / v.height * sensorArraySizes.width()).toInt()
        } else {
            y = (event.y / v.height * sensorArraySizes.width()).toInt()
            x = (event.x / v.width * sensorArraySizes.height()).toInt()
        }

        val focusArea = MeteringRectangle(Math.max(x - 150, 0), Math.max(y - 150, 0), 300, 300, MeteringRectangle.METERING_WEIGHT_MAX - 1)

        mCameraCaptureSession?.stopRepeating()

        capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        capReqBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        mCameraCaptureSession?.setRepeatingRequest(capReqBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                super.onCaptureCompleted(session, request, result)
                isInManualFocus = false
                if (request?.tag == "FOCUS_TAG") {
                    capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                    mCameraCaptureSession?.setRepeatingRequest(capReqBuilder?.build(), null, null)
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest?, failure: CaptureFailure?) {
                super.onCaptureFailed(session, request, failure)
                Log.e(TAG, "Error in manual AF")
                isInManualFocus = false
            }
        }, backgroundHandler)

        capReqBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))

        capReqBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        capReqBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        capReqBuilder?.setTag("FOCUS_TAG")

        mCameraCaptureSession?.capture(capReqBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                super.onCaptureCompleted(session, request, result)
                isInManualFocus = false
                if (request?.tag == "FOCUS_TAG") {
                    capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                    mCameraCaptureSession?.setRepeatingRequest(capReqBuilder?.build(), null, null)
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest?, failure: CaptureFailure?) {
                super.onCaptureFailed(session, request, failure)
                Log.e(TAG, "Error in manual AF")
                isInManualFocus = false
            }
        }, backgroundHandler)

        isInManualFocus = true
        return true
    }*/

    private fun initOrientations() {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private fun checkPermissions() =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() =
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), permissionRequestCode)

    /*
        TODO -- 2017/12/03
        It would seem that to accomplish "wide-angle" on the front cam, the ratio is simply altered
        Look into this
     */

    private fun initCameraLayout() {
        textureView = findViewById(R.id.camera_preview)
        textureView?.surfaceTextureListener = this

        val params = textureView?.layoutParams
        params?.height = textureView?.width
        textureView?.layoutParams = params

        val frontCamButton: Button = findViewById(R.id.debug_cam_switch_front)
        val rearCamButton = findViewById<Button>(R.id.debug_cam_switch_rear)
        val rearWideCamButton = findViewById<Button>(R.id.debug_cam_switch_rear_wide)

        frontCamButton.setOnClickListener(this)
        rearCamButton.setOnClickListener(this)
        rearWideCamButton.setOnClickListener(this)
    }

    private fun initBackgroundThread() {
        backgroundThread = HandlerThread("Camera Thread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun haltBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        if (cameraDevice == null) {
            Log.e(TAG, "CameraDevice == null")
            return
        }
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val chars: CameraCharacteristics? = mgr.getCameraCharacteristics(cameraDevice!!.id)
            var jpegSizes: Array<Size>? = null
            if (chars != null) {
                jpegSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG)
            }

            var w = 640
            var h = 480

            if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                w = jpegSizes[0].width
                h = jpegSizes[0].height
            }

            val rdr = ImageReader.newInstance(w, h, ImageFormat.JPEG, 1)
            val outputSurfaces = ArrayList<Surface>(2)
            outputSurfaces.add(rdr.surface)
            outputSurfaces.add(Surface(textureView?.surfaceTexture))

            capReqBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            capReqBuilder?.addTarget(rdr.surface)
            capReqBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            val rot = windowManager.defaultDisplay.rotation
            capReqBuilder?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rot])

            if (checkStoragePermissions()) {
                setupFile()
            } else {
                requestStoragePermissions()
            }

            rdr.setOnImageAvailableListener({_: ImageReader? ->
                var img: Image?
                try {
                    img = rdr.acquireLatestImage()
                    val buff = img?.planes?.get(0)?.buffer
                    val bytes = byteArrayOf(buff?.capacity()?.toByte()!!)
                    buff[bytes]

                    var os: OutputStream? = null
                    try {
                        os = FileOutputStream(file)
                        os.write(bytes)
                    } finally {
                        if (img != null) img.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, backgroundHandler)

            cameraDevice?.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(p0: CameraCaptureSession?) {
                    try {
                        p0?.capture(capReqBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                                super.onCaptureCompleted(session, request, result)
                                createCameraPreview()
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(p0: CameraCaptureSession?) {
                    Log.e(TAG, "Camera config failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView?.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize?.width!!, previewSize?.height!!)
            val newSurface = Surface(texture)
            capReqBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            capReqBuilder?.addTarget(newSurface)

            cameraDevice?.createCaptureSession(listOf(newSurface, reader?.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(p0: CameraCaptureSession?) {
                    if (cameraDevice == null) return
                    mCameraCaptureSession = p0!!
                    try {
                        capReqBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        capReq = capReqBuilder?.build()
                        mCameraCaptureSession?.setRepeatingRequest(capReq, object : CameraCaptureSession.CaptureCallback() {
                            fun process(result: CaptureResult) {
                                when (state) {
                                    STATE_PREVIEW -> return
                                    STATE_WAITING_LOCK -> {
                                        val afState = result[CaptureResult.CONTROL_AE_STATE]
                                        if (afState == null) {
                                            takePicture()
                                        } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                            val aeState = result[CaptureResult.CONTROL_AE_STATE]
                                            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                                state = STATE_PICTURE_TAKEN
                                                takePicture()
                                            } else {
                                                runPrecapture()
                                            }
                                        }
                                    }
                                    STATE_WAITING_PRECAPTURE -> {
                                        val aeState = result[CaptureResult.CONTROL_AE_STATE]
                                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                                || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) state = STATE_WAITING_NON_PRECAPTURE
                                    }
                                    STATE_WAITING_NON_PRECAPTURE -> {
                                        val aeState = result[CaptureResult.CONTROL_AE_STATE]
                                        if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                            state = STATE_PICTURE_TAKEN
                                            takePicture()
                                        }
                                    }
                                }
                            }

                            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                                process(partialResult)
                            }

                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                process(result)
                            }
                        }, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(p0: CameraCaptureSession) {
                    Log.e(TAG, "Configure failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun lockFocus() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            state = STATE_WAITING_LOCK
            mCameraCaptureSession?.capture(capReqBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
                fun process(result: CaptureResult) {
                    when (state) {
                        STATE_PREVIEW -> return
                        STATE_WAITING_LOCK -> {
                            val afState = result[CaptureResult.CONTROL_AE_STATE]
                            if (afState == null) {
                                takePicture()
                            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                val aeState = result[CaptureResult.CONTROL_AE_STATE]
                                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    state = STATE_PICTURE_TAKEN
                                    takePicture()
                                } else {
                                    runPrecapture()
                                }
                            }
                        }
                        STATE_WAITING_PRECAPTURE -> {
                            val aeState = result[CaptureResult.CONTROL_AE_STATE]
                            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) state = STATE_WAITING_NON_PRECAPTURE
                        }
                        STATE_WAITING_NON_PRECAPTURE -> {
                            val aeState = result[CaptureResult.CONTROL_AE_STATE]
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                state = STATE_PICTURE_TAKEN
                                takePicture()
                            }
                        }
                    }
                }

                override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                    process(partialResult)
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    process(result)
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) {
            return
        }

        capReqBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            mCameraCaptureSession?.setRepeatingRequest(capReqBuilder?.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun runPrecapture() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state = STATE_WAITING_PRECAPTURE
            mCameraCaptureSession?.capture(capReqBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
                fun process(result: CaptureResult) {
                    when (state) {
                        STATE_PREVIEW -> return
                        STATE_WAITING_LOCK -> {
                            val afState = result[CaptureResult.CONTROL_AE_STATE]
                            if (afState == null) {
                                takePicture()
                            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                val aeState = result[CaptureResult.CONTROL_AE_STATE]
                                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    state = STATE_PICTURE_TAKEN
                                    takePicture()
                                } else {
                                    runPrecapture()
                                }
                            }
                        }
                        STATE_WAITING_PRECAPTURE -> {
                            val aeState = result[CaptureResult.CONTROL_AE_STATE]
                            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) state = STATE_WAITING_NON_PRECAPTURE
                        }
                        STATE_WAITING_NON_PRECAPTURE -> {
                            val aeState = result[CaptureResult.CONTROL_AE_STATE]
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                state = STATE_PICTURE_TAKEN
                                takePicture()
                            }
                        }
                    }
                }

                override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                    process(partialResult)
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    process(result)
                }


            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera(camPos: Int, width: Int?, height: Int?) {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        initOutputs(camPos, width, height)
        configTransform(width?.toFloat(), height?.toFloat())
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) throw RuntimeException("Camera access timed out")
            try {
                mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(p0: CameraDevice?) {
                        cameraOpenCloseLock.release()
                        cameraDevice = p0
                        createCameraPreview()
                    }

                    override fun onError(p0: CameraDevice?, p1: Int) {
                        Log.e(TAG, "Camera device ${p0?.id} threw an error")
                        cameraOpenCloseLock.release()
                        p0?.close()
                        cameraDevice = null
                    }

                    override fun onDisconnected(p0: CameraDevice?) {
                        debugLog(TAG, "Camera ${p0?.id} disconnected")
                        cameraOpenCloseLock.release()
                        p0?.close()
                        cameraDevice = null
                    }
                }, backgroundHandler)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initOutputs(camPos: Int, width: Int?, height: Int?) {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = mgr.cameraIdList[camPos]
            val chars = mgr.getCameraCharacteristics(cameraId)
            val configMap = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]

            val largestSize = Collections.max(configMap.getOutputSizes(ImageFormat.JPEG).toList(), { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            reader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 2)
            reader?.setOnImageAvailableListener({ reader ->
                backgroundHandler?.post(ImageSaver(reader.acquireNextImage(), file))
            }, backgroundHandler)

            val dispRot = windowManager.defaultDisplay.rotation
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]
            var areDimensionsSwapped = false
            when (dispRot) {
/*                Surface.ROTATION_0 or Surface.ROTATION_180 -> if (sensorRot == 90 || sensorRot == 270) areDimensionsSwapped = true
                Surface.ROTATION_90 or Surface.ROTATION_270 -> if (sensorRot == 0 || sensorRot == 180) areDimensionsSwapped = true*/
                Surface.ROTATION_0 -> if (sensorRot == 90 || sensorRot == 270) areDimensionsSwapped = true
                else -> Log.e(TAG, "Display rotation $dispRot is invalid")
            }

            val dispSize = Point()
            windowManager.defaultDisplay.getSize(dispSize)
            var rotatedPrevWidth = width
            var rotatedPrevHeight = height
            var maxPrevWidth = dispSize.x
            var maxPrevHeight = dispSize.y

            if (areDimensionsSwapped) {
                rotatedPrevWidth = height
                rotatedPrevHeight = width
                maxPrevWidth = dispSize.y
                maxPrevHeight = dispSize.x
            }

            if (maxPrevWidth > 1440) {
                maxPrevWidth = 1440
            }

            if (maxPrevHeight > 2880) {
                maxPrevHeight = 2880
            }

            previewSize = chooseOptimalSize(configMap.getOutputSizes(SurfaceTexture::class.java), rotatedPrevWidth!!, rotatedPrevHeight!!, maxPrevWidth, maxPrevHeight, largestSize)

            val rot = resources.configuration.orientation
            if (rot == Configuration.ORIENTATION_LANDSCAPE) textureView?.setAspectRatio(previewSize?.width!!, previewSize?.height!!) else textureView?.setAspectRatio(previewSize?.height!!, previewSize?.width!!)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun configTransform(width: Float?, height: Float?) {
        if (textureView == null || previewSize != null) return

        val rot = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, width!!, height!!)
        val bufferRect = RectF(0F, 0F, previewSize?.height?.toFloat()!!, previewSize?.width?.toFloat()!!)

        val cx = viewRect.centerX()
        val cy = viewRect.centerY()

        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

            val scale = Math.max(height / previewSize?.height?.toFloat()!!, width / previewSize?.width?.toFloat()!!)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(180F, cx, cy)
        } else if (rot == Surface.ROTATION_180) matrix.postRotate(180F, cx, cy)
        textureView?.setTransform(matrix)
    }

    private fun checkStoragePermissions() =
            ContextCompat.checkSelfPermission(this, Manifest.permission_group.STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermissions() =
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission_group.STORAGE), storageRequestCode)

    private fun setupFile() {
        file = File("${Environment.getExternalStorageDirectory()}/pic.jpg")
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (mCameraCaptureSession != null) {
                mCameraCaptureSession?.close()
                mCameraCaptureSession = null
            }

            if (cameraDevice != null) {
                cameraDevice?.close()
                cameraDevice = null
            }

            if (reader != null) {
                reader?.close()
                reader = null
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
        }
    }


 /*   *//**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     *//*
    external fun stringFromJNI(): String

    companion object {

        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }*/
}
