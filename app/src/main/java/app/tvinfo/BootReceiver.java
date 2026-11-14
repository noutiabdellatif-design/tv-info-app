package app.tvinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startForegroundService(new Intent(context, InfoService.class));
        } catch (Throwable t) {
            Log.e("TvInfo", "boot start failed", t);
        }
    }
}
