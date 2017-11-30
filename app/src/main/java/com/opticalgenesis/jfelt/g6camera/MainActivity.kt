package com.opticalgenesis.jfelt.g6camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private val permissionRequestCode = 420
    private val storageRequestCode = 350

    private lateinit var textureView: AutofitTextureVew
    private lateinit var file: File
    private lateinit var imageSize: Size
    private lateinit var mCameraCaptureSession: CameraCaptureSession

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    private var capReqBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
        val ORIENTATIONS = SparseIntArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initTextureView()

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
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = this
        }
    }

    override fun onPause() {
        haltBackgroundThread()
        super.onPause()
    }


    override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
        openCamera()
    }

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {
        debugLog(TAG, "SurfaceTextureSizeChanged")
    }

    override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {
        debugLog(TAG, "SurfaceTextureUpdated")
    }

    override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
        debugLog(TAG, "SurfaceTextureDestroyed")
        return false
    }

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

    private fun initTextureView() {
        textureView = findViewById(R.id.cam_display)
        textureView.surfaceTextureListener = this
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
            outputSurfaces.add(Surface(textureView.surfaceTexture))

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
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(imageSize.width, imageSize.height)

            val surf = Surface(texture)
            capReqBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            capReqBuilder?.addTarget(surf)
            cameraDevice?.createCaptureSession(mutableListOf(surf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(p0: CameraCaptureSession?) {
                    if (cameraDevice == null) {
                        return
                    }
                    mCameraCaptureSession = p0!!
                    updatePreview()
                }

                override fun onConfigureFailed(p0: CameraCaptureSession?) {
                    Log.e(TAG, "Camera config failed")
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) {
            return
        }

        capReqBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            mCameraCaptureSession.setRepeatingRequest(capReqBuilder?.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    // TODO -- 2017/11/29 -- Add method to switch selected camera
    // possibly by passing an Integer to this method
    private fun openCamera() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = mgr.cameraIdList[2]
            debugLog(TAG, "There are ${mgr.cameraIdList.size} cameras.")
            val chars: CameraCharacteristics? = mgr.getCameraCharacteristics(cameraId)
            val configMap = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            imageSize = configMap.getOutputSizes(SurfaceTexture::class.java)[0]
            try {
                mgr.openCamera(cameraId, object : CameraDevice.StateCallback(){
                    override fun onOpened(p0: CameraDevice?) {
                        cameraDevice = p0
                        createCameraPreview()
                    }

                    override fun onError(p0: CameraDevice?, p1: Int) {
                        cameraDevice?.close()
                    }

                    override fun onDisconnected(p0: CameraDevice?) {
                        cameraDevice?.close()
                        cameraDevice = null
                    }
                }, null)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun checkStoragePermissions() =
            ContextCompat.checkSelfPermission(this, Manifest.permission_group.STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermissions() =
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission_group.STORAGE), storageRequestCode)

    private fun setupFile() {
        file = File("${Environment.getExternalStorageDirectory()}/pic.jpg")
    }

    private fun closeCamera() {
        if (cameraDevice != null) {
            cameraDevice?.close()
            cameraDevice = null
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
