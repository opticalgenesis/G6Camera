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
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener {

    private val permissionRequestCode = 420
    private val storageRequestCode = 350

    private var file = File("${Environment.getExternalStorageDirectory()}/pic.jpg")
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

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }

        private fun process(res: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> return
                STATE_WAITING_LOCK -> capturePicture(res)
                STATE_WAITING_PRECAPTURE -> state = STATE_WAITING_NON_PRECAPTURE
                STATE_WAITING_NON_PRECAPTURE -> {
                    state = STATE_PICTURE_TAKEN; takePicture()
                }
            }
        }

        private fun capturePicture(res: CaptureResult) {
            val afState = res[CaptureResult.CONTROL_AF_STATE]
            if (afState == null) {
                takePicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                state = MainActivity.STATE_PICTURE_TAKEN
                takePicture()
            } else {
                runPrecapture()
            }
        }
    }

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
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission_group.STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), permissionRequestCode)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission_group.STORAGE), storageRequestCode)
    }

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

        frontCamButton.setOnLongClickListener({ takePicture(); true })
        rearCamButton.setOnLongClickListener({ takePicture(); true })
        rearWideCamButton.setOnLongClickListener({ takePicture(); true })
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
        try {
            if (cameraDevice == null) return
            val rot = windowManager.defaultDisplay.rotation

            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(cameraId!!)
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]

            val capBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(reader?.surface)
                set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS[rot] + sensorRot + 270) % 360)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val capCall = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                    unlockFocus()
                }
            }

            mCameraCaptureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(capBuilder?.build(), capCall, null)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView?.surfaceTexture
            texture?.setDefaultBufferSize(previewSize?.width!!, previewSize?.height!!)
            val surf = Surface(texture)

            capReqBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            capReqBuilder?.addTarget(surf)

            cameraDevice?.createCaptureSession(Arrays.asList(surf, reader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            mCameraCaptureSession = session
                            try {
                                capReqBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                capReq = capReqBuilder?.build()
                                mCameraCaptureSession?.setRepeatingRequest(capReq, captureCallback, backgroundHandler)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                        }
                    }, null)
        } catch (e: Exception) {
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

    private fun unlockFocus() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            mCameraCaptureSession?.capture(capReqBuilder?.build(), captureCallback, backgroundHandler)
            state = STATE_PREVIEW
            mCameraCaptureSession?.setRepeatingRequest(capReq, captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun runPrecapture() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state = STATE_WAITING_PRECAPTURE
            mCameraCaptureSession?.capture(capReqBuilder?.build(), captureCallback, backgroundHandler)
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
            val charMap = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val largest = Collections.max(
                    Arrays.asList(*charMap.getOutputSizes(ImageFormat.JPEG)),
                    { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            reader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ backgroundHandler?.post(ImageSaver(it.acquireNextImage(), file)) }, backgroundHandler)
            }

            val dispRot = windowManager.defaultDisplay.rotation
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]
            val swappedDimens = areDimensionsSwapped(dispRot, sensorRot)

            val dispSize = Point()
            windowManager.defaultDisplay.getSize(dispSize)
            val rotPrevW = if (swappedDimens) height else width
            val rotPrevH = if (swappedDimens) width else height
            var maxPrevW = if (swappedDimens) dispSize.y else dispSize.x
            var maxPrevH = if (swappedDimens) dispSize.x else dispSize.y

            if (maxPrevW > 1080) maxPrevW = 1080

            if (maxPrevH > 1920) maxPrevH = 1920

            previewSize = chooseOptimalSize(charMap.getOutputSizes(SurfaceTexture::class.java), rotPrevW!!, rotPrevH!!, maxPrevW, maxPrevH, largest)

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView?.setAspectRatio(previewSize?.width!!, previewSize?.height!!)
            } else {
                textureView?.setAspectRatio(previewSize?.height!!, previewSize?.width!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun areDimensionsSwapped(dispRot: Int, sensorRot: Int): Boolean {
        var dimensAreSwapped = false

        when (dispRot) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorRot == 90 || sensorRot == 180) dimensAreSwapped = true
            }

            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorRot == 0 || sensorRot == 180) dimensAreSwapped = true
            }

            else -> Log.e(TAG, "Invalid display rotation")
        }

        return dimensAreSwapped
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
