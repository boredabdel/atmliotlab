/*
 * Copyright 2017 The Android Things Samples Authors.
 *
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
package com.example.androidthings.imageclassifier;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.example.androidthings.imageclassifier.classifier.Recognition;
import com.example.androidthings.imageclassifier.classifier.TensorFlowImageClassifier;
import com.example.androidthings.imageclassifier.cloud.iotcore.CloudIotOptions;
import com.example.androidthings.imageclassifier.cloud.iotcore.MQTTPublisher;
import com.example.androidthings.imageclassifier.cloud.iotcore.MqttAuthentication;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.androidthings.imageclassifier.cloud.pubsub.CloudPublisher;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.storage.StorageScopes;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.Storage;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImageClassifierActivity extends Activity implements ImageReader.OnImageAvailableListener {
    private static final String GCS_KEY_FILE = "sa-key.p12";
    private static final String BUCKET_NAME = "at-test-upload01";
    private static final String REGISTRY_ID = "myregistry";
    private static final String DEVICE_ID = "device01";
    private static final String CLOUD_REGION = "europe-west1";
    private static final String PROJECT_ID =  "iot-ml-bootcamp-2018";
    private static final String ACCOUNT_ID = "iot-gcs-sa@iot-ml-bootcamp-2018.iam.gserviceaccount.com";


    private static final String TAG = "ImageClassifierActivity";
    private static final int PREVIEW_IMAGE_WIDTH = 640;
    private static final int PREVIEW_IMAGE_HEIGHT = 480;
    private static final int TF_INPUT_IMAGE_WIDTH = 224;
    private static final int TF_INPUT_IMAGE_HEIGHT = 224;
    private static Storage sStorage;
    private static File gcsKeyFile;

    private static final String DEFAULT_LABELS_FILE = "labels.txt";
    private static final String DEFAULT_MODEL_FILE = "model.tflite";


    /* Key code used by GPIO button to trigger image capture */
    private static final int SHUTTER_KEYCODE = KeyEvent.KEYCODE_CAMERA;

    private ImagePreprocessor mImagePreprocessor;
    private CameraHandler mCameraHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;
    private String localFilePathInCache;
    private String gcsFilePath;
    private CloudPublisher mPublisher;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private ImageView mImage;
    private TextView mResultText;
    private LinearLayout mSendToCloudLayout;
    private Button mSendToCloudButton;
    private Button mConfirmSendButton;
    private RadioGroup mLabelsRadio;
    private MqttAuthentication mqttAuth;
    private MqttClient mqttClient;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private static MqttCallback mCallback;
    private static JSONObject mJsonObject;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{
            Log.i(TAG, "Reading GCS Service Account Key file");
            AssetManager assets = getAssets();
            gcsKeyFile = readFileFromStream(assets.open(GCS_KEY_FILE));
        }catch(Exception e){
            Log.i(TAG, "Failed Reading GCS Service Account Key file");
        }
        setContentView(R.layout.activity_camera);
        mImage = findViewById(R.id.imageView);
        mResultText = findViewById(R.id.resultText);
        mSendToCloudLayout = findViewById(R.id.sendToCloudLayout);
        mLabelsRadio = findViewById(R.id.labelButtons);
        mSendToCloudButton = findViewById(R.id.sendToCloudBtn);
        mConfirmSendButton = findViewById(R.id.confirmSendBtn);
        init();
    }

    private void init() {
        // Initialize MQTT
        mqttAuth = new MqttAuthentication();
        mqttAuth.initialize();

        // Export Certificate to Storage
        if( Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Log.i(TAG, "External Storage Stat " + Environment.getExternalStorageState());
            try {
                Log.i(TAG, "Exporting key to " + Environment.getExternalStorageDirectory());
                mqttAuth.exportPublicKey(new File(Environment.getExternalStorageDirectory(),
                        "cloud_iot_auth_certificate.pem"));
            } catch (GeneralSecurityException | IOException e) {
                if( e instanceof FileNotFoundException && e.getMessage().contains("Permission denied")) {
                    Log.e(TAG, "Unable to export certificate, may need to reboot to receive WRITE permissions?", e);
                } else {
                    Log.e(TAG, "Unable to export certificate", e);
                }
            }
        }
        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);

        // Listen to config changes.
        CloudIotOptions cloudIotOptions =
                new CloudIotOptions(PROJECT_ID, REGISTRY_ID, DEVICE_ID, CLOUD_REGION);
        mPublisher = new MQTTPublisher(cloudIotOptions);
        mqttClient = mPublisher.getMqttClient();
        try {
            Log.i(TAG, "Attaching callback for config change");
            attachCallback(mqttClient, DEVICE_ID);
        } catch (MqttException e){
            Log.e(TAG, "Exception error " + e);
        }

    }


    /** Attaches the callback used when configuration changes occur. */
    public  void attachCallback(MqttClient client, String deviceId) throws MqttException {
        mCallback = new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // Do nothing...
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                String payload = new String(message.getPayload());
                if (! payload.isEmpty()){
                    try {
                        mJsonObject = new JSONObject(payload);
                        Log.i(TAG, "New Config Received, processing");
                        mReady.set(false);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() { mResultText.setText("Config change received, processing....");
                            }
                        });
                        mBackgroundHandler.post(mBackgroundGetModelFromGCS);
                    }catch (JSONException ex){
                        Log.e(TAG, "Failed processing config payload " + payload);
                    }
                }

            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Do nothing;
            }
        };

        String configTopic = String.format("/devices/%s/config", deviceId);
        client.subscribe(configTopic, 1);
        client.setCallback(mCallback);
    }

    private Runnable mBackgroundGetModelFromGCS = new Runnable() {
        @Override
        public void run() {
            //Send the Image to GCS.
            Log.i(TAG, "Getting new Model and Label from GCS ...");
            String bucketName = "";
            String modelFileName = "";
            String labelsFileName = "";
            File cachePath = new File(getCacheDir().toString());
            try {
                bucketName = mJsonObject.getString("bucket");
                modelFileName = mJsonObject.getString("model");
                labelsFileName = mJsonObject.getString("labels");
            } catch (JSONException ex) {
                Log.e(TAG, "Error parsing Json payload" + mJsonObject);
            }
            try {
                Storage storage = getStorage();
                try {
                    // Downloading Model File
                    ByteArrayOutputStream modelStream = new ByteArrayOutputStream();
                    Storage.Objects.Get getModel = storage.objects().get(bucketName, modelFileName);
                    getModel.getMediaHttpDownloader().setDirectDownloadEnabled(false);
                    getModel.executeMediaAndDownloadTo(modelStream);
                    // Writing Model file to Local Cache
                    String modelFilePath = cachePath + "/" + modelFileName;
                    Log.i(TAG, "Writing Model file to Local Cache: " + modelFilePath.toString());
                    FileOutputStream modelFileFos = new FileOutputStream(modelFilePath);
                    modelStream.writeTo(modelFileFos);
                    modelFileFos.flush();
                    modelFileFos.close();

                    // Downloading Labels File
                    ByteArrayOutputStream labelsStream = new ByteArrayOutputStream();
                    Storage.Objects.Get getLabels = storage.objects().get(bucketName, labelsFileName);
                    getModel.getMediaHttpDownloader().setDirectDownloadEnabled(false);
                    getLabels.executeMediaAndDownloadTo(labelsStream);
                    // Writing Model file to Local Cache
                    String labelsFilePath = cachePath + "/" + labelsFileName;
                    Log.i(TAG, "Writing Labels file to Local Cache: " + labelsFilePath.toString());
                    FileOutputStream labelsFileFos = new FileOutputStream(labelsFilePath);
                    labelsStream.writeTo(labelsFileFos);
                    labelsFileFos.flush();
                    labelsFileFos.close();

                    // ReInitialize the TF model
                    try {
                        Log.i(TAG, "Re-Initializing TF Classifier");
                        mTensorFlowClassifier = new TensorFlowImageClassifier(ImageClassifierActivity.this,
                                modelFilePath, labelsFilePath, TF_INPUT_IMAGE_WIDTH, TF_INPUT_IMAGE_HEIGHT, false);
                        Log.i(TAG, "TF Classifier Re-Initialized successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error while Re-Initializing TF Classifier" + e.toString());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error Talking to GCS to fetch new config: " + e.toString());
                }
            } finally {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mResultText.setText(R.string.help_message);
                        mReady.set(true);
                    }
                });
            }
        }
    };

    private File readFileFromStream(InputStream inStream){
        File tempFile = new File(getCacheDir()+"/file");
        try{
            byte[] buffer = new byte[4096];
            inStream.read(buffer);
            inStream.close();
            FileOutputStream KeyFileOutStream = new FileOutputStream(tempFile);
            KeyFileOutStream.write(buffer);
            KeyFileOutStream.close();
        }catch(IOException e){
            Log.e(TAG, "Unable to read file from Stream");
        }
        return tempFile;
    }

    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mImagePreprocessor = new ImagePreprocessor(PREVIEW_IMAGE_WIDTH, PREVIEW_IMAGE_HEIGHT,
                    TF_INPUT_IMAGE_WIDTH, TF_INPUT_IMAGE_HEIGHT);
            mCameraHandler = CameraHandler.getInstance();
            mCameraHandler.initializeCamera(ImageClassifierActivity.this,
                    PREVIEW_IMAGE_WIDTH, PREVIEW_IMAGE_HEIGHT, mBackgroundHandler,
                    ImageClassifierActivity.this);
            try {
                mTensorFlowClassifier = new TensorFlowImageClassifier(ImageClassifierActivity.this,
                        DEFAULT_MODEL_FILE, DEFAULT_LABELS_FILE, TF_INPUT_IMAGE_WIDTH, TF_INPUT_IMAGE_HEIGHT, true);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot initialize TFLite Classifier", e);
            }

            setReady(true);
        }
    };

    private Runnable mBackgroundClickHandler = new Runnable() {
        @Override
        public void run() {
            mCameraHandler.takePicture();
        }
    };

    private Runnable mBackgroundUploadToGcsHandler = new Runnable() {
        @Override
        public void run() {
            //Send the Image to GCS.
            Log.i(TAG, "Trying to write image to GCS");
            try{
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { mResultText.setText("Uploading Image to GCS....");
                    }
                });
                Storage storage = getStorage();
                File file = new File(localFilePathInCache);
                FileInputStream stream = new FileInputStream(file);
                Log.i(TAG, localFilePathInCache);
                try {
                    StorageObject objectMetadata = new StorageObject();
                    objectMetadata.setBucket(BUCKET_NAME);
                    String contentType = URLConnection.guessContentTypeFromStream(stream);
                    InputStreamContent content = new InputStreamContent(contentType, stream);
                    Storage.Objects.Insert insert = storage.objects().insert(
                            BUCKET_NAME, objectMetadata, content);
                    gcsFilePath = "gs://" + BUCKET_NAME + "/" + file.getName();
                    insert.setName(file.getName());
                    insert.execute();
                    Log.i(TAG, "File uploaded to GCS Successfully : " + gcsFilePath);
                    //Try to Publish to PubSub
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { mResultText.setText("Posting to PubSub....");
                        }
                    });
                    Log.i(TAG, "Sending Info to Pub Sub : ");
                    int selectedId = mLabelsRadio.getCheckedRadioButtonId();
                    RadioButton selected = findViewById(selectedId);
                    String selectedLabel = (String) selected.getText();
                    Log.i(TAG, "Selected Label Label:" + selectedLabel);
                    CloudIotOptions cloudIotOptions =
                            new CloudIotOptions(PROJECT_ID, REGISTRY_ID, DEVICE_ID, CLOUD_REGION);
                    mPublisher = new MQTTPublisher(cloudIotOptions);
                    mPublisher.publish(gcsFilePath, selectedLabel.toLowerCase());
                } catch (Exception e) {
                    Log.e(TAG, "Error writing file to GCS "+e.toString());
                } finally {
                    stream.close();
                }
            }catch (FileNotFoundException e){
                Log.e(TAG, "File Not Found" + e.toString());
            }catch (IOException e){
                Log.e(TAG, "IO Stream Error " + e.toString());
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mResultText.setText(R.string.help_message);
                }
            });
            setReady(true);
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Received key up: " + keyCode);
        if (keyCode == SHUTTER_KEYCODE) {
            startImageCapture();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Invoked when the user taps on the UI from a touch-enabled display
     */
    public void onShutterClick(View view) {
        Log.d(TAG, "Received screen tap");
        startImageCapture();
    }

    public void onSendToCloudClick(View view) {
        Log.i(TAG, "Received a request to send to image in cache to Cloud");
        if(mReady.get()) {
            setReady(false);
            showLabels();
        }
    }
    public void onConfirmSendClick(View view){
        mBackgroundHandler.post(mBackgroundUploadToGcsHandler);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLabelsRadio.setVisibility(View.INVISIBLE);
                mConfirmSendButton.setVisibility(View.INVISIBLE);
                mSendToCloudButton.setVisibility(View.VISIBLE);
                mSendToCloudLayout.setVisibility(View.INVISIBLE);

            }
        });
    }

    public void onCancelClick(View view) {
        Log.i(TAG, "Cancel send to Cloud");
        mSendToCloudLayout.setVisibility(View.INVISIBLE);
        mLabelsRadio.setVisibility(View.INVISIBLE);
        mResultText.setText(R.string.help_message);
        setReady(true);
    }
    private void showLabels(){
        Log.i(TAG, "Showing labels");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLabelsRadio.setVisibility(View.VISIBLE);
                mConfirmSendButton.setVisibility(View.VISIBLE);
                mSendToCloudButton.setVisibility(View.INVISIBLE);
                mResultText.setText("Pick a label and click Send");
            }
        });
    }

    /**
     * Verify and initiate a new image capture
     */
    private void startImageCapture() {
        Log.d(TAG, "Ready for another capture? " + mReady.get());
        if (mReady.get()) {
            setReady(false);
            mResultText.setText("Hold on...");
            mBackgroundHandler.post(mBackgroundClickHandler);
        } else {
            Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
        }
    }

    /**
     * Mark the system as ready for a new image capture
     */
    private void setReady(boolean ready) {
        mReady.set(ready);
    }

    private static Storage getStorage() {
        if (sStorage == null) {
            HttpTransport httpTransport = new NetHttpTransport();
            JsonFactory jsonFactory = new JacksonFactory();
            List<String> scopes = new ArrayList<>();
            scopes.add(StorageScopes.DEVSTORAGE_FULL_CONTROL);
            try {
                Credential credential = new GoogleCredential.Builder()
                        .setTransport(httpTransport)
                        .setJsonFactory(jsonFactory)
                        .setServiceAccountId(ACCOUNT_ID)
                        .setServiceAccountPrivateKeyFromP12File(gcsKeyFile)
                        .setServiceAccountScopes(scopes).build();

                sStorage = new Storage.Builder(httpTransport, jsonFactory,
                        credential).build();
            }catch (Exception e){
                Log.e(TAG, "ERROR getting a Storage Object: " + e.toString());
            }
        }
        return sStorage;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        final Bitmap bitmap;
        try (Image image = reader.acquireNextImage()) {
            bitmap = mImagePreprocessor.preprocessImage(image);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
            }
        });

        //Write Image to local Cache.
        Log.i(TAG, "Writing image to local cache....");
        File cachePath= new File(getCacheDir().toString());
        Long ts = System.currentTimeMillis();
        String filePath = cachePath + "/" + ts.toString() + ".jpg";
        try{
            FileOutputStream os = new FileOutputStream(filePath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 10, os);
            os.flush();
            os.close();
            Log.i(TAG, "Image written to local Cache: " + filePath);
            localFilePathInCache = filePath;
        }catch (FileNotFoundException e){
            Log.e(TAG,"Destination not found: " + filePath);
        }catch(IOException e){
            Log.e(TAG,"Error writing file to Cache: " + filePath);
        }

        final Collection<Recognition> results = mTensorFlowClassifier.doRecognize(bitmap);

        Log.d(TAG, "Got the following results from Tensorflow: " + results);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (results == null || results.isEmpty()) {
                    mResultText.setText("I don't understand what I see");
                } else {
                    StringBuilder sb = new StringBuilder();
                    Iterator<Recognition> it = results.iterator();
                    int counter = 0;
                    while (it.hasNext()) {
                        Recognition r = it.next();
                        sb.append(r.getTitle() + String.format("(%.2f%%)", (r.getConfidence() * 100.0f) * 100));
                        counter++;
                        if (counter < results.size() - 1 ) {
                            sb.append(", ");
                        } else if (counter == results.size() - 1) {
                            sb.append(" or ");
                        }
                    }
                    mResultText.setText(sb.toString());
                    //Show the option to send picture to Cloud
                    if(localFilePathInCache != null){
                        mSendToCloudLayout.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
        setReady(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;

        try {
            if (mCameraHandler != null) mCameraHandler.shutDown();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mTensorFlowClassifier != null) mTensorFlowClassifier.destroyClassifier();
        } catch (Throwable t) {
            // close quietly
        }
    }
}