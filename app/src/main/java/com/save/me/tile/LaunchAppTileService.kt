package com.save.me.tile

import android.content.Intent
import android.service.quicksettings.TileService
import com.save.me.MainActivity

class LaunchAppTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }
}