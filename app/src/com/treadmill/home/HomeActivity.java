package com.treadmill.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Home screen: big tiles for every launchable app except iFit's own, streaming apps first. */
public class HomeActivity extends Activity {

    private static final int COLUMNS = 4;
    private static final String MAINTENANCE = "Maintenance";
    /**
     * Home screen order. Each row is one service; several package names cover the official app and
     * the Google-free alternatives that run on GlassOS (no Play Services). Anything else sorts
     * alphabetically after these.
     */
    private static final String[][] STREAMING_ORDER = {
            {"com.netflix.mediaclient"},                       // Netflix
            {"com.google.android.youtube",                     // YouTube (official)
                    "org.schabi.newpipe",                      // NewPipe
                    "org.smarttube.stable",                    // SmartTube (stable)
                    "org.smarttube.beta",                      // SmartTube (beta)
                    "com.teamsmart.videomanager.tv",           // SmartTube (older builds)
                    "com.futo.platformplayer"},                // Grayjay
            {"com.amazon.avod.thirdpartyclient",               // Prime Video (phone/tablet)
                    "com.amazon.avod"},                        // Prime Video (Fire build)
            {"com.hulu.plus"},                                 // Hulu
            {"com.disney.disneyplus"},                         // Disney+ (Hulu content is moving here)
            {"com.crunchyroll.crunchyroid"},                   // Crunchyroll
    };

    private final List<Tile> tiles = new ArrayList<>();
    private TileAdapter adapter;

    private static class Tile {
        final String label;
        final Intent intent;
        final ResolveInfo info; // null for built-in tiles

        Tile(String label, Intent intent, ResolveInfo info) {
            this.label = label;
            this.intent = intent;
            this.info = info;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 24));
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        TextView title = new TextView(this);
        title.setText("Treadmill");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        title.setPadding(dp(8), 0, 0, dp(24));
        root.addView(title);

        GridView grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setHorizontalSpacing(dp(24));
        grid.setVerticalSpacing(dp(24));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Tile tile = tiles.get(position);
            if (tile.intent == null) {
                openMaintenance();
                return;
            }
            try {
                startActivity(tile.intent);
            } catch (Exception ignored) {
                // App was uninstalled or disabled since the list was built.
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, OverlayService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTiles();
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to.
    }

    private void loadTiles() {
        tiles.clear();
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        List<Tile> found = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.startsWith("com.ifit.") || pkg.equals(getPackageName())
                    || pkg.equals("com.android.settings")) {
                continue;
            }
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            found.add(new Tile(String.valueOf(ri.loadLabel(pm)), launch, ri));
        }
        // Streaming apps first, then the rest alphabetically.
        Collections.sort(found, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            return ra != rb ? ra - rb : a.label.compareToIgnoreCase(b.label);
        });
        tiles.addAll(found);
        tiles.add(new Tile("Settings",
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null));
        tiles.add(new Tile(MAINTENANCE, null, null)); // no intent: handled by openMaintenance()
        adapter.notifyDataSetChanged();
    }

    private static int rank(Tile tile) {
        String pkg = tile.info.activityInfo.packageName;
        for (int i = 0; i < STREAMING_ORDER.length; i++) {
            for (String candidate : STREAMING_ORDER[i]) {
                if (pkg.equals(candidate)) return i;
            }
        }
        return STREAMING_ORDER.length;
    }

    /**
     * Re-entering privileged mode (needed for ADB after every restart) means reaching iFit's own
     * screens, which our app otherwise pushes away. Open iFit's code box directly and stand down
     * until ⌂ Home is tapped.
     */
    private void openMaintenance() {
        OverlayService.pauseTakeovers();
        android.widget.Toast.makeText(this, "Maintenance: iFit screens allowed until you tap ⌂ Home",
                android.widget.Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent("com.ifit.eru.PRIVILEGED_DIALOG")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent().setClassName("com.ifit.mithlond",
                        "com.ifit.mithlond.view.SplashScreenActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // Older iFit "standalone" consoles (e.g. the 1750): open the iFit app itself.
                Intent ifit = getPackageManager().getLaunchIntentForPackage("com.ifit.standalone");
                if (ifit != null) startActivity(ifit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private class TileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tiles.size();
        }

        @Override
        public Object getItem(int position) {
            return tiles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = tiles.get(position);

            LinearLayout box = new LinearLayout(HomeActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setMinimumHeight(dp(220));
            box.setPadding(dp(16), dp(16), dp(16), dp(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(36, 40, 52));
            bg.setCornerRadius(dp(20));
            box.setBackground(bg);

            ImageView icon = new ImageView(HomeActivity.this);
            if (tile.info != null) {
                icon.setImageDrawable(tile.info.loadIcon(getPackageManager()));
            } else if (tile.intent == null) {
                icon.setImageResource(android.R.drawable.ic_menu_manage);
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_preferences);
            }
            box.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));

            TextView label = new TextView(HomeActivity.this);
            label.setText(tile.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(12), 0, 0);
            box.addView(label);

            return box;
        }
    }
}
