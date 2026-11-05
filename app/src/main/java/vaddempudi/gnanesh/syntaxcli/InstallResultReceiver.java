package vaddempudi.gnanesh.syntaxcli;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import vaddempudi.gnanesh.syntaxcli.tuils.Tuils;

public class InstallResultReceiver extends BroadcastReceiver {
    private static final String TAG = "SYNTAXCLI_K";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        // -------- PENDING USER ACTION: launch the confirmation dialog --------
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent prompt = null;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    prompt = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                } else {
                    Object o = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (o instanceof Intent) prompt = (Intent) o;
                }
            } catch (Throwable t) {
                Log.e(TAG, "failed to read EXTRA_INTENT", t);
            }

            if (prompt != null) {
                prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(prompt);
                    send(ctx, "[k] confirmation dialog opened — tap Install");
                    Log.d(TAG, "launched pending user action");
                } catch (Throwable t) {
                    send(ctx, "[k] could not open install dialog: " + t);
                    Log.e(TAG, "startActivity prompt failed", t);
                }
            } else {
                send(ctx, "[k] PENDING_USER_ACTION but no EXTRA_INTENT");
                Log.e(TAG, "PENDING_USER_ACTION with null EXTRA_INTENT");
            }
            return;
        }

        // -------- Final result ------------------------------------------------
        String line = status == PackageInstaller.STATUS_SUCCESS
                ? "[k] INSTALL OK: " + pkg
                : "[k] INSTALL FAIL (" + statusName(status) + "): " + pkg + " — " + msg;

        send(ctx, line);
        Log.d(TAG, line);
    }

    private void send(Context ctx, String line) {
        try { Tuils.sendOutput(ctx, line); } catch (Throwable ignored) {}
        try {
            java.io.File out = new java.io.File(ctx.getFilesDir(), "k_debug.log");
            java.io.FileWriter fw = new java.io.FileWriter(out, true);
            fw.write("----- " + new java.util.Date() + " -----\n");
            fw.write(line + "\n\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static String statusName(int s) {
        switch (s) {
            case PackageInstaller.STATUS_SUCCESS: return "SUCCESS";
            case PackageInstaller.STATUS_FAILURE: return "FAILURE";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "INCOMPATIBLE";
            case PackageInstaller.STATUS_PENDING_USER_ACTION: return "PENDING_USER_ACTION";
            default: return String.valueOf(s);
        }
    }
}