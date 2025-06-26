package com.save.me

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class RemoteTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set window flags to wake and show the activity on lock screen and turn screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        // Do NOT use FLAG_KEEP_SCREEN_ON (so the screen can turn off after we leave)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Ensure this activity is not kept in the history stack (for extra safety)
        // This is also set in the manifest, but we do it here for redundancy
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

        val serviceIntent = Intent(this, ForegroundActionService::class.java)
        intent?.extras?.let { serviceIntent.putExtras(it) }

        // Start the service (camera/mic/etc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Remove screen-on flags promptly after starting the service
        Handler(Looper.getMainLooper()).post {
            // Remove the screen-on/turn-on flags so the screen can turn off again
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(false)
            }
            // Move task to back so MainActivity does not come to foreground
            moveTaskToBack(true)
            // Finish activity immediately
            finish()
        }
    }
}