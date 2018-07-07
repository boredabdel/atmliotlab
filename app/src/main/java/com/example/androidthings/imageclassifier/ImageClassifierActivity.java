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
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
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
import org.json.JSONObject;

import com.example.androidthings.imageclassifier.cloud.pubsub.CloudPublisherService;
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
import java.util.Properties;

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
    private static final String GCS_KEY_FILE_PATH = "sa-key.p12";
    private static final String BUCKET_NAME = "at-test-upload01";
    private static final String REGISTRY_ID = "myregistry";
    private static final String DEVICE_ID = "newdevice";
    private static final String CLOUD_REGION = "europe-west1";

    private static final String TAG = "ImageClassifierActivity";
    private static final int PREVIEW_IMAGE_WIDTH = 640;
    private static final int PREVIEW_IMAGE_HEIGHT = 480;
    private static final int TF_INPUT_IMAGE_WIDTH = 224;
    private static final int TF_INPUT_IMAGE_HEIGHT = 224;
    private static Storage sStorage;
    private static Properties sProperties;
    private static File gcsKeyFile;

    private static final String PROJECT_ID_PROPERTY = "project.id";
    private static final String APPLICATION_NAME_PROPERTY = "application.name";
    private static final String ACCOUNT_ID_PROPERTY = "account.id";

    private static final String PROJECT_ID =  "iot-ml-bootcamp-2018";
    private static final String APPLICATION_NAME =  "ml-bootcamp";
    private static final String ACCOUNT_ID = "iot-gcs-sa@iot-ml-bootcamp-2018.iam.gserviceaccount.com";

    /* Key code used by GPIO button to trigger image capture */
    private static final int SHUTTER_KEYCODE = KeyEvent.KEYCODE_CAMERA;

    private ImagePreprocessor mImagePreprocessor;
    private CameraHandler mCameraHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;
    private String localFilePathInCache;
    private String localModelPathInCache;
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
    private CloudPublisherService mPublishService;
    private static MqttCallback mCallback;
    private static JSONObject mjsonObject;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{
            Log.i(TAG, "Reading GCS SA Key file");
            AssetManager assets = getAssets();
            gcsKeyFile = readFileFromStream(assets.open(GCS_KEY_FILE_PATH));
        }catch(Exception e){
            Log.i(TAG, "Failed Reading GCS SA Key file");
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
        if (isAndroidThingsDevice(this)) {
        }
        mqttAuth = new MqttAuthentication();
        mqttAuth.initialize();
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
                mjsonObject = new JSONObject(payload);
                Log.i(TAG, "Received Payload" + mjsonObject.toString());
                mBackgroundHandler.post(mBackgroundGetModelFromGCS);
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
            Log.i(TAG, "Getting Model from GCS to write image to GCS");
            try {
                Storage storage = getStorage();
                try {
                    String bucket_name = mjsonObject.getString("bucket");
                    Log.i(TAG, bucket_name);
                    String model_file = mjsonObject.getString("model");
                    Log.i(TAG, model_file);
                    ByteArrayOutputStream modelStream = new ByteArrayOutputStream();
                    Storage.Objects.Get getModel = storage.objects().get(bucket_name, model_file);
                    getModel.getMediaHttpDownloader().setDirectDownloadEnabled(false);
                    getModel.executeMediaAndDownloadTo(modelStream);
                    Log.i(TAG, "Got Model File : " + modelStream.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error writing file to GCS " + e.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error " + e);
            }
        };
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
                        TF_INPUT_IMAGE_WIDTH, TF_INPUT_IMAGE_HEIGHT);
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
                try {
                    StorageObject objectMetadata = new StorageObject();
                    objectMetadata.setBucket(BUCKET_NAME);
                    String contentType = URLConnection.guessContentTypeFromStream(stream);
                    InputStreamContent content = new InputStreamContent(contentType, stream);
                    Storage.Objects.Insert insert = storage.objects().insert(
                            BUCKET_NAME, objectMetadata, content);
                    insert.setName(file.getName());
                    insert.execute();
                    gcsFilePath = "gs://" + BUCKET_NAME + "/" + file.getName();
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

    private static Properties getProperties() throws Exception {
        if (sProperties == null) {
            sProperties = new Properties();
            sProperties.setProperty(PROJECT_ID_PROPERTY, PROJECT_ID);
            sProperties.setProperty(APPLICATION_NAME_PROPERTY, APPLICATION_NAME);
            sProperties.setProperty(ACCOUNT_ID_PROPERTY, ACCOUNT_ID);
        }
        return sProperties;
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
                        .setServiceAccountId(
                                getProperties().getProperty(ACCOUNT_ID_PROPERTY))
                        .setServiceAccountPrivateKeyFromP12File(gcsKeyFile)
                        .setServiceAccountScopes(scopes).build();

                sStorage = new Storage.Builder(httpTransport, jsonFactory,
                        credential).setApplicationName(
                        getProperties().getProperty(APPLICATION_NAME_PROPERTY))
                        .build();
            }catch (Exception e){
                Log.e(TAG, "ERROR Getting a Storage Object: " + e.toString());
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
        Log.i(TAG, "Trying to write image to local cache....");
        File cachePath= new File(getCacheDir().toString());
        Long ts = System.currentTimeMillis();
        String filePath = cachePath + "/" + ts.toString() + ".jpg";
        try{
            FileOutputStream os = new FileOutputStream(filePath);
            //bitmap.compress(Bitmap.CompressFormat.JPEG, 10, os);
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
                        sb.append(r.getTitle());
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
//        if (mTtsEngine != null) {
//            // speak out loud the result of the image recognition
//            mTtsSpeaker.speakResults(mTtsEngine, results);
//        } else {
//            // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
//            // to ready right away.
//            setReady(true);
//        }
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

    /**
     * @return true if this device is running Android Things.
     *
     * Source: https://stackoverflow.com/a/44171734/112705
     */
    private boolean isAndroidThingsDevice(Context context) {
        // We can't use PackageManager.FEATURE_EMBEDDED here as it was only added in API level 26,
        // and we currently target a lower minSdkVersion
        final PackageManager pm = context.getPackageManager();
        boolean isRunningAndroidThings = pm.hasSystemFeature("android.hardware.type.embedded");
        Log.d(TAG, "isRunningAndroidThings: " + isRunningAndroidThings);
        return isRunningAndroidThings;
    }

    /**
     * Callback for service binding, passed to bindService()
     */
    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            CloudPublisherService.LocalBinder binder = (CloudPublisherService.LocalBinder) service;
            mPublishService = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mPublishService = null;
        }
    };



}
