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
package com.example.androidthings.imageclassifier.classifier;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.example.androidthings.imageclassifier.classifier.Recognition;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Helper functions for the TensorFlow image classifier.
 */
public class TensorFlowHelper {

    private static final int RESULTS_TO_SHOW = 3;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;

    /**
     * Memory-map the model file in Assets.
     */
    public static MappedByteBuffer loadModelFile(Context context, String modelFile)
            throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelFile);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer out = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        return out;
    }

    public static List<String> readLabels(Context context, String labelsFile) {
        AssetManager assetManager = context.getAssets();
        ArrayList<String> result = new ArrayList<>();
        try (InputStream is = assetManager.open(labelsFile);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                result.add(line);
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read labels from " + labelsFile);
        }
    }

    public static MappedByteBuffer loadModelFileFromCache(String modelFilePath)
            throws IOException {
        File modelFile = new File(modelFilePath);
        FileInputStream inputStream = new FileInputStream(modelFile);
        FileChannel fileChannel = inputStream.getChannel();
        long declaredLength =fileChannel.size();
        long offset = fileChannel.position();
        MappedByteBuffer out = new RandomAccessFile(modelFilePath, "r").getChannel().map(
                FileChannel.MapMode.READ_ONLY, offset,declaredLength);
        return out;
    }

    public static List<String> readLabelsFromCache(String labelsFile) {
        ArrayList<String> result = new ArrayList<>();
        File labelFile = new File(labelsFile);
        try {
            FileInputStream is = new FileInputStream(labelFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                result.add(line);
            }
            Log.i("Labels", result.toString());
            return result;

        }
         catch (Exception ex){
                throw new IllegalStateException("Cannot read labels from " + labelsFile);
            }
        }


    /**
     * Find the best classifications.
      */
    public static Collection<Recognition> getBestResults(float[][] labelProbArray,
                                                         List<String> labelList) {
        PriorityQueue<Recognition> sortedLabels = new PriorityQueue<>(
                RESULTS_TO_SHOW,
                new Comparator<Recognition>() {
                    @Override
                    public int compare(Recognition lhs, Recognition rhs) {
                        return Float.compare(rhs.getConfidence(),lhs.getConfidence());
                    }
                });


        for (int i = 0; i < labelList.size(); ++i) {
            Recognition r = new Recognition(
                    String.valueOf(i),
                    labelList.get(i),
                    labelProbArray[0][i]
                   // (labelProbArray[0][i]) / 255.0f
            );
            sortedLabels.add(r);
//            if (r.getConfidence() > 0) {
//                Log.d("ImageRecognition", r.toString());
//            }
//            if (sortedLabels.size() > RESULTS_TO_SHOW) {
//                sortedLabels.poll();
//            }
        }

//        List<Recognition> results = new ArrayList<>(RESULTS_TO_SHOW);
//        for (Recognition r: sortedLabels) {
//            results.add(0, r);
//        }

        List<Recognition> results= new ArrayList<>();
        int len = sortedLabels.size();
        for (int i = 0; i< len && i < RESULTS_TO_SHOW; i++){
            results.add(sortedLabels.poll());
        }
        return results;
    }

    /** Writes Image data into a {@code ByteBuffer}. */
    public static void convertBitmapToByteBuffer(Bitmap bitmap, int[] intValues, ByteBuffer imgData) {
        if (imgData == null) {
            return;
        }
        imgData.rewind();
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        // Encode the image pixels into a byte buffer representation matching the expected
        // input of the Tensorflow model
        int pixel = 0;
        for (int i = 0; i < bitmap.getWidth(); ++i) {
            for (int j = 0; j < bitmap.getHeight(); ++j) {
                final int val = intValues[pixel++];
                //imgData.put((byte) ((val >> 16) & 0xFF));
                //imgData.put((byte) ((val >> 8) & 0xFF));
                //imgData.put((byte) (val & 0xFF));
                imgData.putFloat((((val >> 16) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData.putFloat((((val >> 8) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData.putFloat((((val) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
            }
        }
    }
}
