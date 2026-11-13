package com.treadmill.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Floating, read-only panels on top of every app: a bar with speed, incline, time and distance,
 * and a 400 m track with laps, pace and moving time.
 * Values come from GlassOS on the console itself. Consoles without GlassOS (older iFit
 * "standalone", e.g. the Commercial 1750) get them from iFit's own log lines instead (IfitLog);
 * there the on-screen motor controls are hidden and the console's own buttons are the only controls.
 */
public class OverlayService extends Service {

    private static final String TAG = "TreadmillOverlay";
    static final String EXTRA_FROM_BOOT = "fromBoot";
    static final String EXTRA_SHOW_HOME = "showHome";
    private static final long[] BOOT_HOME_DELAYS_MS = {45_000, 90_000};
    private static final long FOREGROUND_WATCH_MS = 1000;
    private static final String IFIT_NO_CONNECTION = "NoConnectionActivity";
    private static volatile boolean takeoversPaused;
    private static final long DIM_AFTER_MS = 10 * 60_000;
    private static final long SETUP_EVERY_MS = 5 * 60_000;
    private static final int DIM_LEVEL = 12; // of 255; readable up close, dark from across the room
    private static final String CHANNEL = "overlay";
    private static final long POLL_MS = 1000;
    private static final long RETRY_MS = 3000;
    private static final double KM_PER_MILE = 1.609344;
    // Slight dim when the belt is stopped; anything lower is hard to read over busy screens.
    private static final float STOPPED_ALPHA = 0.85f;
    private static final int LOCK_STATE_LOCKED = 2; // IdleModeLockoutState
    private static final int WORKOUT_RUNNING = 3, WORKOUT_PAUSED = 4; // WorkoutState (IDLE 1, DMK 2, RESULTS 5)

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler poller;
    private GlassOsClient client;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout bar;
    private TextView speedText, inclineText, timeText, distanceText, lockText;
    private TextView startStop, pauseResume, speedDown, speedUp, inclineDown, inclineUp, controlsToggle;
    private long workoutCommandAt; // last on-screen Start/Stop tap
    private static final long WORKOUT_COMMAND_SETTLE_MS = 3000;
    private boolean controlsShown;
    private volatile double lastKph, lastInclinePct; // latest readings, for the ± controls
    private volatile int workoutState; // latest WorkoutState; 0 = not read yet
    private static final double STEP_MPH = 0.5, MIN_MPH = 0.5, MAX_MPH = 11.5; // board max ~11.8 mph
    private static final double MIN_INCLINE = -3, MAX_INCLINE = 12;           // board range
    private static final long CONTROLS_HIDE_MS = 8000;
    private GradientDrawable barBackground;
    private boolean keepingScreenOn;
    private volatile boolean locked;
    private boolean returnHomeWhenUnlocked;
    private boolean unlocking; // tapped the orange bar; waiting for iFit to clear the lock
    private static final String LOCKED_TEXT = "🔒  Treadmill locked: tap to unlock";
    private static final String UNLOCKING_TEXT = "⏳  Unlocking… about a minute";
    private String lastWatchedApp; // e.g. Netflix or Crunchyroll, to return to after iFit pops up
    private long lastActivityAt;   // last touch or belt movement, for the idle dimmer
    private volatile boolean dimmed;
    private volatile String foregroundPkg; // last app seen moving to the front (watchForeground)
    private volatile long lastGoodPollAt;
    private StatusServer status;
    private int savedBrightness = 200;
    private View touchWatcher;

    private TrackView track;
    private WindowManager.LayoutParams trackParams;
    // Time with the belt actually moving; GlassOS elapsed time also counts pauses.
    private long movingMs;
    private long lastPollAt;
    private boolean wasMoving;
    // Workout time on the tablet's own clock (RUNNING only, frozen while paused). GlassOS's
    // elapsed time ran ~13% slow under load on Oct 28, 2026, so the top bar shows this instead.
    private long workoutMs;
    private boolean wasRunning;
    private double ourKm; // speed x real time, logged next to GlassOS distance to check it
    private long lastClockLogAt;
    private static final long CLOCK_LOG_MS = 30_000;

    // Non-GlassOS consoles: belt state from iFit's log (see IfitLog). Guarded by itself.
    static final String GLASSOS_PKG = "com.ifit.glassos_service";
    private boolean glassOs;
    private final IfitLog ifitLog = new IfitLog();
    private Thread logReader;
    private volatile Process logcat;
    private volatile boolean stopping;
    private double logKm;      // distance from speed x time; iFit's log has no distance
    private long lastLogPollAt;
    // iFit only takes a Start press for a new workout while its dashboard is on screen. When it
    // ignores one, we bring the dashboard up so the next press starts the belt, then step back.
    static final String IFIT_STANDALONE_PKG = "com.ifit.standalone";
    private static final long START_HANDOFF_TIMEOUT_MS = 60_000;
    private static final long RETURN_AFTER_START_MS = 1500;
    private volatile int startPressBaseline = -1; // presses already in the log buffer at startup
    private int handledStartPresses;
    private long openedIfitForStartAt;            // 0 = not waiting on iFit's dashboard

