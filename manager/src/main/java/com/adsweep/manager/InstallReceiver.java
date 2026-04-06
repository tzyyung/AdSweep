package com.adsweep.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * Receives install session status updates.
 * When status is PENDING_USER_ACTION, launches the confirmation intent.
 */
public class InstallReceiver extends BroadcastReceiver {

    private static final String TAG = "AdSweep.InstallRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        Log.i(TAG, "Install status: " + status + " msg: " + msg);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Need user to confirm — launch the confirmation activity
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirmIntent);
                    Log.i(TAG, "Launched install confirmation");
                }
                break;

            case PackageInstaller.STATUS_SUCCESS:
                Log.i(TAG, "Install SUCCESS!");
                Toast.makeText(context, "AdSweep: Install complete!", Toast.LENGTH_LONG).show();
                break;

            default:
                Log.e(TAG, "Install failed: status=" + status + " msg=" + msg);
                Toast.makeText(context, "Install failed: " + msg, Toast.LENGTH_LONG).show();
                break;
        }
    }
}
