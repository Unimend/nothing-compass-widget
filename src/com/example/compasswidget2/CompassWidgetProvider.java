package com.example.compasswidget2;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

public class CompassWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Intent intent = new Intent(context, CompassService.class);
        intent.setAction(CompassService.ACTION_START);
        context.startForegroundService(intent);
    }

    @Override
    public void onDisabled(Context context) {
        Intent intent = new Intent(context, CompassService.class);
        intent.setAction(CompassService.ACTION_STOP);
        context.startService(intent);
    }
}
