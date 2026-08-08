package com.firstham.aethergui;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.annotation.SuppressLint;

public final class AethonTileService extends TileService {
    public static final String EXTRA_CONNECT_FROM_TILE = "connect_from_tile";

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override public void onStopListening() {
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        String state = getSharedPreferences("service_state", MODE_PRIVATE).getString("state", "disconnected");
        if (VpnConnectionController.canDisconnect(state)) {
            VpnConnectionController.disconnect(this);
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences("aether", MODE_PRIVATE);
        if (!"manual".equals(preferences.getString("mode", "vpn")) && VpnService.prepare(this) != null) {
            openPermissionScreen();
            return;
        }
        VpnConnectionController.connect(this, preferences);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String state = getSharedPreferences("service_state", MODE_PRIVATE).getString("state", "disconnected");
        if ("connected".equals(state)) {
            tile.setState(Tile.STATE_ACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_connected));
        } else if ("error".equals(state) || "blocked".equals(state)) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_unavailable));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_disconnected));
        }
        tile.setLabel(getString(R.string.tile_name));
        tile.updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openPermissionScreen() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CONNECT_FROM_TILE, true);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    static void requestUpdate(Context context) {
        requestListeningState(context, new ComponentName(context, AethonTileService.class));
    }
}
