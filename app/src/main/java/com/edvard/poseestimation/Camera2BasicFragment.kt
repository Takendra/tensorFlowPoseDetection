/*
 * Copyright 2018 Zihua Zeng (edvard_hua@live.com), Lang Feng (tearjeaker@hotmail.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edvard.poseestimation

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.*
import android.support.v13.app.FragmentCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.support.v4.content.LocalBroadcastManager
import java.util.*
import android.util.DisplayMetrics
import android.util.SparseIntArray


/**
 * Basic fragments for the Camera.
 */
class Camera2BasicFragment : Fragment(), FragmentCompat.OnRequestPermissionsResultCallback {

  private var startRecording: Boolean=false
  private val lock = Any()
  private var runClassifier = false
  private var checkedPermissions = false
  private var textView: TextView? = null
  private var textureView: AutoFitTextureView? = null
  private var layoutFrame: AutoFitFrameLayout? = null
  private var drawView: DrawView? = null
  private var classifier: ImageClassifier? = null
  private var layoutBottom: ViewGroup? = null


 /*video recorder vars*/

  private val TAG = "Takendra"
  private val REQUESTCODE = 1000
  private var mScreenDensity = 0
  private var mProjectionManager: MediaProjectionManager? = null

  private var mMediaProjection: MediaProjection? = null
  private var mVirtualDisplay: VirtualDisplay? = null
  private var mMediaProjectionCallback: MediaProjectionCallback? = null
  private var mMediaRecorder: MediaRecorder? = null
  private var mBroadcastReceiver: BroadcastReceiver? = null
  private var folderName:String="yogaApp/"
 /*video recorder vars*/


  /**
   * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
   */
  private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

    override fun onSurfaceTextureAvailable(
      texture: SurfaceTexture,
      width: Int,
      height: Int
    ) {
      openCamera(width, height)
    }

    override fun onSurfaceTextureSizeChanged(
      texture: SurfaceTexture,
      width: Int,
      height: Int
    ) {
      configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
      return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
  }

  /**
   * ID of the current [CameraDevice].
   */
  private var cameraId: String? = null

  /**
   * A [CameraCaptureSession] for camera preview.
   */
  private var captureSession: CameraCaptureSession? = null

  /**
   * A reference to the opened [CameraDevice].
   */
  private var cameraDevice: CameraDevice? = null

  /**
   * The [android.util.Size] of camera preview.
   */
  private var previewSize: Size? = null

  /**
   * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
   */
  private val stateCallback = object : CameraDevice.StateCallback() {

    override fun onOpened(currentCameraDevice: CameraDevice) {
      // This method is called when the camera is opened.  We start camera preview here.
      cameraOpenCloseLock.release()
      cameraDevice = currentCameraDevice
      createCameraPreviewSession()
    }

    override fun onDisconnected(currentCameraDevice: CameraDevice) {
      cameraOpenCloseLock.release()
      currentCameraDevice.close()
      cameraDevice = null
    }

    override fun onError(
      currentCameraDevice: CameraDevice,
      error: Int
    ) {
      cameraOpenCloseLock.release()
      currentCameraDevice.close()
      cameraDevice = null
      val activity = activity
      activity?.finish()
    }
  }

  /**
   * An additional thread for running tasks that shouldn't block the UI.
   */
  private var backgroundThread: HandlerThread? = null

  /**
   * A [Handler] for running tasks in the background.
   */
  private var backgroundHandler: Handler? = null

  /**
   * An [ImageReader] that handles image capture.
   */
  private var imageReader: ImageReader? = null

  /**
   * [CaptureRequest.Builder] for the camera preview
   */
  private var previewRequestBuilder: CaptureRequest.Builder? = null

  /**
   * [CaptureRequest] generated by [.previewRequestBuilder]
   */
  private var previewRequest: CaptureRequest? = null

  /**
   * A [Semaphore] to prevent the app from exiting before closing the camera.
   */
  private val cameraOpenCloseLock = Semaphore(1)

