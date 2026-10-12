package com.example.compasswidget2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.RemoteViews;

import java.util.Locale;

public class CompassService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.compasswidget2.START";

    private static final int SIZE = 512;
    private static final float SMOOTHING = 0.12f;
    private static final long MIN_RENDER_INTERVAL_MS = 200L;
    private static final long FORCE_RENDER_INTERVAL_MS = 1500L;
    private static final float MIN_RENDER_DELTA_DEGREES = 0.7f;

    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] adjustedRotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final Bitmap[] frameBuffers = new Bitmap[2];

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Typeface dotoTypeface;
    private Bitmap matrixBitmap;
    private boolean hasGravity;
    private boolean hasGeomagnetic;
    private boolean sensorsRegistered;
    private boolean receiverRegistered;
    private int magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private int frameBufferIndex;
    private float smoothedAzimuth = Float.NaN;
    private float lastRenderedAzimuth = Float.NaN;
    private long lastRenderAt;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopSensors();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                startSensorsIfInteractive();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        dotoTypeface = loadTypeface("doto.ttf");
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        frameBuffers[0] = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        frameBuffers[1] = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        matrixBitmap = drawMatrix();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!hasWidgets()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        if (accelerometer == null || magnetometer == null) {
            updateWidgets(Float.NaN, "NO SENSOR");
            return START_NOT_STICKY;
        }
        updateWidgets(Float.NaN, "STARTING");
        startSensorsIfInteractive();
        return START_STICKY;
    }

    private boolean hasWidgets() {
        int[] ids = AppWidgetManager.getInstance(this).getAppWidgetIds(
                new ComponentName(this, CompassBlackWidgetProvider.class));
        return ids.length > 0;
    }

    private void startSensorsIfInteractive() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) return;
        if (sensorsRegistered || accelerometer == null || magnetometer == null) return;
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        sensorsRegistered = true;
    }

    private void stopSensors() {
        if (!sensorsRegistered || sensorManager == null) return;
        sensorManager.unregisterListener(this);
        sensorsRegistered = false;
        hasGravity = false;
        hasGeomagnetic = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, gravity.length);
            hasGravity = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, geomagnetic.length);
            hasGeomagnetic = true;
        }
        if (!hasGravity || !hasGeomagnetic) return;

        float azimuth = calculateAzimuth();
        if (Float.isNaN(azimuth)) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastRenderAt;
        float delta = angleDistance(azimuth, lastRenderedAzimuth);
        if (elapsed < MIN_RENDER_INTERVAL_MS) return;
        if (elapsed < FORCE_RENDER_INTERVAL_MS && delta < MIN_RENDER_DELTA_DEGREES) return;

        String status = magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ? "CALIBRATE" : cardinalDirection(azimuth);
        updateWidgets(azimuth, status);
        lastRenderAt = now;
        lastRenderedAzimuth = azimuth;
    }

    private float calculateAzimuth() {
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            return Float.NaN;
        }
        int xAxis = SensorManager.AXIS_X;
        int yAxis = SensorManager.AXIS_Y;
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int rotation = windowManager == null
                ? Surface.ROTATION_0 : windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                xAxis = SensorManager.AXIS_Y;
                yAxis = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                xAxis = SensorManager.AXIS_MINUS_X;
                yAxis = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                xAxis = SensorManager.AXIS_MINUS_Y;
                yAxis = SensorManager.AXIS_X;
                break;
            default:
                break;
        }
        if (!SensorManager.remapCoordinateSystem(
                rotationMatrix, xAxis, yAxis, adjustedRotationMatrix)) {
            return Float.NaN;
        }
        SensorManager.getOrientation(adjustedRotationMatrix, orientation);
        float rawAzimuth = ((float) Math.toDegrees(orientation[0]) + 360f) % 360f;
        return smoothAngle(rawAzimuth);
    }

    private float smoothAngle(float newAngle) {
        if (Float.isNaN(smoothedAzimuth)) {
            smoothedAzimuth = newAngle;
        } else {
            float diff = newAngle - smoothedAzimuth;
            while (diff > 180f) diff -= 360f;
            while (diff < -180f) diff += 360f;
            smoothedAzimuth = (smoothedAzimuth + SMOOTHING * diff + 360f) % 360f;
        }
        return smoothedAzimuth;
    }

    private float angleDistance(float first, float second) {
        if (Float.isNaN(second)) return Float.MAX_VALUE;
        float diff = Math.abs(first - second) % 360f;
        return Math.min(diff, 360f - diff);
    }

    private void updateWidgets(float azimuth, String status) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(this, CompassBlackWidgetProvider.class));
        if (ids.length == 0) {
            stopSelf();
            return;
        }

        Bitmap content = nextFrame(azimuth, status);
        PendingIntent compassIntent = compassPendingIntent();
        for (int id : ids) {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_layout);
            views.setImageViewBitmap(R.id.background_matrix, matrixBitmap);
            views.setImageViewBitmap(R.id.compass_content, content);
            if (compassIntent != null) {
                views.setOnClickPendingIntent(R.id.click_target, compassIntent);
            }
            manager.updateAppWidget(id, views);
        }
    }

    private Bitmap nextFrame(float azimuth, String status) {
        frameBufferIndex = (frameBufferIndex + 1) % frameBuffers.length;
        Bitmap bitmap = frameBuffers[frameBufferIndex];
        bitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(bitmap);

        float center = SIZE / 2f;
        Paint labelPaint = textPaint(58f, Color.WHITE);
        drawCenteredGlyph(canvas, "N", center, 42f, Edge.TOP, labelPaint);
        drawCenteredGlyph(canvas, "S", center, SIZE - 42f, Edge.BOTTOM, labelPaint);
        drawCenteredGlyph(canvas, "W", 42f, center, Edge.LEFT, labelPaint);
        drawCenteredGlyph(canvas, "E", SIZE - 42f, center, Edge.RIGHT, labelPaint);

        if (!Float.isNaN(azimuth)) {
            canvas.save();
            canvas.rotate(azimuth, center, center);
            drawDotArrow(canvas, center, center);
            canvas.restore();
        } else {
            drawIdleMark(canvas, center, center);
        }

        Paint degreePaint = textPaint(36f, Color.WHITE);
        boolean directionStatus = isDirection(status);
        String degree = Float.isNaN(azimuth)
                ? "---" : String.format(Locale.US, "%03d", Math.round(azimuth) % 360);
        String degreeLine = directionStatus ? degree + " " + status : degree;
        drawPixelCenteredText(canvas, degreeLine, center, SIZE - 120f, degreePaint);

        if (!directionStatus) {
            Paint statusPaint = textPaint(22f,
                    "CALIBRATE".equals(status) ? Color.rgb(255, 55, 65) : Color.LTGRAY);
            drawPixelCenteredText(canvas, status, center, SIZE - 82f, statusPaint);
        }
        return bitmap;
    }

    private Bitmap drawMatrix() {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint matrixPaint = new Paint();
        matrixPaint.setAntiAlias(false);
        float cell = 24f;
        int columns = (int) Math.ceil(SIZE / cell);
        int rows = (int) Math.ceil(SIZE / cell);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int alpha = 7 + ((row * 11 + column * 7) % 4) * 3;
                boolean accent = (row * 17 + column * 29) % 37 == 0;
                matrixPaint.setColor(accent
                        ? Color.argb(24, 255, 45, 45)
                        : Color.argb(alpha, 255, 255, 255));
                canvas.drawRect(column * cell, row * cell,
                        Math.min(SIZE, (column + 1) * cell),
                        Math.min(SIZE, (row + 1) * cell), matrixPaint);
            }
        }
        return bitmap;
    }

    private Paint textPaint(float size, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(dotoTypeface);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        return paint;
    }

    private void drawPixelCenteredText(Canvas canvas, String text, float centerX,
                                       float baseline, Paint paint) {
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        float originX = centerX - (bounds.left + bounds.right) / 2f;
        canvas.drawText(text, originX, baseline, paint);
    }

    private enum Edge { TOP, BOTTOM, LEFT, RIGHT }

    private void drawCenteredGlyph(Canvas canvas, String glyph, float anchorX, float anchorY,
                                   Edge edge, Paint paint) {
        Rect bounds = new Rect();
        paint.getTextBounds(glyph, 0, glyph.length(), bounds);
        float x;
        float baseline;
        if (edge == Edge.TOP) {
            x = anchorX - (bounds.left + bounds.right) / 2f;
            baseline = anchorY - bounds.top;
        } else if (edge == Edge.BOTTOM) {
            x = anchorX - (bounds.left + bounds.right) / 2f;
            baseline = anchorY - bounds.bottom;
        } else if (edge == Edge.LEFT) {
            x = anchorX - bounds.left;
            baseline = anchorY - (bounds.top + bounds.bottom) / 2f;
        } else {
            x = anchorX - bounds.right;
            baseline = anchorY - (bounds.top + bounds.bottom) / 2f;
        }
        canvas.drawText(glyph, x, baseline, paint);
    }

    private void drawDotArrow(Canvas canvas, float centerX, float centerY) {
        String[] pattern = {
                "....X....", "...XXX...", "..XXXXX..", ".XXXXXXX.",
                "...XXX...", "...XXX...", "...XXX..."
        };
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(255, 45, 45));
        float gap = 17f;
        float startX = centerX - ((pattern[0].length() - 1) * gap) / 2f;
        float startY = centerY - ((pattern.length - 1) * gap) / 2f;
        for (int row = 0; row < pattern.length; row++) {
            for (int column = 0; column < pattern[row].length(); column++) {
                if (pattern[row].charAt(column) == 'X') {
                    canvas.drawCircle(startX + column * gap, startY + row * gap,
                            6f, paint);
                }
            }
        }
    }

    private void drawIdleMark(Canvas canvas, float centerX, float centerY) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(255, 45, 45));
        canvas.drawCircle(centerX, centerY, 9f, paint);
    }

    private String cardinalDirection(float azimuth) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return directions[Math.round(azimuth / 45f) % directions.length];
    }

    private boolean isDirection(String status) {
        return "N".equals(status) || "NE".equals(status) || "E".equals(status)
                || "SE".equals(status) || "S".equals(status) || "SW".equals(status)
                || "W".equals(status) || "NW".equals(status);
    }

    private PendingIntent compassPendingIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.coloros.compass2");
        if (launch == null) return null;
        return PendingIntent.getActivity(this, 100, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    "compass", "点阵指南针", NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "compass")
                : new Notification.Builder(this);
        PendingIntent launch = compassPendingIntent();
        builder.setContentTitle("点阵指南针")
                .setContentText("屏幕点亮时更新方向")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true);
        if (launch != null) builder.setContentIntent(launch);
        return builder.build();
    }

    private Typeface loadTypeface(String asset) {
        try {
            return Typeface.createFromAsset(getAssets(), asset);
        } catch (Exception ignored) {
            return Typeface.DEFAULT;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticAccuracy = accuracy;
        }
    }

    @Override
    public void onDestroy() {
        stopSensors();
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        for (Bitmap bitmap : frameBuffers) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        if (matrixBitmap != null && !matrixBitmap.isRecycled()) matrixBitmap.recycle();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
