package com.treadmill.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * The Android home screen (see HomeView for its tiles). On consoles without GlassOS, iFit only
 * takes the Start key while its dashboard is in front, so this hands over to the overlay's cover:
 * iFit's dashboard comes forward underneath the same tiles (OverlayService.showHome).
 */
public class HomeActivity extends Activity implements HomeView.Listener {

    private HomeView home;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        home = new HomeView(this, this);
        setContentView(home);
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, OverlayService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        home.reload();
        if (!OverlayService.isGlassOsConsole(this) && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, OverlayService.class)
                    .putExtra(OverlayService.EXTRA_SHOW_HOME, true));
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to.
    }

    @Override
    public void onLaunch(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            // App was uninstalled or disabled since the list was built.
        }
    }

    @Override
    public void onMaintenance() {
        OverlayService.pauseTakeovers();
        android.widget.Toast.makeText(this, "Maintenance: iFit screens allowed until you tap ⌂ Home",
                android.widget.Toast.LENGTH_LONG).show();
        HomeView.openIfitForMaintenance(this);
    }
}
