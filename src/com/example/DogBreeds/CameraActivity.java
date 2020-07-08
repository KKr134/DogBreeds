package com.jstappdev.dbclf;


import android.Manifest;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.jstappdev.dbclf.env.ImageUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;


public abstract class CameraActivity extends FragmentActivity
        implements OnImageAvailableListener, Camera.PreviewCallback {

    static final int PICK_IMAGE = 100;
    private static final int PERMISSIONS_REQUEST = 1;
    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE_READ = Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final String PERMISSION_STORAGE_WRITE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static int cameraPermissionRequests = 0;

    private Handler handler;
    private HandlerThread handlerThread;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    protected ArrayList<String> currentRecognitions;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Runnable postInferenceCallback;
    private Runnable imageConverter;

    TextView resultsView;
    PieChart mChart;

    AtomicBoolean snapShot = new AtomicBoolean(false);
    boolean continuousInference = false;
    boolean imageSet = false;

    ImageButton cameraButton, gallarybutton,shareButton;
    ToggleButton continuousInferenceButton;
    ImageView imageViewFromGallery;
    ProgressBar progressBar;

    static private final int[] CHART_COLORS = {Color.rgb(114, 147, 203),
            Color.rgb(225, 151, 76), Color.rgb(132, 186, 91), Color.TRANSPARENT};
    private boolean useCamera2API;

    protected ClassifierActivity.InferenceTask inferenceTask;

    abstract void handleSendImage(Intent intent);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        setupButtons();
        setupPieChart();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

                handleSendImage(intent);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            switch (permissions[0]) {
                case PERMISSION_CAMERA:
                    setFragment();
                    break;
                case PERMISSION_STORAGE_READ:
                    pickImage();
                    break;
                case PERMISSION_STORAGE_WRITE:
                    shareButton.callOnClick();
                    break;
            }
        }
    }

    private void setupButtons() {
        imageViewFromGallery = findViewById(R.id.imageView);
        resultsView = findViewById(R.id.results);
        mChart = findViewById(R.id.chart);
        progressBar = findViewById(R.id.progressBar);

        continuousInferenceButton = findViewById(R.id.continuousInferenceButton);
        cameraButton = findViewById(R.id.cameraButton);
        gallarybutton= findViewById(R.id.pick_image);
        if (!hasPermission(PERMISSION_STORAGE_READ)) {
            requestPermission(PERMISSION_STORAGE_READ);
        }
        gallarybutton.setOnClickListener(v -> {
            if (!hasPermission(PERMISSION_CAMERA)) {
                requestPermission(PERMISSION_CAMERA);
                return;
            }
            pickImage();

        });

        cameraButton.setEnabled(false);
        cameraButton.setOnClickListener(v -> {
            if (!hasPermission(PERMISSION_CAMERA)) {
                requestPermission(PERMISSION_CAMERA);
                return;
            }

            final View pnlFlash = findViewById(R.id.pnlFlash);

            cameraButton.setEnabled(false);
            snapShot.set(true);
            imageSet = false;
            updateResults(null);

            imageViewFromGallery.setVisibility(View.GONE);
            continuousInferenceButton.setChecked(false);

            pnlFlash.setVisibility(View.VISIBLE);
            AlphaAnimation fade = new AlphaAnimation(1, 0);
            fade.setDuration(500);
            fade.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation anim) {
                    pnlFlash.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }
            });

            pnlFlash.startAnimation(fade);
        });

        continuousInferenceButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!hasPermission(PERMISSION_CAMERA)) requestPermission(PERMISSION_CAMERA);

            imageViewFromGallery.setVisibility(View.GONE);
            continuousInference = isChecked;

            if (!continuousInference)
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

            if (!isChecked)
                resultsView.setEnabled(false);

            cameraButton.setEnabled(true);

            imageSet = false;

            if (handler != null)
                handler.post(() -> updateResults(null));

            readyForNextImage();
        });

        resultsView.setOnClickListener(v -> {
            if (currentRecognitions == null || continuousInference || currentRecognitions.size() == 0)
                return;


            Intent i = new Intent(getApplicationContext(), SimpleListActivity.class);
            i.putStringArrayListExtra("recogs", currentRecognitions);

            startActivity(i);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mainmenu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                final AlertDialog.Builder builder1 = new AlertDialog.Builder(CameraActivity.this);

                final SpannableString s = new SpannableString(Html.fromHtml(getString(R.string.about_message)));
                Linkify.addLinks(s, Linkify.WEB_URLS);

                builder1.setMessage(s);
                builder1.setCancelable(true);

                builder1.setPositiveButton(
                        "Ok.",
                        (dialog, id) -> dialog.cancel());

                final AlertDialog infoDialog = builder1.create();
                infoDialog.show();

                ((TextView) infoDialog.findViewById(android.R.id.message)).
                        setMovementMethod(LinkMovementMethod.getInstance());
                break;
            case R.id.pick_image:
                if (!hasPermission(PERMISSION_STORAGE_READ)) {
                    requestPermission(PERMISSION_STORAGE_READ);
                    return false;
                }

                pickImage();
                break;
            case R.id.list_breeds:
                startActivity(new Intent(this, SimpleListActivity.class));
                break;
            case R.id.action_exit:
                finishAndRemoveTask();
                break;
            default:
                break;
        }

        return true;
    }

    private void pickImage() {
        final Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        continuousInferenceButton.setChecked(false);
        startActivityForResult(i, PICK_IMAGE);
    }

    protected int[] getRgbBytes() {
        if (imageConverter != null)
            imageConverter.run();

        return rgbBytes;
    }


    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (isProcessingFrame) {
            return;
        }

        try {

            if (rgbBytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                previewHeight = previewSize.height;
                previewWidth = previewSize.width;
                rgbBytes = new int[previewWidth * previewHeight];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
        } catch (final Exception e) {
            //LOGGER.e(e, "Exception!");
            return;
        }

        isProcessingFrame = true;
        yuvBytes[0] = bytes;
        yRowStride = previewWidth;

        imageConverter =
                () -> ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);

        postInferenceCallback =
                () -> {
                    camera.addCallbackBuffer(bytes);
                    isProcessingFrame = false;
                };
        processImage();
    }


    @Override
    public void onImageAvailable(final ImageReader reader) {

        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            final Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter = () -> ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0],
                    yuvBytes[1],
                    yuvBytes[2],
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes);

            postInferenceCallback = () -> {
                image.close();
                isProcessingFrame = false;
            };

            processImage();
        } catch (final Exception ignored) {
        }
    }

    @Override
    public synchronized void onStart() {
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        if (!hasPermission(PERMISSION_CAMERA)) {

            if (cameraPermissionRequests++ < 3) {
                requestPermission(PERMISSION_CAMERA);
            } else {
                Toast.makeText(getApplicationContext(), "Camera permission required.", Toast.LENGTH_LONG).show();
            }
        } else {
            setFragment();
        }


        snapShot.set(false);

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        final ActionBar actionBar = getActionBar();
        if (actionBar != null)
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME
                    | ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM);

        if (!imageSet) cameraButton.setEnabled(true);
    }

    private void setupPieChart() {
        mChart.getDescription().setEnabled(false);
        mChart.setUsePercentValues(true);
        mChart.setTouchEnabled(false);

        mChart.setCenterTextTypeface(Typeface.createFromAsset(getAssets(), "OpenSans-Regular.ttf"));
        mChart.setCenterText(generateCenterSpannableText());
        mChart.setExtraOffsets(14, 0.f, 14, 0.f);
        mChart.setHoleRadius(85);
        mChart.setHoleColor(Color.TRANSPARENT);
        mChart.setCenterTextSizePixels(23);
        mChart.setHovered(true);
        mChart.setDrawMarkers(false);
        mChart.setDrawCenterText(true);
        mChart.setRotationEnabled(false);
        mChart.setHighlightPerTapEnabled(false);
        mChart.getLegend().setEnabled(false);
        mChart.setAlpha(0.9f);

        final ArrayList<PieEntry> entries = new ArrayList<>();
        entries.add(new PieEntry(100, ""));
        final PieDataSet set = new PieDataSet(entries, "");
        set.setColor(R.color.transparent);
        set.setDrawValues(false);

        final PieData data = new PieData(set);
//        mChart.setCenterText(" ");
        mChart.setData(data);
    }

    private SpannableString generateCenterSpannableText() {
        final SpannableString s = new SpannableString("Center dog here\nkeep camera stable");
        s.setSpan(new RelativeSizeSpan(1.5f), 0, 15, 0);
        s.setSpan(new StyleSpan(Typeface.NORMAL), 15, s.length() - 15, 0);
        s.setSpan(new ForegroundColorSpan(ColorTemplate.getHoloBlue()), 0, 15, 0);

        s.setSpan(new StyleSpan(Typeface.ITALIC), s.length() - 18, s.length(), 0);
        s.setSpan(new ForegroundColorSpan(ColorTemplate.getHoloBlue()), s.length() - 18, s.length(), 0);
        return s;
    }

    @Override
    public synchronized void onPause() {
        snapShot.set(false);
        cameraButton.setEnabled(false);
        isProcessingFrame = false;
        progressBar.setVisibility(View.GONE);

        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException ignored) {
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    private boolean hasPermission(final String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission(final String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{permission}, PERMISSIONS_REQUEST);
        }
    }

    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        return requiredLevel <= deviceLevel;
    }

    protected String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                        || isHardwareLevelSupported(characteristics,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                return cameraId;
            }
        } catch (CameraAccessException e) {
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();
        if (cameraId == null) {
            Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show();
            finish();
        }

        Fragment fragment;
        if (useCamera2API) {
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            (size, rotation) -> {
                                previewHeight = size.getHeight();
                                previewWidth = size.getWidth();
                                CameraActivity.this.onPreviewSizeChosen(size, rotation);
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment =
                    new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }

        getSupportFragmentManager().beginTransaction().replace(R.id.container, fragment).commitNowAllowingStateLoss();
    }

    protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        } else isProcessingFrame = false;

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

    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();

    protected abstract void initClassifier();

    void updateResults(List<Classifier.Recognition> results) {
        runOnUiThread(() -> {
            updateResultsView(results);
            updatePieChart(results);
        });
    }

    void updateResultsView(List<Classifier.Recognition> results) {
        final StringBuilder sb = new StringBuilder();
        currentRecognitions = new ArrayList<String>();

        if (results != null) {
            resultsView.setEnabled(true);




            if (results.size() > 0) {
                for (final Classifier.Recognition recog : results) {
                    final String text = String.format(Locale.getDefault(), "%s: %d %%\n",
                            recog.getTitle(), Math.round(recog.getConfidence() * 100));
                    sb.append(text);
                    currentRecognitions.add(recog.getTitle());
                }
            } else {
                sb.append(getString(R.string.no_detection));
            }
        } else {
            resultsView.setEnabled(false);
        }

        final String finalText = sb.toString();
        resultsView.setText(finalText);
    }

    void updatePieChart(List<Classifier.Recognition> results) {
        final ArrayList<PieEntry> entries = new ArrayList<>();
        float sum = 0;

        if (results != null)
            for (int i = 0; i < results.size(); i++) {
                sum += results.get(i).getConfidence();

                PieEntry entry = new PieEntry(results.get(i).getConfidence() * 100, results.get(i).getTitle());
                entries.add(entry);
            }

        final float unknown = 1 - sum;
        entries.add(new PieEntry(unknown * 100, ""));

        final float offset = entries.get(0).getValue() * 3.6f / 2;
        final float end = 270f - (entries.get(0).getValue() * 3.6f - offset);

        final PieDataSet set = new PieDataSet(entries, "");

        if (entries.size() > 2)
            set.setSliceSpace(3f);

        final ArrayList<Integer> sliceColors = new ArrayList<>();

        for (int c : CHART_COLORS)
            sliceColors.add(c);

        if (entries.size() > 0)
            sliceColors.set(entries.size() - 1, R.color.transparent);

        set.setColors(sliceColors);
        set.setDrawValues(false);

        final PieData data = new PieData(set);
        mChart.setData(data);
        mChart.setCenterText(" ");
        mChart.setRotationAngle(end);
        mChart.setEntryLabelTextSize(16);
        mChart.invalidate();
    }

    protected void setImage(Bitmap image) {
        final int transitionTime = 1000;
        imageSet = true;

        cameraButton.setEnabled(false);
        imageViewFromGallery.setImageBitmap(image);
        imageViewFromGallery.setVisibility(View.VISIBLE);

        final TransitionDrawable transition = (TransitionDrawable) imageViewFromGallery.getBackground();
        transition.startTransition(transitionTime);

        final AlphaAnimation fade = new AlphaAnimation(1, 0);
        fade.setDuration(transitionTime);

        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

                imageViewFromGallery.setClickable(false);
                runInBackground(() -> updateResults(null));
                transition.reverseTransition(transitionTime);
                imageViewFromGallery.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationEnd(Animation anim) {
                progressBar.setVisibility(View.GONE);
                imageSet = false;
                snapShot.set(false);
                cameraButton.setEnabled(true);
                readyForNextImage();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });

        imageViewFromGallery.setVisibility(View.VISIBLE);
        imageViewFromGallery.setOnClickListener(v -> imageViewFromGallery.startAnimation(fade));

    }

    public Bitmap takeScreenshot() {
        View rootView = findViewById(android.R.id.content).getRootView();
        rootView.setDrawingCacheEnabled(true);
        Bitmap screenshot = rootView.getDrawingCache();
        return screenshot;
    }

    private String fileUrl;
    private boolean alreadyAdded = false;

}