package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * The prayer-time alarm. Plays the azan (AzanActivity) when the TV is on, the prayer is still switched on,
 * the alarm is on time, and no Live TV or HDMI input is in front; then sets the next prayer's alarm.
 * Glass TV may open the azan over another app because its remote-key service is bound.
 */
public class AzanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int prayer = intent.getIntExtra("prayer", -1);
        long at = intent.getLongExtra("at", 0);
        String zone = Azan.prefs(ctx).getString("zone", "");
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            boolean awake = pm == null || pm.isInteractive();
            boolean onTime = Math.abs(System.currentTimeMillis() - at) <= Azan.LATE_MS;
            boolean liveTv = InputKeyService.liveTvInFront();
            if (prayer >= 0 && Azan.on(ctx, prayer) && awake && onTime && !liveTv) {
                ctx.startActivity(new Intent(ctx, AzanActivity.class)
                        .putExtra("prayer", prayer)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } else {
                Log.i("TvHome.Azan", "skipped: on=" + (prayer >= 0 && Azan.on(ctx, prayer)) + " awake=" + awake
                        + " onTime=" + onTime + " liveTv=" + liveTv);
            }
        } finally {
            // The next prayer's alarm (this one is now in the past, so it is skipped).
            Azan.schedule(ctx, zone);
        }
    }
}
