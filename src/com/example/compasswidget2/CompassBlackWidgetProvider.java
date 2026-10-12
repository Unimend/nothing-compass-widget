package com.example.compasswidget2;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * The single black-card compass widget provider.
 */
public class CompassBlackWidgetProvider extends AppWidgetProvider {

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            startCompass(context);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        showInitialState(context, appWidgetManager, appWidgetIds);
        startCompass(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        showInitialState(context, appWidgetManager, new int[]{appWidgetId});
        startCompass(context);
    }

    @Override
    public void onDisabled(Context context) {
        context.stopService(new Intent(context, CompassService.class));
    }

    private void startCompass(Context context) {
        Intent intent = new Intent(context, CompassService.class)
                .setAction(CompassService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Some launchers defer background starts. Their next widget update retries.
        }
    }

    private void showInitialState(Context context, AppWidgetManager manager, int[] ids) {
        PendingIntent compassIntent = compassPendingIntent(context);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
            views.setImageViewResource(R.id.compass_content, R.drawable.widget_compass_placeholder);
            if (compassIntent != null) {
                views.setOnClickPendingIntent(R.id.click_target, compassIntent);
            }
            manager.updateAppWidget(id, views);
        }
    }

    private PendingIntent compassPendingIntent(Context context) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .setClassName("com.coloros.compass2",
                        "com.coloros.compass.flat.FlatCompass")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(context, 100, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
