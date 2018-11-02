package com.samsung.microbit.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.samsung.microbit.service.HubService;

public class HubServiceRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("HubServiceRestartReceiver", "onReceive! restarting service");
        context.startService(new Intent(context,HubService.class));
    }
}
