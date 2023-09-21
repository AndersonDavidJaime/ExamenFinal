package com.example.realtimeexamefinal;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;
import java.util.ArrayList;


import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ThumbnailUtils;
import android.os.AsyncTask;
import android.os.Build;

import android.os.Handler;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;


import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.example.realtimeexamefinal.ml.ModelUnquant;


import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.Arrays;
import java.util.Locale;
import java.util.Scanner;

import camerax.CameraConnectionFragment;
import camerax.ImageUtils;


public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener, ImageReader.OnImageAvailableListener {
    ArrayList<String> permisosNoAprobados;
    TextView txtLugar;
    String[] labels;
    TextToSpeech textToSpeech;
    private static final int IMAGE_SIZE = 224;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtLugar = findViewById(R.id.txtresultados);
        textToSpeech = new TextToSpeech(this, this);
        leerlabels();
        checkpermisos();
    }
    public void checkpermisos(){
        ArrayList<String> perms = new ArrayList<String>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        perms.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE);
        permisosNoAprobados = getPermisosNoAprobados(perms);
        requestPermissions(permisosNoAprobados.toArray(new String[permisosNoAprobados.size()]), 100);
    }
    public void leerlabels(){
        labels = new String[1001];
        int i = 0;
        try {
            Scanner scanner = new Scanner(getAssets().open("labels.txt"));
            while (scanner.hasNextLine() && i < 1001) {
                String linea = scanner.nextLine();
                labels[i] = linea;
                i++;
            }
            scanner.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for(int i=0; i<permissions.length; i++){
            if(permissions[i].equals(Manifest.permission.CAMERA)){
                this.setFragment();
            }else
            {
                this.setFragment();
            }
        }
    }
    public ArrayList<String> getPermisosNoAprobados(ArrayList<String> listaPermisos) {
        ArrayList<String> list = new ArrayList<String>();
        boolean habilitado;

        if (Build.VERSION.SDK_INT >= 23) {
            for (String permiso : listaPermisos) {
                if (checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
                    list.add(permiso);
                    habilitado = false;
                } else {
                    habilitado = true;
                }
            }
        }

        return list;
    }
    int previewHeight = 0,previewWidth = 0;
    int sensorOrientation;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight();    previewWidth = size.getWidth();
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this,   R.layout.fragmento_camara,     new Size(640, 480));

        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        getFragmentManager().beginTransaction().replace(R.id.fragmento, fragment).commit();
    }
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;
    @Override
    public void onImageAvailable(ImageReader imageReader) {
        if (previewWidth == 0 || previewHeight == 0)           return;
        if (rgbBytes == null)    rgbBytes = new int[previewWidth * previewHeight];
        try {
            final Image image = imageReader.acquireLatestImage();
            if (image == null)    return;
            if (isProcessingFrame) {           image.close();            return;         }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            imageConverter =  new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888( yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth,  previewHeight,
                            yRowStride,uvRowStride, uvPixelStride,rgbBytes);
                }
            };
            postInferenceCallback =      new Runnable() {
                @Override
                public void run() {  image.close(); isProcessingFrame = false;  }
            };

            ProcesarImagenyModelo();

        } catch (final Exception e) {    }

    }
    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(new Locale("es", "MX"));
            HablarPorTexto();
        }
    }
    @Override
    public void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
    private void HablarPorTexto() {
        String text = txtLugar.getText().toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);}
        else {textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);}
    }
    private void ProcesarImagenyModelo() {
        imageConverter.run();
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        try {
            Bitmap bitmap=Bitmap.createScaledBitmap(rgbFrameBitmap,224,224,true);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3);
            byteBuffer.order(ByteOrder.nativeOrder());
            int[] intValues = new int[IMAGE_SIZE * IMAGE_SIZE];
            bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int pixel = 0;
            for (int i = 0; i < IMAGE_SIZE; i++) {
                for (int j = 0; j < IMAGE_SIZE; j++) {
                    int val = intValues[pixel++];
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
                }
            }
            ModelUnquant model = ModelUnquant.newInstance(getApplicationContext());
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, IMAGE_SIZE, IMAGE_SIZE, 3}, DataType.FLOAT32);
            inputFeature0.loadBuffer(byteBuffer);
            ModelUnquant.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    int maxIndex = 0;
                    float maxValue = outputFeature0.getFloatArray()[0];
                    for (int i = 1; i < outputFeature0.getFloatArray().length; i++) {
                        if (outputFeature0.getFloatArray()[i] > maxValue) {
                            maxValue = outputFeature0.getFloatArray()[i];
                            maxIndex = i;
                        }
                    }
                    txtLugar.setText(labels[maxIndex] + " ");
                }
            });
            new TareaVoz(this::HablarPorTexto).execute(1500);
            model.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        postInferenceCallback.run();
    }
    public class TareaVoz extends AsyncTask<Integer, Void, Void> {
        private final Runnable onComplete;
        public TareaVoz(Runnable onComplete) {
            this.onComplete = onComplete;
        }
        @Override
        protected Void doInBackground(Integer... params) {
            int delayMillis = params.length > 0 ? params[0] : 2000;
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }
        @Override
        protected void onPostExecute(Void result) {
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }
}