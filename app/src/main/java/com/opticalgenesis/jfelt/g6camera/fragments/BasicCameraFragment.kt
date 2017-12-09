package com.opticalgenesis.jfelt.g6camera.fragments

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.Fragment
import android.util.Size
import android.view.*
import com.opticalgenesis.jfelt.g6camera.*
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class BasicCameraFragment : Fragment(), TextureView.SurfaceTextureListener {

    private var v: View? = null
    private var state = Constants.STATE_PREVIEW

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

    private val cameraOpenCloseLock = Semaphore(1)

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            this@BasicCameraFragment.cameraDevice = cd
            createPreview()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice?.close()
            this@BasicCameraFragment.cameraDevice = null
        }

        override fun onError(cd: CameraDevice, err: Int) {
            onDisconnected(cd)
            throw RuntimeException("Error opening basic camera")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }

        private fun process(res: CaptureResult) {
            when (state) {
                Constants.STATE_PREVIEW -> return
                Constants.STATE_WAITING_LOCK -> capturePicture(res)
                Constants.STATE_WAITING_PRECAPTURE -> state = Constants.STATE_WAITING_NON_PRECAPTURE
                Constants.STATE_WAITING_NON_PRECAPTURE -> {
                    state = Constants.STATE_PICTURE_TAKEN
                    takeBasicImage()
                }
            }
        }

        private fun capturePicture(res: CaptureResult) {
            val afState = res[CaptureResult.CONTROL_AF_STATE]
            if (afState == null) {
                takeBasicImage()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                state = Constants.STATE_PICTURE_TAKEN
                takeBasicImage()
            } else {
                runPrecapture()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        v = inflater.inflate(R.layout.fragment_camera_basic, container, false)
        return v
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

    override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) = openCamera(0, p1, p2)

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {
        if (cameraDevice != null) {
            closeCamera()
            openCamera(0, p1, p2)
        }
    }

    override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {}

    override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?) = false

    private fun initBackgroundThread() {
        backgroundThread = HandlerThread("Basic Camera Thread")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
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

    private fun lockFocus() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            state = Constants.STATE_WAITING_LOCK
            mCameraCaptureSession?.capture(capReqBuilder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun unlockFocus() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            mCameraCaptureSession?.capture(capReqBuilder?.build(), captureCallback, backgroundHandler)
            state = Constants.STATE_PREVIEW
            mCameraCaptureSession?.setRepeatingRequest(capReq, captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera(camPos: Int, width: Int?, height: Int?) {
        initOutputs(camPos, width, height)
        configTransform(width?.toFloat(), height?.toFloat())
        val mgr = activity?.getSystemService(Context.CAMERA_SERVICE)!! as CameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) throw RuntimeException("Time out opening camera")
            mgr.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun initOutputs(camPos: Int, width: Int?, height: Int?) {
        val mgr = activity?.getSystemService(Context.CAMERA_SERVICE)!! as CameraManager
        try {
            cameraId = mgr.cameraIdList[camPos]
            val chars = mgr.getCameraCharacteristics(cameraId)
            val charMap = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val largest = Collections.max(
                    Arrays.asList(*charMap.getOutputSizes(ImageFormat.JPEG)),
                    { lhs, rhs -> Constants.compareSizesByArea(lhs, rhs) })
            reader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ backgroundHandler?.post(ImageSaver(reader?.acquireNextImage()!!, Constants.setupFile())) }, backgroundHandler)
            }

            val dispRot = activity?.windowManager?.defaultDisplay?.rotation
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]
            val swappedDimens = Constants.areDimensSwapped(dispRot!!, sensorRot)

            val dispSize = Point()
            activity?.windowManager?.defaultDisplay?.getSize(dispSize)
            val rotPrevW = if (swappedDimens) height else width
            val rotPrevH = if (swappedDimens) width else height
            var maxPrevW = if (swappedDimens) dispSize.y else dispSize.x
            var maxPrevH = if (swappedDimens) dispSize.x else dispSize.y

            if (maxPrevW > 1080) maxPrevW = 1080

            if (maxPrevH > 1920) maxPrevH = 1920

            previewSize = MainActivity.chooseOptimalSize(charMap.getOutputSizes(SurfaceTexture::class.java), rotPrevW!!, rotPrevH!!, maxPrevW, maxPrevH, largest)
            debugLog(MainActivity.TAG, "Optimal width is ${previewSize?.width}\nOptimal height is ${previewSize?.height}")

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView?.setAspectRatio(previewSize?.width!!, previewSize?.height!!)
            } else {
                textureView?.setAspectRatio(previewSize?.height!!, previewSize?.width!!)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun configTransform(width: Float?, height: Float?) {
        if (textureView == null || previewSize != null) return

        val rot = activity?.windowManager?.defaultDisplay?.rotation!!
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width!!, height!!)
        val bufferRect = RectF(0f, 0f, previewSize?.height?.toFloat()!!, previewSize?.width?.toFloat()!!)

        val cx = viewRect.centerX()
        val cy = viewRect.centerY()

        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

            val scale = Math.max(height / previewSize?.height?.toFloat()!!, width / previewSize?.width?.toFloat()!!)
            matrix.apply {
                postScale(scale, scale, cx, cy)
                postRotate(180f, cx, cy)
            }
        } else if (rot == Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
        textureView?.setTransform(matrix)
    }

    private fun runPrecapture() {
        try {
            capReqBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state = Constants.STATE_WAITING_PRECAPTURE
            mCameraCaptureSession?.capture(capReqBuilder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createPreview() {
        try {
            val texture = textureView?.surfaceTexture
            texture?.setDefaultBufferSize(previewSize?.width!!, previewSize?.height!!)
            val surf = Surface(texture)

            capReqBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            capReqBuilder?.addTarget(surf)

            cameraDevice?.createCaptureSession(Arrays.asList(surf, reader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(ccs: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            mCameraCaptureSession = ccs
                            try {
                                capReqBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                capReq = capReqBuilder?.build()
                                mCameraCaptureSession?.setRepeatingRequest(capReq, captureCallback, backgroundHandler)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(ccs: CameraCaptureSession) = throw RuntimeException("Preview creation failed")
                    }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun takeBasicImage() {
        try {
            if (cameraDevice == null) return
            val rot = activity?.windowManager?.defaultDisplay?.rotation!!

            val mgr = activity?.getSystemService(Context.CAMERA_SERVICE)!! as CameraManager
            val chars = mgr.getCameraCharacteristics(cameraId!!)
            val sensorRot = chars[CameraCharacteristics.SENSOR_ORIENTATION]

            val capBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(reader?.surface)
                set(CaptureRequest.JPEG_ORIENTATION, (Constants.ORIENTATIONS[rot] + sensorRot + 270) % 360)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val capCall = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = unlockFocus()
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