  /**
   * A [CameraCaptureSession.CaptureCallback] that handles events related to capture.
   */
  private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

    override fun onCaptureProgressed(
      session: CameraCaptureSession,
      request: CaptureRequest,
      partialResult: CaptureResult
    ) {
    }

    override fun onCaptureCompleted(
      session: CameraCaptureSession,
      request: CaptureRequest,
      result: TotalCaptureResult
    ) {
    }
  }

  private val requiredPermissions: Array<String>
    get() {
      val activity = activity
      return try {
        val info = activity
            .packageManager
            .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        val ps = info.requestedPermissions
        if (ps != null && ps.isNotEmpty()) {
          ps
        } else {
          arrayOf()
        }
      } catch (e: Exception) {
        arrayOf()
      }

    }

  /**
   * Takes photos and classify them periodically.
   */
  private val periodicClassify = object : Runnable {
    override fun run() {
      synchronized(lock) {
        if (runClassifier) {
          classifyFrame()
        }
      }
      backgroundHandler!!.post(this)
    }
  }

  /**
   * Shows a [Toast] on the UI thread for the classification results.
   *
   * @param text The message to show
   */
  private fun showToast(text: String) {
    val activity = activity
    activity?.runOnUiThread {
      textView!!.text = text
      drawView!!.invalidate()
    }
  }

  /**
   * Layout the preview and buttons.
   */
  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    return inflater.inflate(R.layout.fragment_camera2_basic, container, false)
  }

  /**
   * Connect the buttons to their event handler.
   */
  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?
  ) {
    textureView = view.findViewById(R.id.texture)
    textView = view.findViewById(R.id.text)
    layoutFrame = view.findViewById(R.id.layout_frame)
    drawView = view.findViewById(R.id.drawview)
    layoutBottom = view.findViewById(R.id.layout_bottom)
//    if (classifier != null)
//      drawView!!.setImgSize(classifier!!.imageSizeX, classifier!!.imageSizeY)


    val metrics = DisplayMetrics()
    activity.windowManager.defaultDisplay.getMetrics(metrics)
    mScreenDensity = metrics.densityDpi

    //DISPLAY_HEIGHT=metrics.heightPixels
    //DISPLAY_WIDTH=metrics.widthPixels

    mMediaRecorder= MediaRecorder()
    mProjectionManager=activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

  }

  override fun onStop() {
    super.onStop()
  }
  /**
   * Load the model and labels.
   */
  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    try {
      // create either a new ImageClassifierQuantizedMobileNet or an ImageClassifierFloatInception
      //      classifier = new ImageClassifierQuantizedMobileNet(getActivity());
      classifier = ImageClassifierFloatInception.create(activity)
      if (drawView != null)
        drawView!!.setImgSize(classifier!!.imageSizeX, classifier!!.imageSizeY)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to initialize an image classifier.", e)
    }

    startBackgroundThread()
  }

  override fun onResume() {
    super.onResume()
    startBackgroundThread()

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (textureView!!.isAvailable) {
      openCamera(textureView!!.width, textureView!!.height)
    } else {
      textureView!!.surfaceTextureListener = surfaceTextureListener
    }
  }

  override fun onPause() {
    closeCamera()
    stopBackgroundThread()
    super.onPause()
  }

  override fun onDestroy() {
    classifier!!.close()
    mBroadcastReceiver?.let { LocalBroadcastManager.getInstance(activity).unregisterReceiver(it) }

    //takendra
    mMediaRecorder!!.stop()
    mMediaRecorder!!.reset()
    Log.v(TAG, "Stopping Recording")
    stopScreenSharing()

    super.onDestroy()

  }


  /**
   * Sets up member variables related to camera.
   *
   * @param width  The width of available size for camera preview
   * @param height The height of available size for camera preview
   */
  private fun setUpCameraOutputs(
    width: Int,
    height: Int
  ) {
    val activity = activity
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)

        // We don't use a front facing camera in this sample.
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue
        }

        val map =
          characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

        // // For still image captures, we use the largest available size.
        val largest = Collections.max(
            Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea()
        )
        imageReader = ImageReader.newInstance(
            largest.width, largest.height, ImageFormat.JPEG, /*maxImages*/ 2
        )

        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        val displayRotation = activity.windowManager.defaultDisplay.rotation

        /* Orientation of the camera sensor */
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        var swappedDimensions = false
        when (displayRotation) {
          Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation == 90 || sensorOrientation == 270) {
            swappedDimensions = true
          }
          Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation == 0 || sensorOrientation == 180) {
            swappedDimensions = true
          }
          else -> Log.e(TAG, "Display rotation is invalid: $displayRotation")
        }

        val displaySize = Point()
        activity.windowManager.defaultDisplay.getSize(displaySize)
        var rotatedPreviewWidth = width
        var rotatedPreviewHeight = height
        var maxPreviewWidth = displaySize.x
        var maxPreviewHeight = displaySize.y

        if (swappedDimensions) {
          rotatedPreviewWidth = height
          rotatedPreviewHeight = width
          maxPreviewWidth = displaySize.y
          maxPreviewHeight = displaySize.x
        }

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
          maxPreviewWidth = MAX_PREVIEW_WIDTH
        }

        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
          maxPreviewHeight = MAX_PREVIEW_HEIGHT
        }

        previewSize = chooseOptimalSize(
            map.getOutputSizes(SurfaceTexture::class.java),
            rotatedPreviewWidth,
            rotatedPreviewHeight,
            maxPreviewWidth,
            maxPreviewHeight,
            largest
        )

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          layoutFrame!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
          textureView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
          drawView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
        } else {
          layoutFrame!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
          textureView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
          drawView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
        }

        this.cameraId = cameraId
        return
      }
    } catch (e: CameraAccessException) {
      Log.e(TAG, "Failed to access Camera", e)
    } catch (e: NullPointerException) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the
      // device this code runs.
      ErrorDialog.newInstance(getString(R.string.camera_error))
          .show(childFragmentManager, FRAGMENT_DIALOG)
    }

  }

  /**
   * Opens the camera specified by [Camera2BasicFragment.cameraId].
   */
  @SuppressLint("MissingPermission")
  private fun openCamera(
    width: Int,
    height: Int
  ) {
    if (!checkedPermissions && !allPermissionsGranted()) {
      FragmentCompat.requestPermissions(this, requiredPermissions, PERMISSIONS_REQUEST_CODE)
      return
    } else {
      checkedPermissions = true
    }
    setUpCameraOutputs(width, height)
    configureTransform(width, height)
    val activity = activity
    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw RuntimeException("Time out waiting to lock camera opening.")
      }
      manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
    } catch (e: CameraAccessException) {
      Log.e(TAG, "Failed to open Camera", e)
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera opening.", e)
    }

  }

  private fun allPermissionsGranted(): Boolean {
    for (permission in requiredPermissions) {
      if (ContextCompat.checkSelfPermission(
              activity, permission
          ) != PackageManager.PERMISSION_GRANTED
      ) {
        return false
      }
    }
    return true
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  /**
   * Closes the current [CameraDevice].
   */
  private fun closeCamera() {
    try {
      cameraOpenCloseLock.acquire()
      if (null != captureSession) {
        captureSession!!.close()
        captureSession = null
      }
      if (null != cameraDevice) {
        cameraDevice!!.close()
        cameraDevice = null
      }
      if (null != imageReader) {
        imageReader!!.close()
        imageReader = null
      }
    } catch (e: InterruptedException) {
      throw RuntimeException("Interrupted while trying to lock camera closing.", e)
    } finally {
      cameraOpenCloseLock.release()
    }
  }

  /**
   * Starts a background thread and its [Handler].
   */
  private fun startBackgroundThread() {
    backgroundThread = HandlerThread(HANDLE_THREAD_NAME)
    backgroundThread!!.start()
    backgroundHandler = Handler(backgroundThread!!.looper)
    synchronized(lock) {
      runClassifier = true
    }
    backgroundHandler!!.post(periodicClassify)
  }

  /**
   * Stops the background thread and its [Handler].
   */
  private fun stopBackgroundThread() {
    backgroundThread!!.quitSafely()
    try {
      backgroundThread!!.join()
      backgroundThread = null
      backgroundHandler = null
      synchronized(lock) {
        runClassifier = false
      }
    } catch (e: InterruptedException) {
      Log.e(TAG, "Interrupted when stopping background thread", e)
    }

  }

  /**
   * Creates a new [CameraCaptureSession] for camera preview.
   */
  private fun createCameraPreviewSession() {
    try {
      val texture = textureView!!.surfaceTexture!!

      // We configure the size of default buffer to be the size of camera preview we want.
      texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

      // This is the output Surface we need to start preview.
      val surface = Surface(texture)

      // We set up a CaptureRequest.Builder with the output Surface.
      previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      previewRequestBuilder!!.addTarget(surface)

      // Here, we create a CameraCaptureSession for camera preview.
      cameraDevice!!.createCaptureSession(
          Arrays.asList(surface),
          object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
              // The camera is already closed
              if (null == cameraDevice) {
                return
              }

              // When the session is ready, we start displaying the preview.
              captureSession = cameraCaptureSession
              try {
                // Auto focus should be continuous for camera preview.
                previewRequestBuilder!!.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

                // Finally, we start displaying the camera preview.
                previewRequest = previewRequestBuilder!!.build()
                captureSession!!.setRepeatingRequest(
                    previewRequest!!, captureCallback, backgroundHandler
                )
              } catch (e: CameraAccessException) {
                Log.e(TAG, "Failed to set up config to capture Camera", e)
              }

            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
              showToast("Failed")
            }
          }, null
      )
    } catch (e: CameraAccessException) {
      Log.e(TAG, "Failed to preview Camera", e)
    }

  }

  /**
   * Configures the necessary [android.graphics.Matrix] transformation to `textureView`. This
   * method should be called after the camera preview size is determined in setUpCameraOutputs and
   * also the size of `textureView` is fixed.
   *
   * @param viewWidth  The width of `textureView`
   * @param viewHeight The height of `textureView`
   */
  private fun configureTransform(
    viewWidth: Int,
    viewHeight: Int
  ) {
    val activity = activity
    if (null == textureView || null == previewSize || null == activity) {
      return
    }
    val rotation = activity.windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
      val scale = Math.max(
          viewHeight.toFloat() / previewSize!!.height,
          viewWidth.toFloat() / previewSize!!.width
      )
      matrix.postScale(scale, scale, centerX, centerY)
      matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180f, centerX, centerY)
    }
    textureView!!.setTransform(matrix)
  }

  /**
   * Classifies a frame from the preview stream.
   */
  private fun classifyFrame() {
    if (classifier == null || activity == null || cameraDevice == null) {
      showToast("Uninitialized Classifier or invalid context.")
      return
    }
    val bitmap = textureView!!.getBitmap(classifier!!.imageSizeX, classifier!!.imageSizeY)
    val textToShow = classifier!!.classifyFrame(bitmap)
    bitmap.recycle()


    drawView!!.setDrawPoint(classifier!!.mPrintPointArray!!, 0.5f)

    //Log.d("DrawPoints",""+classifier!!.mPrintPointArray!!)

    Log.d("DrawPoints","=======DrawPoints Read starts=====")
    var a:Int=0
    var filename = StringBuilder()
    var xCoordinates= StringBuilder()
    var yCoordinates=StringBuilder()
    for(i in 0 until classifier!!.mPrintPointArray!!.size)
    {
        var filterLabelProbArray=classifier!!.mPrintPointArray!![i]
      for (j in 0 until filterLabelProbArray.size)
      {
        Log.d("DrawPoints",""+filterLabelProbArray[j])
        if(i==0)
          xCoordinates.append(filterLabelProbArray[j])
        if(i==1)
          yCoordinates.append(filterLabelProbArray[j])


        filename.append(filterLabelProbArray[j]).append(" ")
        if(filterLabelProbArray[j]>0)
          a= filterLabelProbArray[j].roundToInt()
      }


    }
    Log.d("DrawPoints","=======DrawPoints Read ends=====")
    //takendra got the points in mPrintPointArray


    showToast(textToShow)
    activity.runOnUiThread()
    {
      if(!startRecording) {
        initRecorder()
        shareScreen()
        //deleteOldCoordinatesFile()
        deleteAppDirectory()
        startRecording=true
      }
    }

    //val bitmap1: Bitmap? = getScreenShotFromView(drawView!!.rootView.fi)
    val bitmap1: Bitmap? = takeScreenshot()
    bitmap1?.let {
      if(a>0) {
        this.saveMediaToStorage(it, fileName = filename.toString().plus("($textToShow)"))
        createTextFile(filename.toString().plus("($textToShow)"),frameTime = textToShow,xCoordinates = xCoordinates.toString(),yCoordinates = yCoordinates.toString())
        Log.d("DrawPoints",filename.toString())
      }
    }
  }
  private fun initRecorder() {
    try {
      mMediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
      mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
      mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
      mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
      mMediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
      mMediaRecorder!!.setOutputFile(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          .toString() + "/YogaVideos.mp4"
      )
      mMediaRecorder!!.setVideoSize(
        DISPLAY_WIDTH,
        DISPLAY_HEIGHT
      )
      mMediaRecorder!!.setVideoEncodingBitRate(512 * 1000)
      mMediaRecorder!!.setVideoFrameRate(30)
      val rotation: Int = activity.windowManager.defaultDisplay.rotation
      val orientation: Int = ORIENTATIONS.get(rotation + 90)
      mMediaRecorder!!.setOrientationHint(orientation)
      mMediaRecorder!!.prepare()
    } catch (e: IOException) {
      e.printStackTrace()
    }
  }
  private fun shareScreen() {
    if (mMediaProjection == null) {
      startActivityForResult(
        mProjectionManager!!.createScreenCaptureIntent(),
        REQUESTCODE)
      return
    }
    mVirtualDisplay = createVirtualDisplay()
    mMediaRecorder!!.start()
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)

    if (resultCode != RESULT_OK) {
      Toast.makeText(
        activity,
        "Screen Cast Permission Denied", Toast.LENGTH_SHORT
      ).show()
      return
    }
    Log.d(TAG,"Permission given ")
    val intent = Intent(activity, MyService::class.java)
    // For Android O and newer, you must start a foreground service
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      activity.startForegroundService(intent)
    } else {
      activity.startService(intent)
    }

    mBroadcastReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(RECEIVER_MESSAGE)
        Log.d(TAG, message!!)
        if (message.equals("startMediaRecorder", ignoreCase = true)) {
          startMediaRecorder(resultCode, data!!)
        }
      }
    }


    LocalBroadcastManager.getInstance(activity).registerReceiver(mBroadcastReceiver as BroadcastReceiver, IntentFilter(RECEIVER_INTENT))


  }
  private fun startMediaRecorder(resultCode: Int, data: Intent) {
    mMediaProjectionCallback = MediaProjectionCallback()
    mMediaProjection = mProjectionManager!!.getMediaProjection(resultCode, data)
    mMediaProjection!!.registerCallback(mMediaProjectionCallback, null)
    mVirtualDisplay = createVirtualDisplay()
    mMediaRecorder!!.start()
  }
  private inner class MediaProjectionCallback : MediaProjection.Callback() {
    override fun onStop() {

      mMediaRecorder!!.stop()
      mMediaRecorder!!.reset()
      Log.d(TAG, "Recording Stopped")
      mMediaProjection = null
      stopScreenSharing()

    }
  }

  private fun stopScreenSharing() {
    if (mVirtualDisplay == null) {
      return
    }
    mVirtualDisplay!!.release()
    //mMediaRecorder.release(); //If used: mMediaRecorder object cannot
    // be reused again
    destroyMediaProjection()
  }

  override fun onDestroyView() {
    super.onDestroyView()
  }
  private fun destroyMediaProjection() {
    if (mMediaProjection != null) {
      mMediaProjection!!.unregisterCallback(mMediaProjectionCallback)
      mMediaProjection!!.stop()
      mMediaProjection = null
    }
    Log.d(TAG, "MediaProjection Stopped")
  }
  private fun createVirtualDisplay(): VirtualDisplay? {
    return mMediaProjection!!.createVirtualDisplay(
      "CameraActivity",
      DISPLAY_WIDTH,
      DISPLAY_HEIGHT,
      mScreenDensity,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      mMediaRecorder!!.surface,
      null /*Callbacks*/,
      null /*Handler*/
    )
  }



  private fun getScreenShotFromView(v: View): Bitmap? {
    // create a bitmap object
    var screenshot: Bitmap? = null
    try {
      // inflate screenshot object
      // with Bitmap.createBitmap it
      // requires three parameters
      // width and height of the view and
      // the background color
      screenshot = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
      // Now draw this bitmap on a canvas
      val canvas = Canvas(screenshot)
      v.draw(canvas)
    } catch (e: Exception) {
      Log.e("GFG", "Failed to capture screenshot because:" + e.message)
    }
    // return the bitmap
    return screenshot
  }
  private fun deleteAppDirectory()
  {
    val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val appDirectory=File(downloadDirectory,folderName)
    appDirectory.deleteRecursively()
  }

