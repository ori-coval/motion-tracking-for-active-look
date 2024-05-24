package com.activelook.demo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.activelook.activelooksdk.Glasses;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.List;

public class ObjectTracking extends AppCompatActivity {

    public Glasses connectedGlasses;
    private ImageView imageView;
    private Handler handler;
    private static final String TAG = "MainActivity";
    private static final String STREAM_URL = "http://10.100.102.169:81/stream";

    private int lastCenterX = -1;
    private int lastCenterY = -1;

    private static final int ORIGINAL_WIDTH = 480;
    private static final int ORIGINAL_HEIGHT = 320;
    private static final int SCREEN_WIDTH = 304;
    private static final int SCREEN_HEIGHT = 256;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_object_tracking);

        Intent intent = this.getIntent();
        this.connectedGlasses = intent.getExtras().getParcelable("connectedGlasses");

        imageView = findViewById(R.id.imageView);
        handler = new Handler(Looper.getMainLooper());

        Button clearButton = findViewById(R.id.clear);
        clearButton.setOnClickListener(v -> connectedGlasses.clear());

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        startStreamProcessing();
    }

    private void startStreamProcessing() {
        Python py = Python.getInstance();
        PyObject pyModule = py.getModule("motion_tracking");
        PyGeneratorWrapper generatorWrapper = new PyGeneratorWrapper(pyModule.callAttr("process_stream", STREAM_URL, 9379, 22484));

        new Thread(() -> {
            while (true) {
                try {
                    PyObject frameDataObj = Python.getInstance().getBuiltins().callAttr("next", generatorWrapper.getGenerator());
                    if (frameDataObj == null) {
                        Log.d(TAG, "End of stream");
                        break; // End of generator
                    }

                    // Convert PyObject to list of PyObject items
                    List<PyObject> frameDataList = frameDataObj.asList();
                    PyObject frameDataPy = frameDataList.get(0);
                    PyObject centersPy = frameDataList.get(1);

                    byte[] frameData = frameDataPy.toJava(byte[].class);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                    Log.d(TAG, "Received frame data of length: " + frameData.length);  // Log frame data length

                    handler.post(() -> {
                        imageView.setImageBitmap(bitmap);
                        Log.d(TAG, "Bitmap set to ImageView");  // Log image display

                        // Log centers of detected objects and draw lines
                        List<PyObject> centersList = centersPy.asList();
                        for (PyObject center : centersList) {
                            int originalCenterX = center.asList().get(0).toInt();
                            int originalCenterY = center.asList().get(1).toInt();

                            // Scale coordinates
                            int centerX = scaleCoordinate(originalCenterX, ORIGINAL_WIDTH, SCREEN_WIDTH);
                            int centerY = scaleCoordinate(originalCenterY, ORIGINAL_HEIGHT, SCREEN_HEIGHT);

                            Log.d(TAG, "Detected object center at: (" + centerX + ", " + centerY + ")");

                            if (lastCenterX != -1 && lastCenterY != -1) {
                                connectedGlasses.line((short) lastCenterX, (short) lastCenterY, (short) centerX, (short) centerY);
                            }

                            lastCenterX = centerX;
                            lastCenterY = centerY;
                        }
                    });
                } catch (Exception e) {
                    if (e.getMessage().contains("StopIteration")) {
                        Log.d(TAG, "StopIteration caught, restarting stream processing");
                        generatorWrapper.setGenerator(pyModule.callAttr("process_stream", STREAM_URL));  // Restart the stream
                    } else {
                        Log.e(TAG, "Error processing stream", e);  // Log any other exceptions
                        break;
                    }
                }
            }
        }).start();
    }

    private int scaleCoordinate(int originalCoord, int originalMax, int screenMax) {
        return (originalCoord * screenMax) / originalMax;
    }

    public class PyGeneratorWrapper {
        private PyObject generator;

        public PyGeneratorWrapper(PyObject generator) {
            this.generator = generator;
        }

        public PyObject getGenerator() {
            return generator;
        }

        public void setGenerator(PyObject generator) {
            this.generator = generator;
        }
    }
}
