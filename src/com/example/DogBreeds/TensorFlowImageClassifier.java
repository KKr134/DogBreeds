package com.jstappdev.dbclf;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import org.tensorflow.Operation;
import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Vector;


public class TensorFlowImageClassifier implements Classifier {
    // Only return this many results with at least this confidence.
    private static final int MAX_RESULTS = 3;
    private static final float THRESHOLD = 0.1f;

    // Config values.
    private String inputName;
    private String outputName;
    private int inputSize;
    private int imageMean;
    private float imageStd;

    // Pre-allocated buffers.
    private Vector<String> labels = new Vector<String>();
    private int[] intValues;
    private float[] floatValues;
    private float[] outputs;
    private String[] outputNames;

    private boolean logStats = false;

    private TensorFlowInferenceInterface inferenceInterface;

    private TensorFlowImageClassifier() {
    }

    public static Classifier create(
            AssetManager assetManager,
            String modelFilename,
            String[] labels,
            int inputSize,
            int imageMean,
            float imageStd,
            String inputName,
            String outputName) {
        final TensorFlowImageClassifier c = new TensorFlowImageClassifier();
        c.inputName = inputName;
        c.outputName = outputName;

        // Read the label names into memory.
/*        final String actualFilename = labelFilename.split("file:///android_asset/")[1];

        try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(assetManager.open(actualFilename)))) {
            String line;
            while ((line = br.readLine()) != null) {
                c.labels.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Problem reading label file!", e);
        }*/

        Collections.addAll(c.labels, labels);

        c.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);

        // The shape of the output is [N, NUM_CLASSES], where N is the batch size.
        final Operation operation = c.inferenceInterface.graphOperation(outputName);
        final int numClasses = (int) operation.output(0).shape().size(1);
        //Log.i(TAG, "Read " + c.labels.size() + " labels, output layer size is " + numClasses);


        c.inputSize = inputSize;
        c.imageMean = imageMean;
        c.imageStd = imageStd;

        // Pre-allocate buffers.
        c.outputNames = new String[]{outputName};
        c.intValues = new int[inputSize * inputSize];
        c.floatValues = new float[inputSize * inputSize * 3];
        c.outputs = new float[numClasses];

        return c;
    }


    @Override
    public List<Recognition> recognizeImage(final Bitmap bitmap) {
        // Log this method so that it can be analyzed with systrace.

        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int i = 0; i < intValues.length; ++i) {
            final int val = intValues[i];
            floatValues[i * 3 + 0] = (((val >> 16) & 0xFF) - imageMean) / imageStd;
            floatValues[i * 3 + 1] = (((val >> 8) & 0xFF) - imageMean) / imageStd;
            floatValues[i * 3 + 2] = ((val & 0xFF) - imageMean) / imageStd;
        }

        // Copy the input data into TensorFlow.
        inferenceInterface.feed(inputName, floatValues, 1, inputSize, inputSize, 3);

        // Run the inference call.
        inferenceInterface.run(outputNames, logStats);

        // Copy the output Tensor back into the output array.
        inferenceInterface.fetch(outputName, outputs);

        // Find the best classifications.
        PriorityQueue<Recognition> pq =
                new PriorityQueue<Recognition>(
                        3,
                        (lhs, rhs) -> {
                            // Intentionally reversed to put high confidence at the head of the queue.
                            return Float.compare(rhs.getConfidence(), lhs.getConfidence());
                        });

        for (int i = 0; i < outputs.length; ++i) {
            if (outputs[i] > THRESHOLD) {
                pq.add(
                        new Recognition(
                                "" + i, labels.size() > i ? labels.get(i) : "unknown", outputs[i], null));
            }
        }
        final ArrayList<Recognition> recognitions = new ArrayList<Recognition>();
        final int recognitionsSize = Math.min(pq.size(), MAX_RESULTS);
        for (int i = 0; i < recognitionsSize; ++i) {
            recognitions.add(pq.poll());
        }
        return recognitions;
    }
}