private fun createTextFile(frameFileName:String,frameTime:String,xCoordinates:String,yCoordinates:String) {

  val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
  val appDirectory=File(downloadDirectory,folderName)
  if(!appDirectory.exists())
    appDirectory.mkdir()

  //val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Coordinates.txt")
  val f = File(appDirectory, "Coordinates.txt")
  f.appendText("xCoordinates=$xCoordinates\n")
  f.appendText("yCoordinates=$yCoordinates\n")
  f.appendText("frameFileName=$frameFileName\n")
  f.appendText("Epoch=$frameTime\n")
  f.appendText("----------------------------------------------------------\n")
}
  private fun takeScreenshot(): Bitmap?  {
    return try {
      // create bitmap screen capture
      val v1: View = activity.window.decorView.rootView
      v1.isDrawingCacheEnabled = true
      val bitmap = Bitmap.createBitmap(v1.drawingCache)
      v1.isDrawingCacheEnabled = false
      bitmap
    } catch (e: Throwable) {
      // Several error may come out with file handling or DOM
      e.printStackTrace()
      null
    }
  }
  // this method saves the image to gallery
  private fun saveMediaToStorage(bitmap: Bitmap,fileName:String) {
    // Generating a file name
    //val filename = "${System.currentTimeMillis()}.jpg"
    val filename = "${fileName}.jpg"

    // Output stream
    var fos: OutputStream? = null

    // For devices running android >= Q
  /*  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // getting the contentResolver
      this.contentResolver?.also { resolver ->

        // Content resolver will process the contentvalues
        val contentValues = ContentValues().apply {

          // putting file information in content values
          put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
          put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        // Inserting the contentValues to
        // contentResolver and getting the Uri
        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        // Opening an outputstream with the Uri that we got
        fos = imageUri?.let { resolver.openOutputStream(it) }
      }
    } else {*/
      // These for devices running on android < Q
     // val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
       val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
       val appDirectory=File(downloadDirectory,folderName)
       if(!appDirectory.exists())
         appDirectory.mkdir()

     val image = File(appDirectory, filename)
      fos = FileOutputStream(image)
    //}

    fos?.use {
      // Finally writing the bitmap to the output stream that we opened
      bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)

      val activity = activity
      activity?.runOnUiThread {
        Toast.makeText(activity , "Captured View and saved to Gallery" , Toast.LENGTH_SHORT).show()
      }
    }
  }



  /**
   * Compares two `Size`s based on their areas.
   */
  private class CompareSizesByArea : Comparator<Size> {

    override fun compare(
      lhs: Size,
      rhs: Size
    ): Int {
      // We cast here to ensure the multiplications won't overflow
      return java.lang.Long.signum(
          lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height
      )
    }
  }

  /**
   * Shows an error message dialog.
   */
  class ErrorDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
      val activity = activity
      return AlertDialog.Builder(activity)
          .setMessage(arguments.getString(ARG_MESSAGE))
          .setPositiveButton(
              android.R.string.ok
          ) { dialogInterface, i -> activity.finish() }
          .create()
    }

    companion object {

      private val ARG_MESSAGE = "message"

      fun newInstance(message: String): ErrorDialog {
        val dialog = ErrorDialog()
        val args = Bundle()
        args.putString(ARG_MESSAGE, message)
        dialog.arguments = args
        return dialog
      }
    }
  }

  companion object {

    /**
     * Tag for the [Log].
     */
    const val RECEIVER_INTENT: String = "RECEIVER_INTENT"
    const val RECEIVER_MESSAGE:String = "RECEIVER_MESSAGE"

    private var DISPLAY_WIDTH = 720
    private var DISPLAY_HEIGHT = 1520

  private val ORIENTATIONS=SparseIntArray()
    init {
      ORIENTATIONS.append(Surface.ROTATION_0, 90)
      ORIENTATIONS.append(Surface.ROTATION_90, 0)
      ORIENTATIONS.append(Surface.ROTATION_180, 270)
      ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }

    private const val TAG = "TfLiteCameraDemo"

    private const val FRAGMENT_DIALOG = "dialog"

    private const val HANDLE_THREAD_NAME = "CameraBackground"

    private const val PERMISSIONS_REQUEST_CODE = 1

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private const val MAX_PREVIEW_WIDTH = 1920

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private const val MAX_PREVIEW_HEIGHT = 1080

    /**
     * Resizes image.
     *
     *
     * Attempting to use too large a preview size could  exceed the camera bus' bandwidth limitation,
     * resulting in gorgeous previews but the storage of garbage capture data.
     *
     *
     * Given `choices` of `Size`s supported by a camera, choose the smallest one that is
     * at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size, and
     * whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
      choices: Array<Size>,
      textureViewWidth: Int,
      textureViewHeight: Int,
      maxWidth: Int,
      maxHeight: Int,
      aspectRatio: Size
    ): Size {

      // Collect the supported resolutions that are at least as big as the preview Surface
      val bigEnough = ArrayList<Size>()
      // Collect the supported resolutions that are smaller than the preview Surface
      val notBigEnough = ArrayList<Size>()
      val w = aspectRatio.width
      val h = aspectRatio.height
      for (option in choices) {
        if (option.width <= maxWidth
            && option.height <= maxHeight
            && option.height == option.width * h / w
        ) {
          if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
            bigEnough.add(option)
          } else {
            notBigEnough.add(option)
          }
        }
      }

      // Pick the smallest of those big enough. If there is no one big enough, pick the
      // largest of those not big enough.
      return when {
        bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
        notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
        else -> {
          Log.e(TAG, "Couldn't find any suitable preview size")
          choices[0]
        }
      }
    }

    fun newInstance(): Camera2BasicFragment {
      return Camera2BasicFragment()
    }
  }
}
