package com.opticalgenesis.jfelt.g6camera

import android.Manifest
import android.content.ContentValues
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
import android.provider.MediaStore
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
import android.widget.SeekBar
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Long.signum
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener, View.OnLongClickListener {

    // todo 2018/02/22 store cameraPos as shared pref to enable going back to same cam view after capturing image

    private val cameraRequestCode = 420
    private val storageRequestCode = 350

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
    private var cameraPosition: Int = 1
    private var exposure: Float = 0.0f

//    private var isInManualFocus = false

    private val cameraOpenCloseLock = Semaphore(1)

    private lateinit var file: File

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            this@MainActivity.cameraDevice = cd
            createCameraPreview()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            this@MainActivity.cameraDevice = null
        }

        override fun onError(cd: CameraDevice, err: Int) {
            onDisconnected(cd)
            throw RuntimeException("Error opening camera")
        }
    }

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

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        private class ImageSaver(val image: Image, val file: File) : Runnable {
            override fun run() {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer[bytes]
                var outputStream: FileOutputStream? = null
                try {
                    outputStream = FileOutputStream(file).apply { write(bytes) }
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    image.close()
                    outputStream?.let {
                        try {
                            it.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        private fun compareSizesByArea(lhs: Size, rhs: Size) = signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)

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
        val prefs = getPreferences(Context.MODE_PRIVATE)
        cameraPosition = prefs.getInt("CameraPosition", 1)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        } else {
            initCameraLayout()
        }
    }

    override fun onLongClick(v: View?): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission_group.STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermissions()
            lockFocus()
            Toast.makeText(this, "Picture taken", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun requestCameraPermission() = ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), cameraRequestCode)

    private fun requestStoragePermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), storageRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            cameraRequestCode -> initOrientations()
        }
    }

    override fun onResume() {
        super.onResume()
        initBackgroundThread()
        if (textureView == null) initCameraLayout()
        if (textureView?.isAvailable!!) {
            openCamera(cameraPosition, textureView?.width!!, textureView?.height!!)
        } else {
            textureView?.surfaceTextureListener = this
        }
    }

    override fun onPause() {
        saveCameraPosition()
        closeCamera()
        haltBackgroundThread()
        super.onPause()
    }


    override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
        openCamera(cameraPosition, p1, p2)
    }

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {
        if (cameraDevice != null) {
            closeCamera()
            openCamera(cameraPosition, p1, p2)
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
                openCamera(cameraPosition, w, h)
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        debugLog("RESTART", "Activity completely restarted")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        debugLog("RESTART", "Instance state restored")
    }

    private fun initOrientations() {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private fun checkPermissions() =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission_group.STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun initCameraLayout() {
        textureView = findViewById(R.id.camera_preview)
        textureView?.surfaceTextureListener = this

        val params = textureView?.layoutParams
        params?.height = textureView?.width
        textureView?.layoutParams = params

        val frontCamButton: Button = findViewById(R.id.debug_cam_switch_front)
        val rearCamButton = findViewById<Button>(R.id.debug_cam_switch_rear)
        val rearWideCamButton = findViewById<Button>(R.id.debug_cam_switch_rear_wide)

        val exposureBar = findViewById<SeekBar>(R.id.exposure_bar)

        exposureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                exposure = (progress / 100).toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        frontCamButton.setOnClickListener(this)
        frontCamButton.setOnLongClickListener(this)

        rearCamButton.setOnClickListener(this)
        rearCamButton.setOnLongClickListener(this)

        rearWideCamButton.setOnClickListener(this)
        rearWideCamButton.setOnLongClickListener(this)
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
            val expRange = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            val actualExp = expRange.lower + ((expRange.upper - expRange.lower) * exposure)
            debugLog("ACT_EXP", "Lower: ${expRange.lower}\nUpper: ${expRange.upper}\nModified: $actualExp\nExposure modifier: $exposure")
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]
            val capBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(reader?.surface)
                set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS[rot] + sensorRot + 270) % 360)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
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

            Toast.makeText(this, "Picture taken", Toast.LENGTH_SHORT).show()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun saveCameraPosition() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt("CameraPosition", cameraPosition).apply()
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
                            error("Camera configure failed")
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
        cameraPosition = camPos
        initOutputs(camPos, width, height)
        configTransform(width?.toFloat(), height?.toFloat())
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) throw RuntimeException("Time out opening camera")
            mgr.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun initOutputs(camPos: Int, width: Int?, height: Int?) {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = mgr.cameraIdList[camPos]
            val chars = mgr.getCameraCharacteristics(cameraId)
            val charMap = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            // getting fps for video
/*            if (CamcorderProfile.hasProfile(cameraId?.toInt()!!, CamcorderProfile.QUALITY_1080P)) {
                val prof = CamcorderProfile.get(cameraId?.toInt()!!, CamcorderProfile.QUALITY_1080P)
                debugLog("PROF", prof.videoFrameRate.toString())
            }*/
            // checking for available keys
            chars.availableCaptureRequestKeys.forEach({
                debugLog("CHAR_KEY", it.name)
            })
            val largest = Collections.max(
                    Arrays.asList(*charMap.getOutputSizes(ImageFormat.JPEG)),
                    { lhs, rhs -> compareSizesByArea(lhs, rhs) })
            reader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({
                    // todo 2018/02/21 -- refactor this into separate method for readability
                    val pathId = "${Environment.getExternalStorageDirectory()}/${Environment.DIRECTORY_DCIM}/"
                    val folderId = File(pathId, "G6Camera")
                    if (!folderId.exists()) {
                        if (!folderId.mkdirs()) {
                            Log.e("ERR", "Failed to create album folder")
                        } else {
                            Log.d("SUCC", "Album folder created successfully")
                        }
                    }

                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val imgFile = File(folderId, "$ts.jpg")

                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.TITLE, "$ts.jpg")
                    cv.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    cv.put(MediaStore.Images.Media.ORIENTATION, 0) // not accurate
                    cv.put(MediaStore.Images.Media.CONTENT_TYPE, "image/jpeg")
                    cv.put("_data", imgFile.absolutePath)
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    backgroundHandler?.post(ImageSaver(it.acquireNextImage(), imgFile))
                }, backgroundHandler)
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
                if (sensorRot == 90 || sensorRot == 270) dimensAreSwapped = true
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
}