    // Non-GlassOS home: our tiles as a full-screen cover over iFit, which must be in front for its
    // Start key to work. When iFit sits on a screen that won't take Start (login, a dialog, a
    // results page), the cover steps aside so it can be tapped, then comes back. Main thread only.
    private static final long NOT_READY_GRACE_MS = 3000; // iFit passes through splash screens
    private static final long FOREGROUND_GRACE_MS = 4000; // iFit coming forward after showHome
    private HomeView homeCover;
    private WindowManager.LayoutParams coverParams;
    private boolean homeWanted, coverShown;
    private long homeShownAt, notReadySince;
    private long leftHomeAt; // when we last sent the user to an app; iFit's events lag behind
    private TextView noticeText;

    @Override
    public void onCreate() {
        super.onCreate();
        glassOs = isInstalled(GLASSOS_PKG);
        Log.i(TAG, glassOs ? "GlassOS console" : "no GlassOS: reading belt state from iFit's log");
        startInForeground();
        addBar();
        addTrack();
        if (!glassOs) controlsToggle.setVisibility(View.GONE); // nothing to send commands to

        pollThread = new HandlerThread("glassos-poll");
        pollThread.start();
        poller = new Handler(pollThread.getLooper());
        if (!glassOs) startLogReader();
        poller.post(this::poll);
        main.post(this::watchForeground);
        addTouchWatcher();
        lastActivityAt = android.os.SystemClock.elapsedRealtime();
        restoreBrightness(); // a power-off while dimmed leaves the setting at DIM_LEVEL
        status = new StatusServer(this, this::statusLines);
        status.start();
        main.post(this::applyDeviceSetup);
        if (glassOs) main.postDelayed(this::checkMotorSafetyNet, 30_000); // watches the GlassOS motor service
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Re-applies DeviceSetup and the rotation lock at start (i.e. at boot) and every few minutes after. */
    private void applyDeviceSetup() {
        lockRotation();
        try {
            DeviceSetup.apply(this);
        } catch (Exception e) {
            Log.w(TAG, "device setup failed: " + e);
        }
        main.postDelayed(this::applyDeviceSetup, SETUP_EVERY_MS);
    }

    /** Every 30 s: the eru-restore safety net (only acts when we're device owner and eru is gone). */
    private void checkMotorSafetyNet() {
        long now = android.os.SystemClock.elapsedRealtime();
        long since = lastGoodPollAt == 0 ? now : now - lastGoodPollAt;
        DeviceSetup.restoreEruIfMotorDown(this, now, since);
        main.postDelayed(this::checkMotorSafetyNet, 30_000);
    }

    /** Our own part of the status page. Read from the status thread, so only volatile/simple reads. */
    private String statusLines() {
        long sincePoll = lastGoodPollAt == 0 ? -1
                : (android.os.SystemClock.elapsedRealtime() - lastGoodPollAt) / 1000;
        String source;
        if (glassOs) {
            source = "glassos    " + (sincePoll < 0 ? "never answered" : sincePoll <= 5 ? "answering"
                    : "no answer for " + sincePoll + " s");
        } else {
            source = "ifit log   " + (!canReadLogs() ? "NO PERMISSION (adb shell pm grant "
                    + getPackageName() + " android.permission.READ_LOGS)" : sincePoll < 0 ? "no reading yet"
                    : sincePoll <= 5 ? "reading" : "no fresh reading for " + sincePoll + " s");
        }
        return source + '\n'
                + "lock       " + (locked ? "LOCKED (orange bar showing)" : "unlocked") + '\n'
                + "screen     " + (dimmed ? "dimmed" : "normal") + '\n'
                + "takeovers  " + (takeoversPaused ? "paused (maintenance)" : "active") + '\n'
                + DeviceSetup.report(this);
    }

    /**
     * iFit keeps the screen on at full brightness forever (its screensaver, now disabled, used to
     * dim it). Dim after a while with the belt stopped and no touches; any touch or the belt
     * moving brings it back. Dims rather than turning the screen off: the console may have no way
     * to wake a fully-off screen.
     */
    private void checkIdleDim() {
        long idle = android.os.SystemClock.elapsedRealtime() - lastActivityAt;
        if (!dimmed && idle > DIM_AFTER_MS && android.provider.Settings.System.canWrite(this)) {
            android.content.ContentResolver cr = getContentResolver();
            savedBrightness = android.provider.Settings.System.getInt(cr,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 200);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, DIM_LEVEL);
            dimmed = true;
            Log.i(TAG, "screen dimmed after idle");
        }
    }

