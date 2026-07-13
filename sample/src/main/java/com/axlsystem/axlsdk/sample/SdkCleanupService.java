package com.axlsystem.axlsdk.sample;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.axlsystem.axlsdk.Sdk;

/**
 * Safety-net service that stops the RFID reader and disconnects the SDK when
 * the user swipes the app away from the recents screen.
 *
 * WHY A SERVICE:
 * The primary disconnect happens in Activity.onStop(isFinishing=true).
 * On some OEM devices onStop() does not receive isFinishing=true on task
 * removal. This service registered with android:stopWithTask="false" receives
 * onTaskRemoved() reliably and acts as a secondary guarantee.
 *
 * WHY disconnectBlocking():
 * sdk.disconnect() submits work to connectExecutor and returns immediately.
 * If onTaskRemoved() returns right after, Android kills the process before
 * the executor runs — the firmware keeps scanning. disconnectBlocking() sends
 * read_stop (up to 3 attempts, 400 ms gaps) then disconnect_sync synchronously
 * on the calling thread — onTaskRemoved() cannot return until the writes
 * complete. Does not fire SdkListener callbacks.
 *
 * Lifecycle:
 *   - MainActivity.onConnected()    → startService()  (arms the guard)
 *   - MainActivity.onDisconnected() → stopService()   (clean disconnect — guard not needed)
 *   - User swipes app from recents  → onTaskRemoved() → disconnectBlocking() → stopSelf()
 */
public class SdkCleanupService extends Service {

    private static final String TAG = "SdkCleanupService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "App removed from recents — disconnecting SDK");
        try {
            Sdk.getInstance().disconnectBlocking();
        } catch (Exception e) {
            Log.w(TAG, "disconnectBlocking on task removal: " + e.getMessage());
        } finally {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }
}
