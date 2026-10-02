package com.example.compasswidget2;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * 黑底版指南针组件（与 CompassWidgetProvider 共用同一个 CompassService，
 * 区别仅在绘制时用黑色圆角卡片背景）。
 */
public class CompassBlackWidgetProvider extends AppWidgetProvider {

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
