package com.save.me.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.save.me.ForegroundActionService
import com.save.me.R

class StartStopTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val isActive = ForegroundActionService.isRunning(this)
        if (isActive) {
            ForegroundActionService.stop(this)
        } else {
            ForegroundActionService.start(this)
        }
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val active = ForegroundActionService.isRunning(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (active) R.drawable.ic_qs_tile_pointer_active else R.drawable.ic_qs_tile_pointer
        )
        tile.label = if (active) "Remote: Active" else "Remote: Inactive"
        tile.updateTile()
    }
}