    private void wake() {
        lastActivityAt = android.os.SystemClock.elapsedRealtime();
        if (!dimmed) return;
        dimmed = false;
        if (android.provider.Settings.System.canWrite(this)) {
            android.provider.Settings.System.putInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, Math.max(savedBrightness, 100));
        }
        Log.i(TAG, "screen restored");
    }

    /** A 1x1 invisible window that hears about every touch on the screen (ACTION_OUTSIDE). */
    private void addTouchWatcher() {
        View watcher = new View(this);
        watcher.setOnTouchListener((v, e) -> {
            wake();
            return false;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(watcher, lp);
        touchWatcher = watcher;
    }

    /**
     * With iFit's servers blocked, iFit's core app pops up its "No Connection" screen over whatever
     * is playing. Push it away: back to the app that was in front, or to our home screen.
     * Needs usage access: adb shell appops set com.treadmill.home GET_USAGE_STATS allow
     */
    private void watchForeground() {
        try {
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - FOREGROUND_WATCH_MS * 3, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String pkg = null, cls = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.getPackageName();
                    cls = e.getClassName();
                }
            }
            if (pkg != null) {
                foregroundPkg = pkg;
                if (!glassOs && pkg.equals(IFIT_STANDALONE_PKG) && cls != null) {
                    synchronized (ifitLog) {
                        // Only when iFit's log hasn't named a screen (its line rotated away):
                        // these events can lag the log by a second and would undo a newer screen.
                        if (ifitLog.screen().isEmpty()) ifitLog.noteScreen(cls);
                    }
                }
                if (cls != null && cls.endsWith(IFIT_NO_CONNECTION) && !takeoversPaused()) {
                    Log.i(TAG, "pushing away iFit No Connection screen, back to " + lastWatchedApp);
                    Intent back = lastWatchedApp == null ? null
                            : getPackageManager().getLaunchIntentForPackage(lastWatchedApp);
                    if (back != null) {
                        startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        openOurHome(this);
                    }
                } else if (!glassOs && pkg.equals(IFIT_STANDALONE_PKG) && !homeWanted && !takeoversPaused()
                        && lastWatchedApp != null
                        && android.os.SystemClock.elapsedRealtime() - leftHomeAt > FOREGROUND_GRACE_MS) {
                    // iFit put itself in front over the app being watched (it does when a workout
                    // ends, or after restarting itself). We didn't ask for it: go back.
                    Intent back = getPackageManager().getLaunchIntentForPackage(lastWatchedApp);
                    if (back != null) {
                        Log.i(TAG, "iFit came forward on its own (" + cls + "); back to " + lastWatchedApp);
                        startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        leftHomeAt = android.os.SystemClock.elapsedRealtime(); // let it come forward
                    }
                } else if (!pkg.startsWith("com.ifit.") && !pkg.equals(getPackageName())
                        && !pkg.equals("android")) {
                    lastWatchedApp = pkg;
                } else if (pkg.equals(getPackageName())) {
                    lastWatchedApp = null;
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "foreground watch failed: " + ex);
        }
        checkIdleDim();
        main.postDelayed(this::watchForeground, FOREGROUND_WATCH_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_HOME, false) && !glassOs) {
            main.post(this::showHome);
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) {
            // After a restart iFit may load its own home on top; take the screen back once it's done,
            // but only from iFit: never pull the user out of Netflix/Crunchyroll.
            for (long delay : BOOT_HOME_DELAYS_MS) {
                main.postDelayed(() -> {
                    String fg = foregroundPkg;
                    if (!takeoversPaused() && (fg == null || fg.startsWith("com.ifit."))) openOurHome(this);
                }, delay);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Process p = logcat;
        if (p != null) p.destroy();
        main.removeCallbacksAndMessages(null);
        poller.removeCallbacksAndMessages(null);
        pollThread.quitSafely();
        if (client != null) client.close();
        if (coverShown) windowManager.removeView(homeCover);
        if (bar != null) windowManager.removeView(bar);
        if (track != null) windowManager.removeView(track);
        if (touchWatcher != null) windowManager.removeView(touchWatcher);
        if (status != null) status.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        if (!glassOs) {
            pollIfitLog();
            poller.postDelayed(this::poll, POLL_MS);
            return;
        }
        long delay = POLL_MS;
        try {
            if (client == null) client = new GlassOsClient(this, "localhost", 54321);
            double kph = GlassOsClient.field(client.call("com.ifit.glassos.SpeedService/GetSpeed"), 3);
            double incline = GlassOsClient.field(client.call("com.ifit.glassos.InclineService/GetIncline"), 3);
            int seconds = (int) GlassOsClient.field(client.call("com.ifit.glassos.ElapsedTimeService/GetElapsedTime"), 2);
            double km = GlassOsClient.field(client.call("com.ifit.glassos.DistanceService/GetDistance"), 3);
            boolean isLocked = GlassOsClient.field(
                    client.call("com.ifit.glassos.IdleModeLockoutService/GetIdleModeLockout"), 2) == LOCK_STATE_LOCKED;
            locked = isLocked;
            workoutState = (int) GlassOsClient.field(client.call("com.ifit.glassos.WorkoutService/GetWorkoutState"), 1);
            lastGoodPollAt = android.os.SystemClock.elapsedRealtime();
            main.post(() -> {
                show(kph, incline, seconds, km);
                showLock(isLocked);
            });
        } catch (Exception e) {
            Log.w(TAG, "GlassOS poll failed: " + e);
            main.post(this::showDisconnected);
            delay = RETRY_MS;
        }
        poller.postDelayed(this::poll, delay);
    }

    // ---- Non-GlassOS consoles: iFit's log ----

    private boolean canReadLogs() {
        return checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Follows iFit's log lines (tag mono-stdout) on a thread of its own, restarting logcat if it
     * ever exits. First reads what is already in the log buffer (so a workout that began before
     * this service did is picked up), then follows new lines. Start presses from that backlog are
     * history, not requests, so they're recorded as the baseline. Without READ_LOGS logcat only
     * returns our own lines, so the bar would sit at zero; it shows "--" instead (pollIfitLog).
     */
    private void startLogReader() {
        logReader = new Thread(() -> {
            if (canReadLogs()) {
                readLogcat("-d"); // dump the backlog and exit
                synchronized (ifitLog) {
                    startPressBaseline = ifitLog.startPresses();
                }
            }
            while (!stopping) {
                if (startPressBaseline < 0) startPressBaseline = 0; // permission granted later
                readLogcat("-T", "1"); // new lines only, until logcat exits
                if (!stopping) {
                    Log.w(TAG, "logcat exited; restarting");
                    android.os.SystemClock.sleep(RETRY_MS);
                }
            }
        }, "ifit-log");
        logReader.setDaemon(true);
        logReader.start();
    }

    private void readLogcat(String... mode) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList("logcat", "-v", "brief"));
            cmd.addAll(java.util.Arrays.asList(mode));
            cmd.addAll(java.util.Arrays.asList("mono-stdout:V", "*:S"));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            logcat = p;
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (ifitLog) {
                        ifitLog.accept(line, android.os.SystemClock.elapsedRealtime());
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            if (!stopping) Log.w(TAG, "iFit log reader failed: " + e);
        }
    }

    /** Once a second on the poll thread: turns iFit's last logged values into a bar update. */
    private void pollIfitLog() {
        if (!canReadLogs()) {
            main.post(this::showDisconnected);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        double kph, grade;
        IfitLog.Mode mode;
        boolean stale;
        int startPresses;
        boolean ready;
        synchronized (ifitLog) {
            kph = ifitLog.kph();
            grade = ifitLog.grade();
            mode = ifitLog.mode(now);
            stale = ifitLog.stale(now);
            startPresses = ifitLog.startPresses();
            ready = ifitLog.ready(now);
        }
        handleStartKey(mode, startPresses, now);
        main.post(() -> updateCover(ready));
        if (stale) { // running but iFit went quiet: don't show a speed we can't vouch for
            main.post(this::showDisconnected);
            lastLogPollAt = now;
            return;
        }
        workoutState = mode == IfitLog.Mode.RUNNING ? WORKOUT_RUNNING
                : mode == IfitLog.Mode.PAUSED ? WORKOUT_PAUSED : 1;
        if (mode == IfitLog.Mode.IDLE) {
            logKm = 0;
        } else if (lastLogPollAt != 0) {
            logKm += kph * (now - lastLogPollAt) / 3_600_000.0;
        }
        lastLogPollAt = now;
        lastGoodPollAt = now;
        final double km = logKm;
        final int seconds = mode == IfitLog.Mode.IDLE ? 0 : 1; // only "is there a workout" matters to show()
        main.post(() -> show(kph, grade, seconds, km));
    }

    /**
     * Poll thread. A Start press with no workout while iFit isn't in front just beeps (iFit only
     * enables the key on its dashboard), so bring the home screen up, which puts iFit's dashboard
     * in front underneath it: the user's next press starts the belt. Once the workout has started,
     * or after a minute without one, go back to what they were watching. Never starts the belt
     * itself: that always takes the physical Start key.
     */
    private void handleStartKey(IfitLog.Mode mode, int startPresses, long now) {
        int baseline = startPressBaseline;
        if (baseline < 0) return; // still reading the backlog
        if (handledStartPresses < baseline) handledStartPresses = baseline;
        boolean newPress = startPresses > handledStartPresses;
        handledStartPresses = startPresses;

        if (openedIfitForStartAt != 0) {
            if (mode != IfitLog.Mode.IDLE) {
                openedIfitForStartAt = 0;
                Log.i(TAG, "workout started from iFit's dashboard; stepping back");
                main.postDelayed(this::returnFromIfit, RETURN_AFTER_START_MS);
            } else if (now - openedIfitForStartAt > START_HANDOFF_TIMEOUT_MS) {
                openedIfitForStartAt = 0;
                main.post(this::returnFromIfit);
            }
            return;
        }
        if (newPress && mode == IfitLog.Mode.IDLE && !takeoversPaused()) {
            String fg = foregroundPkg;
            if (fg != null && fg.equals(IFIT_STANDALONE_PKG)) return; // iFit is up: it takes the press
            openedIfitForStartAt = now;
            Log.i(TAG, "Start pressed with no workout; bringing iFit's dashboard up for the next press");
            main.post(() -> {
                showHome();
                android.widget.Toast.makeText(this, "Press Start again to begin",
                        android.widget.Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * Main thread: after a Start hand-off, back to the app they were watching. If they were on the
     * home screen, they're already back there.
     */
    private void returnFromIfit() {
        String fg = foregroundPkg;
        if (takeoversPaused() || fg == null || !fg.startsWith("com.ifit.")) return;
        Intent back = lastWatchedApp == null ? null : getPackageManager().getLaunchIntentForPackage(lastWatchedApp);
        if (back != null) leaveHomeFor(back);
    }

    // ---- Non-GlassOS home: the cover over iFit ----

    static boolean isGlassOsConsole(android.content.Context context) {
        try {
            context.getPackageManager().getApplicationInfo(GLASSOS_PKG, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Main thread: our tiles on top, iFit's current screen (normally its dashboard) underneath. */
    private void showHome() {
        if (glassOs || takeoversPaused()) return;
        homeWanted = true;
        homeShownAt = android.os.SystemClock.elapsedRealtime();
        notReadySince = 0;
        Intent ifit = getPackageManager().getLaunchIntentForPackage(IFIT_STANDALONE_PKG);
        if (ifit != null) startActivity(ifit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        addCover();
    }

    /** Main thread: open an app from the home screen (or go back to one), taking the cover down. */
    private void leaveHomeFor(Intent intent) {
        homeWanted = false;
        leftHomeAt = android.os.SystemClock.elapsedRealtime();
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "could not open " + intent + ": " + e);
        }
        hideNotice();
        // Wait for the app to come up first, so iFit's dashboard doesn't flash in between.
        main.postDelayed(() -> {
            if (!homeWanted) removeCover("opened an app");
        }, 500);
    }

    private void addCover() {
        if (coverShown) return;
        if (homeCover == null) {
            homeCover = new HomeView(this, new HomeView.Listener() {
                @Override
                public void onLaunch(Intent intent) {
                    leaveHomeFor(intent);
                }

                @Override
                public void onMaintenance() {
                    homeWanted = false;
                    removeCover("maintenance");
                    hideNotice();
                    pauseTakeovers();
                    android.widget.Toast.makeText(OverlayService.this,
                            "Maintenance: iFit screens allowed until you tap ⌂ Home",
                            android.widget.Toast.LENGTH_LONG).show();
                    HomeView.openIfitForMaintenance(OverlayService.this);
                }
            });
            coverParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
        }
        Log.i(TAG, "cover up (iFit screen " + currentIfitScreen() + ")");
        homeCover.reload();
        windowManager.addView(homeCover, coverParams);
        // Same window type: the last one added is on top, so lift the bar and track back above.
        windowManager.removeView(bar);
        windowManager.addView(bar, params);
        windowManager.removeView(track);
        windowManager.addView(track, trackParams);
        coverShown = true;
        hideNotice();
    }

    private void removeCover(String why) {
        if (!coverShown) return;
        Log.i(TAG, "cover down: " + why + " (iFit screen " + currentIfitScreen() + ", front " + foregroundPkg + ")");
        windowManager.removeView(homeCover);
        coverShown = false;
    }

    private String currentIfitScreen() {
        synchronized (ifitLog) {
            return ifitLog.screen() + (ifitLog.dialogShown() ? " + dialog" : "");
        }
    }

    /**
     * Main thread, once a second. While the home screen is wanted, keep the cover up when iFit
     * would take Start; when it's stuck somewhere that won't (login, a dialog, results), step aside
     * so it can be tapped, with a note in the bar. Another app coming to the front ends it.
     */
    private void updateCover(boolean ready) {
        if (!homeWanted) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (takeoversPaused()) {
            homeWanted = false;
            removeCover("maintenance mode");
            hideNotice();
            return;
        }
        String fg = foregroundPkg;
        if (now - homeShownAt > FOREGROUND_GRACE_MS && fg != null && !fg.startsWith("com.ifit.")
                && !fg.equals(getPackageName()) && !fg.equals("android")) {
            homeWanted = false; // something else came forward (a notification, Settings...)
            removeCover("another app came forward");
            hideNotice();
            return;
        }
        if (ready) {
            notReadySince = 0;
            addCover();
        } else if (notReadySince == 0) {
            notReadySince = now;
        } else if (now - notReadySince > NOT_READY_GRACE_MS && coverShown) {
            Log.i(TAG, "iFit needs a tap; stepping aside");
            removeCover("iFit needs a tap");
            noticeText.setVisibility(View.VISIBLE);
        }
    }

    private void hideNotice() {
        if (noticeText != null) noticeText.setVisibility(View.GONE);
    }

    private void show(double kph, double incline, int seconds, double km) {
        lastKph = kph;
        lastInclinePct = incline;
        speedText.setText(String.format(Locale.US, "%.1f mph", kph / KM_PER_MILE));
        inclineText.setText(String.format(Locale.US, "%.1f%%", incline));
        distanceText.setText(String.format(Locale.US, "%.2f mi", km / KM_PER_MILE));

        boolean moving = kph > 0;
        updateStartStop(moving);
        if (moving) wake();
        long now = android.os.SystemClock.elapsedRealtime();
        if (seconds == 0 && km == 0) {
            movingMs = 0; // no workout: start the next one fresh
        } else if (moving && wasMoving) {
            movingMs += now - lastPollAt;
        }
        boolean running = workoutState == WORKOUT_RUNNING;
        if (running && wasRunning) {
            workoutMs += now - lastPollAt;
            ourKm += kph * (now - lastPollAt) / 3_600_000.0;
        } else if (!running && workoutState != WORKOUT_PAUSED) {
            workoutMs = 0; // no workout
            ourKm = 0;
        }
        if ((running || workoutState == WORKOUT_PAUSED) && now - lastClockLogAt >= CLOCK_LOG_MS) {
            lastClockLogAt = now;
            Log.i(TAG, String.format(Locale.US, "clock glassos=%ds ours=%ds moving=%ds km=%.3f ourKm=%.3f kph=%.1f state=%d",
                    seconds, workoutMs / 1000, movingMs / 1000, km, ourKm, kph, workoutState));
        }
        lastPollAt = now;
        wasMoving = moving;
        wasRunning = running;
        int workoutSec = (int) (workoutMs / 1000);
        timeText.setText(workoutSec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", workoutSec / 3600, workoutSec / 60 % 60, workoutSec % 60)
                : String.format(Locale.US, "%d:%02d", workoutSec / 60, workoutSec % 60));
        int movingSec = (int) (movingMs / 1000);
        String pace = "--:-- /mi";
        if (moving) {
            int secPerMile = (int) Math.round(3600 * KM_PER_MILE / kph);
            pace = String.format(Locale.US, "%d:%02d /mi", secPerMile / 60, secPerMile % 60);
        }
        track.update(km * 1000, pace, movingSec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", movingSec / 3600, movingSec / 60 % 60, movingSec % 60)
                : String.format(Locale.US, "%d:%02d", movingSec / 60, movingSec % 60));
        track.setAlpha(moving ? 1f : STOPPED_ALPHA);
        bar.setAlpha(moving ? 1f : STOPPED_ALPHA);
        if (moving != keepingScreenOn) {
            keepingScreenOn = moving;
            if (moving) {
                params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            } else {
                params.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            }
            windowManager.updateViewLayout(bar, params);
        }
    }

    /**
     * After sitting idle GlassOS locks the belt (hardware Start only beeps) and iFit dims the
     * screen. iFit's own unlock lives in its screensaver app, so the bar offers one instead.
     */
    private void showLock(boolean isLocked) {
        int stats = isLocked ? android.view.View.GONE : android.view.View.VISIBLE;
        speedText.setVisibility(stats);
        inclineText.setVisibility(stats);
        timeText.setVisibility(stats);
        distanceText.setVisibility(stats);
        controlsToggle.setVisibility(stats);
        if (isLocked) hideControls();
        lockText.setVisibility(isLocked ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!isLocked) {
            unlocking = false;
            lockText.setText(LOCKED_TEXT);
        }
        if (!isLocked && returnHomeWhenUnlocked) {
            returnHomeWhenUnlocked = false;
            if (!takeoversPaused()) openOurHome(this);
        }
        barBackground.setColor(!isLocked ? Color.argb(230, 0, 0, 0)
                : unlocking ? Color.argb(240, 120, 55, 15) : Color.argb(240, 200, 90, 20));
        if (isLocked) bar.setAlpha(1f);
    }

    /** Maintenance: stop automatically pulling our home forward until ⌂ Home is tapped. */
    static void pauseTakeovers() {
        takeoversPaused = true;
    }

    private static boolean takeoversPaused() {
        return takeoversPaused;
    }

    /**
     * Opens our home screen by name rather than via the HOME category, so it still works when iFit
     * has made itself the default home again (it does that after a restart).
     */
    static void openOurHome(android.content.Context context) {
        context.startActivity(new Intent(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /**
     * GlassOS refuses SetIdleModeLockout from our client ("Eriador is not allowed to set idle mode
     * lockout"), so let iFit unlock itself the approved way: opening its dashboard clears the lock.
     * Once the poll sees it unlocked, we bring our home screen back.
     */
    private void unlock() {
        // Show at once that the tap worked; iFit's dashboard can take a minute to clear the lock.
        unlocking = true;
        lockText.setText(UNLOCKING_TEXT);
        barBackground.setColor(Color.argb(240, 120, 55, 15));
        main.postDelayed(() -> { // if iFit never clears it, offer the tap again
            if (unlocking && locked) {
                unlocking = false;
                lockText.setText(LOCKED_TEXT);
                barBackground.setColor(Color.argb(240, 200, 90, 20));
            }
        }, 3 * 60_000);
        restoreBrightness();
        returnHomeWhenUnlocked = true;
        try {
            startActivity(new Intent().setClassName("com.ifit.mithlond", "com.ifit.mithlond.view.SplashScreenActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i(TAG, "opened iFit dashboard to clear idle lockout");
        } catch (Exception e) {
            Log.w(TAG, "could not open iFit dashboard: " + e);
        }
    }

    private void restoreBrightness() {
        if (!android.provider.Settings.System.canWrite(this)) return;
        android.content.ContentResolver cr = getContentResolver();
        int current = android.provider.Settings.System.getInt(cr,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 200);
        if (current < 50) {
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 200);
        }
    }

    /**
     * This console's panel is mounted upside down (ro.sf.hwrotation=180): the right way up is
     * ROTATION_180 with auto-rotate off. Something keeps turning auto-rotate back on, which flips
     * the screen, so put it back whenever it has changed.
     */
    private void lockRotation() {
        if (!android.provider.Settings.System.canWrite(this)) return;
        if (!"180".equals(systemProperty("ro.sf.hwrotation"))) return; // e.g. the 1750 (0): leave it alone
        android.content.ContentResolver cr = getContentResolver();
        int auto = android.provider.Settings.System.getInt(cr,
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 1);
        int rotation = android.provider.Settings.System.getInt(cr,
                android.provider.Settings.System.USER_ROTATION, 0);
        if (auto != 0 || rotation != android.view.Surface.ROTATION_180) {
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.USER_ROTATION,
                    android.view.Surface.ROTATION_180);
            Log.i(TAG, "rotation fixed (was auto=" + auto + " rotation=" + rotation + ")");
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    private void showDisconnected() {
        speedText.setText("-- mph");
        inclineText.setText("--%");
        timeText.setText("--:--");
        distanceText.setText("-- mi");
        bar.setAlpha(0.6f);
    }

    private void addBar() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(28), dp(10), dp(28), dp(10));
        barBackground = new GradientDrawable();
        barBackground.setColor(Color.argb(230, 0, 0, 0));
        barBackground.setCornerRadius(dp(40));
        bar.setBackground(barBackground);

        // The console has no reliable Back/Home buttons, so the bar carries one.
        TextView home = addStat(Color.WHITE);
        home.setText("⌂ Home");
        home.setPadding(dp(8), dp(6), dp(22), dp(6));
        home.setOnClickListener(v -> {
            takeoversPaused = false; // leaving maintenance
            openOurHome(this);
        });

        startStop = addControl("▶ Start", this::toggleWorkout);
        startStop.setMinWidth(dp(150));
        ((GradientDrawable) startStop.getBackground()).setColor(Color.rgb(40, 140, 75));
        pauseResume = addControl("❚❚ Pause", this::togglePause);
        pauseResume.setMinWidth(dp(170));
        speedText = addStat(Color.WHITE);
        speedDown = addControl("−", () -> stepSpeed(-1));
        speedUp = addControl("+", () -> stepSpeed(+1));
        inclineText = addStat(Color.rgb(255, 196, 64));
        inclineDown = addControl("−", () -> stepIncline(-1));
        inclineUp = addControl("+", () -> stepIncline(+1));
        timeText = addStat(Color.rgb(170, 200, 255));
        distanceText = addStat(Color.rgb(140, 230, 160));
        noticeText = addStat(Color.rgb(255, 170, 60));
        noticeText.setText("iFit needs a tap");
        noticeText.setVisibility(View.GONE);
        controlsToggle = addControl("±", () -> {
            if (controlsShown) hideControls();
            else showControls();
        });
        hideControls();
        lockText = addStat(Color.WHITE);
        lockText.setText(LOCKED_TEXT);
        lockText.setPadding(dp(24), dp(14), dp(24), dp(14));
        lockText.setVisibility(android.view.View.GONE);
        showDisconnected();

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(12);
        makeDraggable(bar, params, () -> {
            if (locked && !unlocking) unlock();
        }, () -> !locked); // while locked the whole bar is one button, not something to drag
        windowManager.addView(bar, params);
    }

    private void addTrack() {
        track = new TrackView(this);
        trackParams = new WindowManager.LayoutParams(dp(460), dp(240),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        trackParams.gravity = Gravity.BOTTOM | Gravity.END;
        trackParams.x = dp(16);
        trackParams.y = dp(110); // above iFit's bottom bar (Quick start / Workout settings)
        makeDraggable(track, trackParams, null, () -> true);
        windowManager.addView(track, trackParams);
    }

    /**
     * Slide a panel to move it out of the way of video controls; a tap runs onTap. Movement only
     * starts after a deliberate slide (dragSlop), so a tap never nudges the panel.
     */
    private void makeDraggable(android.view.View view, WindowManager.LayoutParams lp, Runnable onTap,
                               java.util.function.BooleanSupplier canDrag) {
        int dragSlop = dp(24);
        // END/BOTTOM gravity measures x/y from the right/bottom edge, so movement is inverted.
        int xSign = (lp.gravity & Gravity.END) == Gravity.END ? -1 : 1;
        int ySign = (lp.gravity & Gravity.BOTTOM) == Gravity.BOTTOM ? -1 : 1;
        view.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float startRawX, startRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = e.getRawX();
                        startRawY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        if (onTap != null) v.setAlpha(0.75f); // pressed look
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - startRawX, dy = e.getRawY() - startRawY;
                        if (!dragging && canDrag.getAsBoolean() && Math.hypot(dx, dy) > dragSlop) {
                            dragging = true;
                            v.setAlpha(1f);
                        }
                        if (dragging) {
                            lp.x = startX + xSign * (int) dx;
                            lp.y = startY + ySign * (int) dy;
                            windowManager.updateViewLayout(view, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(1f);
                        if (!dragging && onTap != null) onTap.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** A small rounded button inside the bar. */
    private TextView addControl(String label, Runnable onTap) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(52));
        t.setPadding(dp(10), dp(2), dp(10), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(64, 70, 88));
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        t.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(t, lp);
        return t;
    }

    // ---- On-screen speed/incline controls ----
    // Only ever adjusts a belt the user already started with the console's Start key; never starts
    // or stops it (that stays with the hardware Start/Stop keys and the safety key). Requests go
    // through the motor service, which applies the machine's own limits on top of ours.

    private void stepSpeed(int direction) {
        showControls(); // keep them up while being used
        if (lastKph <= 0) {
            notRunningToast();
            return;
        }
        double mph = Math.round(lastKph / KM_PER_MILE / STEP_MPH) * STEP_MPH; // snap to the step grid
        double target = Math.max(MIN_MPH, Math.min(MAX_MPH, mph + direction * STEP_MPH));
        speedText.setText(String.format(Locale.US, "→%.1f mph", target)); // instant feedback
        send(() -> client.setSpeedKph(target * KM_PER_MILE), "speed " + target + " mph");
    }

    private void stepIncline(int direction) {
        showControls();
        if (lastKph <= 0) { // the motor service refuses incline too unless a workout is RUNNING
            notRunningToast();
            return;
        }
        double target = Math.max(MIN_INCLINE, Math.min(MAX_INCLINE, Math.round(lastInclinePct) + direction));
        inclineText.setText(String.format(Locale.US, "→%.0f%%", target));
        send(() -> client.setInclinePercent(target), "incline " + target + "%");
    }

    private void notRunningToast() {
        android.widget.Toast.makeText(this, workoutState == WORKOUT_PAUSED ? "Tap Resume first"
                : "Press Start first", android.widget.Toast.LENGTH_SHORT).show();
    }

    private interface Write {
        void run() throws Exception;
    }

    private void send(Write write, String what) {
        poller.post(() -> {
            try {
                if (client == null) client = new GlassOsClient(this, "localhost", 54321);
                write.run();
                Log.i(TAG, "set " + what);
            } catch (Exception e) {
                Log.w(TAG, "could not set " + what + ": " + e);
            }
        });
    }

    private final Runnable autoHideControls = this::hideControls;

    /** A workout is under way (running or paused), so Start would throw away its time and laps. */
    private boolean inWorkout() {
        return workoutState == WORKOUT_RUNNING || workoutState == WORKOUT_PAUSED
                || (workoutState == 0 && lastKph > 0); // state not read yet: go by the belt
    }

    /**
     * One button that follows the workout: "▶ Start" when there is none (a manual workout at a
     * slow walk, like iFit's Quick start), "■ Stop" while running or paused. Repeat taps are
     * ignored for a few seconds while the belt responds. The hardware Stop key and the safety key
     * always override it.
     */
    private void toggleWorkout() {
        showControls();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - workoutCommandAt < WORKOUT_COMMAND_SETTLE_MS) return;
        workoutCommandAt = now;
        if (inWorkout()) {
            startStop.setText("■ Stopping…");
            send(() -> client.stopWorkout(), "workout stop");
        } else {
            if (locked) return; // the orange bar handles the lock first
            startStop.setText("▶ Starting…");
            send(() -> client.startWorkout(), "workout start");
        }
    }

    /**
     * "❚❚ Pause" while running, "▶ Resume" while paused: the belt stops but GlassOS keeps the
     * workout's time, distance and laps. Hidden when there is no workout.
     */
    private void togglePause() {
        showControls();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - workoutCommandAt < WORKOUT_COMMAND_SETTLE_MS) return;
        if (workoutState == WORKOUT_RUNNING) {
            workoutCommandAt = now;
            pauseResume.setText("❚❚ Pausing…");
            send(() -> client.pauseWorkout(), "workout pause");
        } else if (workoutState == WORKOUT_PAUSED) {
            if (locked) return; // the orange bar handles the lock first
            workoutCommandAt = now;
            pauseResume.setText("▶ Resuming…");
            send(() -> client.resumeWorkout(), "workout resume");
        }
    }

    private void updateStartStop(boolean moving) {
        if (android.os.SystemClock.elapsedRealtime() - workoutCommandAt < WORKOUT_COMMAND_SETTLE_MS) {
            return; // keep "Starting…"/"Stopping…" until the belt has had time to respond
        }
        boolean active = inWorkout();
        startStop.setText(active ? "■ Stop" : "▶ Start");
        ((GradientDrawable) startStop.getBackground()).setColor(active ? Color.rgb(180, 45, 45) : Color.rgb(40, 140, 75));
        boolean paused = workoutState == WORKOUT_PAUSED;
        pauseResume.setText(paused ? "▶ Resume" : "❚❚ Pause");
        ((GradientDrawable) pauseResume.getBackground()).setColor(paused ? Color.rgb(40, 140, 75) : Color.rgb(200, 140, 30));
        pauseResume.setVisibility(controlsShown && active ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void showControls() {
        controlsShown = true;
        for (TextView t : new TextView[] {startStop, speedDown, speedUp, inclineDown, inclineUp}) {
            t.setVisibility(android.view.View.VISIBLE);
        }
        if (inWorkout()) pauseResume.setVisibility(android.view.View.VISIBLE);
        main.removeCallbacks(autoHideControls);
        main.postDelayed(autoHideControls, CONTROLS_HIDE_MS);
    }

    private void hideControls() {
        controlsShown = false;
        for (TextView t : new TextView[] {startStop, pauseResume, speedDown, speedUp, inclineDown, inclineUp}) {
            t.setVisibility(android.view.View.GONE);
        }
        main.removeCallbacks(autoHideControls);
    }

    private TextView addStat(int color) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setSingleLine(true); // never wrap into a vertical strip when the bar is near the screen edge
        t.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(t);
        return t;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Treadmill bar",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Treadmill bar is running")
                .build();
        startForeground(1, n);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
