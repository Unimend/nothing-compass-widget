package com.example.compasswidget2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Locale;

public class CompassService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.compasswidget2.START";
    public static final String ACTION_STOP = "com.example.compasswidget2.STOP";

    private static final float SMOOTHING = 0.12f;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private float[] gravity = new float[3];
    private float[] geomagnetic = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;

    private float smoothedAzimuth = Float.NaN;
    private Typeface ndotTypeface;

    private LocationManager locationManager;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ndotTypeface = Typeface.createFromAsset(getAssets(), "ndot.otf");
        } catch (Exception e) {
            ndotTypeface = Typeface.DEFAULT;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        }
        startLocation();
        return START_STICKY;
    }

    private void startLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        String[] providers = {
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String p : providers) {
            try {
                locationManager.requestLocationUpdates(p, 3000, 0, locationListener);
            } catch (Exception ignored) {
            }
        }
        updateFromLastKnown();
        // 主动请求一次当前位置（API 30+，用 Fused 提供者）
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                locationManager.getCurrentLocation(
                        LocationManager.FUSED_PROVIDER, null,
                        getMainExecutor(),
                        location -> {
                            Log.d("CompassWidget", "getCurrentLocation(fused) = " +
                                    (location != null ? location.getLatitude() + "," + location.getLongitude() : "null"));
                            if (location != null) {
                                latitude = location.getLatitude();
                                longitude = location.getLongitude();
                            }
                        });
            } catch (Exception e) {
                Log.e("CompassWidget", "getCurrentLocation failed: " + e);
            }
        }
    }

    private void updateFromLastKnown() {
        try {
            Location best = null;
            String[] providers = {
                    LocationManager.FUSED_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
            };
            for (String p : providers) {
                Location l = locationManager.getLastKnownLocation(p);
                Log.d("CompassWidget", "getLastKnownLocation(" + p + ") = " +
                        (l != null ? l.getLatitude() + "," + l.getLongitude() : "null"));
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best != null) {
                latitude = best.getLatitude();
                longitude = best.getLongitude();
            }
        } catch (Exception e) {
            Log.e("CompassWidget", "updateFromLastKnown failed: " + e);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravity = event.values.clone();
            hasGravity = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = event.values.clone();
            hasGeomagnetic = true;
        }
        if (hasGravity && hasGeomagnetic) {
            updateWidget();
        }
    }

    private void updateWidget() {
        float[] rotationMatrix = new float[9];
        float[] inclinationMatrix = new float[9];
        boolean success = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic);
        if (!success) {
            return;
        }
        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);
        float rawAzimuth = (float) Math.toDegrees(orientation[0]);
        rawAzimuth = (rawAzimuth + 360) % 360;
        float azimuth = smoothAngle(rawAzimuth);

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        updateProviderWidgets(mgr, azimuth, CompassWidgetProvider.class, false);
        updateProviderWidgets(mgr, azimuth, CompassBlackWidgetProvider.class, true);
    }

    private void updateProviderWidgets(AppWidgetManager mgr, float azimuth,
                                       Class<?> providerClass, boolean black) {
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, providerClass));
        for (int id : ids) {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_layout);
            views.setImageViewBitmap(R.id.arrow, drawCompass(azimuth, black));
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.coloros.compass2");
            if (launchIntent != null) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, 0, launchIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                views.setOnClickPendingIntent(R.id.arrow, pi);
            }
            mgr.updateAppWidget(id, views);
        }
    }

    private float smoothAngle(float newAngle) {
        if (Float.isNaN(smoothedAzimuth)) {
            smoothedAzimuth = newAngle;
        } else {
            float diff = newAngle - smoothedAzimuth;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            smoothedAzimuth += SMOOTHING * diff;
            smoothedAzimuth = (smoothedAzimuth + 360) % 360;
        }
        return smoothedAzimuth;
    }

    private Bitmap drawCompass(float azimuth, boolean black) {
        int size = 512;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (black) {
            // 黑底版：不透明黑色圆角卡片背景
            Paint bg = new Paint();
            bg.setColor(Color.BLACK);
            bg.setAntiAlias(true);
            canvas.drawRoundRect(0, 0, size, size, 72f, 72f, bg);
        }
        // 透明版：不填充背景

        float cx = size / 2f;
        float cy = size / 2f;
        float dialCy = size / 2f; // 旋转中心 = 组件正中心 (256,256)

        // N / E / S / W 标签
        Paint labelPaint = new Paint();
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTypeface(ndotTypeface);
        labelPaint.setTextSize(56f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setAntiAlias(true);

        // 测量各字母字形边界，让 N/S/W/E 的「字形边缘」到组件边缘距离统一为 edge（30px）。
        // 注意 drawText 的 y 是基线（字形向上延伸），x（CENTER 对齐）是字形中心。
        float edge = 30f;
        android.graphics.Rect bN = new android.graphics.Rect();
        android.graphics.Rect bS = new android.graphics.Rect();
        android.graphics.Rect bW = new android.graphics.Rect();
        android.graphics.Rect bE = new android.graphics.Rect();
        labelPaint.getTextBounds("N", 0, 1, bN);
        labelPaint.getTextBounds("S", 0, 1, bS);
        labelPaint.getTextBounds("W", 0, 1, bW);
        labelPaint.getTextBounds("E", 0, 1, bE);

        // N（顶）：字形顶部距顶 edge
        canvas.drawText("N", cx, edge - bN.top, labelPaint);
        // S（底）：字形底部距底 edge
        canvas.drawText("S", cx, size - edge - bS.bottom, labelPaint);
        // W（左）：字形左边距左 edge，垂直居中
        canvas.drawText("W", edge + bW.width() / 2f, cy - (bW.top + bW.bottom) / 2f, labelPaint);
        // E（右）：字形右边距右 edge，垂直居中
        canvas.drawText("E", size - edge - bE.width() / 2f, cy - (bE.top + bE.bottom) / 2f, labelPaint);

        // 点阵箭头（旋转指向方向，绕表盘中心）
        canvas.save();
        canvas.rotate(azimuth, cx, dialCy);
        drawDotArrow(canvas, cx, dialCy);
        canvas.restore();

        // 角度 + 经纬度 合并成一行
        Paint infoPaint = new Paint();
        infoPaint.setColor(Color.WHITE);
        infoPaint.setTypeface(ndotTypeface);
        infoPaint.setTextSize(34f);
        infoPaint.setTextAlign(Paint.Align.CENTER);
        infoPaint.setAntiAlias(true);

        String latStr = Double.isNaN(latitude) ? "--.--N" : String.format(Locale.US, "%.2fN", latitude);
        String lonStr = Double.isNaN(longitude) ? "--.--E" : String.format(Locale.US, "%.2fE", longitude);
        String info = Math.round(azimuth) + "  " + latStr + "  " + lonStr;
        canvas.drawText(info, cx, size - 140f, infoPaint);

        return bmp;
    }

    private void drawDotArrow(Canvas canvas, float cx, float cy) {
        String[] pattern = {
                "....X....",
                "...XXX...",
                "..XXXXX..",
                ".XXXXXXX.",
                "...XXX...",
                "...XXX...",
                "...XXX...",
        };
        float dotSize = 6f;
        float gap = 17f;
        int cols = 9;
        int rows = pattern.length;
        float startX = cx - (cols * gap) / 2f;
        float startY = cy - (rows * gap) / 2f;

        Paint dotPaint = new Paint();
        dotPaint.setColor(Color.RED);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(true);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (pattern[r].charAt(c) == 'X') {
                    canvas.drawCircle(startX + c * gap, startY + r * gap, dotSize, dotPaint);
                }
            }
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                "compass", "指南针", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(channel);
        return new Notification.Builder(this, "compass")
                .setContentTitle("指南针")
                .setContentText("正在运行")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
