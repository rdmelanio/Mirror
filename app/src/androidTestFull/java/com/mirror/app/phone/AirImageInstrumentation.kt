package com.mirror.app.phone

import android.app.Instrumentation
import android.os.Bundle
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/** No third-party runner. Runs synthetic images through both real Android containers and native model.
 * adb shell am instrument -w com.mirror.app.test/com.mirror.app.phone.AirImageInstrumentation
 * Requires arm64 Android hardware; no camera or pairing permissions are needed. */
class AirImageInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val result=Bundle();val raw=AirRawFrame();var count=0;var model:GestureRecognizer?=null
        try {
            model=GestureRecognizer.createFromOptions(targetContext,GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()).setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
            var time=0L
            for((w,h) in listOf(640 to 360,480 to 270,360 to 640)) for(rotation in listOf(0,90,180,270)) {
                raw.loadNv21(ByteArray(w*h*3/2) { if(it<w*h) 32 else 128.toByte() },w,h,rotation)
                for(roi in listOf(AirRoi.FULL,AirRoi(.13f,.19f,.71f,.83f),AirRoi(.2f,.2f,.63f,.71f),AirRoi.FULL)) for(bitmap in listOf(false,true)) {
                    val image=raw.image(640,bitmap,roi)
                    val owned=if(bitmap) BitmapExtractor.extract(image) else null
                    try {
                        if(!bitmap) {
                            val bytes=ByteBufferExtractor.extract(image)
                            check(bytes.capacity()==AirPixels.rgbStride(image.width)*image.height)
                            check(bytes.position()==0 && bytes.limit()==bytes.capacity())
                        } else check(owned!=null && !owned.isRecycled)
                        model.recognizeForVideo(image,++time);count++
                    } finally { image.close() }
                    if(bitmap) check(owned!!.isRecycled)
                }
            }
            result.putString("stream","PASS: $count native recognizer calls; actual Direct RGB and Bitmap, rotations and variable ROI\n")
            finish(android.app.Activity.RESULT_OK,result)
        } catch(e:Throwable) {
            result.putString("stream","FAIL: ${e.javaClass.simpleName}: ${e.message}\n");finish(android.app.Activity.RESULT_CANCELED,result)
        } finally { raw.close();model?.close() }
    }
}
