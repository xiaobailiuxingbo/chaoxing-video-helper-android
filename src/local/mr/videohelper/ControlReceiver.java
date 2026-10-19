package local.mr.videohelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("local.mr.videohelper.PAUSE".equals(action)) AppState.pause(context);
        else if ("local.mr.videohelper.START".equals(action)) AppState.start(context);
        else if ("local.mr.videohelper.STOP".equals(action)) AppState.stop(context);
        if (PlaybackService.instance != null) PlaybackService.instance.refreshNotification();
    }
}
