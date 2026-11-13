import com.treadmill.home.IfitLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Plain-Java checks for IfitLog (no Android, no test framework). Run with app\test\run-tests.ps1.
 * An optional argument is a captured `adb logcat -v time` file to replay as a smoke test.
 */
public class IfitLogTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        parsesSpeedGradeAndState();
        ignoresOtherLines();
        warmUpAndRunningCountAsRunning();
        idleAndResultsCountAsNoWorkout();
        movingBeltIsRunningEvenBeforeAStateLine();
        goesStaleWhenRunningAndSilent();
        stoppedIsNeverStale();
        countsStartKeyPresses();
        workoutStateIsRunning();
        workoutResultsIsNoWorkout();
        tracksScreenFromLog();
        tracksScreenFromForegroundClass();
        tracksDialogs();
        readyOnlyOnDashboardWhenIdle();
        readyOnAnyScreenDuringAWorkout();
        neverReadyWithADialog();
        unknownScreenCountsAsReady();
        if (args.length > 0) replay(args[0]);
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all passed");
    }

    // Lines copied from a NordicTrack Commercial 1750 (iFit standalone 2.6.88), Nov 13, 2026.
    static final String KPH = "11-13 19:26:14.433 V/mono-stdout(29446): [34] [Trace:FitPro] Changed KPH to: 1.77";
    static final String GRADE = "11-13 19:26:17.129 V/mono-stdout(29446): [36] [Trace:FitPro] Changed Grade to: 0.5";
    static final String GRADE_NEG = "11-13 19:26:30.000 V/mono-stdout(29446): [36] [Trace:FitPro] Changed Grade to: -1";
    static final String WARMUP = "11-13 19:25:20.000 V/mono-stdout(29446): [5] [Trace:FitPro] Changed state from: Idle to: WarmUp";
    static final String PAUSED = "11-13 19:27:00.986 V/mono-stdout(29446): [5] [Trace:FitPro] Changed state from: WarmUp to: Paused";
    static final String TICK = "11-13 19:26:32.722 V/mono-stdout(29446): [5] [Trace:FitPro] Changed CurrentTime to: 106";
    static final String CONSOLE_KPH = "11-13 19:26:14.846 V/mono-stdout(29446): [10] [Trace:FitnessConsole] Kph changed from 1.6 to 1.77";

    static void parsesSpeedGradeAndState() {
        IfitLog log = new IfitLog();
        log.accept(KPH, 1000);
        log.accept(GRADE, 1000);
        log.accept(WARMUP, 1000);
        eq("kph", 1.77, log.kph());
        eq("grade", 0.5, log.grade());
        eq("state", "WarmUp", log.state());
        log.accept(GRADE_NEG, 1000);
        eq("negative grade", -1.0, log.grade());
    }

    static void ignoresOtherLines() {
        IfitLog log = new IfitLog();
        log.accept(KPH, 1000);
        log.accept(CONSOLE_KPH.replace("1.77", "9.9"), 1000); // second report of the same change
        log.accept("11-13 19:25:35.241 I/system_server(534): NativeAlloc concurrent copying GC", 1000);
        log.accept("", 1000);
        log.accept("Changed KPH to: not-a-number", 1000);
        eq("kph unchanged", 1.77, log.kph());
    }

    static void warmUpAndRunningCountAsRunning() {
        for (String s : new String[] {"WarmUp", "Running", "CoolDown"}) {
            IfitLog log = new IfitLog();
            log.accept(stateLine("Idle", s), 1000);
            eq(s + " mode", IfitLog.Mode.RUNNING, log.mode(1000));
        }
        IfitLog log = new IfitLog();
        log.accept(PAUSED, 1000);
        eq("Paused mode", IfitLog.Mode.PAUSED, log.mode(1000));
    }

    static void idleAndResultsCountAsNoWorkout() {
        for (String s : new String[] {"Idle", "Results", "Finished", "Summary", "Done"}) {
            IfitLog log = new IfitLog();
            log.accept(stateLine("Paused", s), 1000);
            eq(s + " mode", IfitLog.Mode.IDLE, log.mode(1000));
        }
        eq("no lines yet", IfitLog.Mode.IDLE, new IfitLog().mode(1000));
    }

    static void movingBeltIsRunningEvenBeforeAStateLine() {
        IfitLog log = new IfitLog();
        log.accept(KPH, 1000); // e.g. the app started mid-workout and only saw a speed change
        eq("mode from speed", IfitLog.Mode.RUNNING, log.mode(1000));
    }

    static void goesStaleWhenRunningAndSilent() {
        IfitLog log = new IfitLog();
        log.accept(WARMUP, 1000);
        log.accept(KPH, 1000);
        log.accept(TICK, 5000);
        eq("fresh 9 s after a tick", false, log.stale(5000 + IfitLog.STALE_MS - 1000));
        eq("stale after silence", true, log.stale(5000 + IfitLog.STALE_MS + 1));
    }

    static void stoppedIsNeverStale() {
        IfitLog log = new IfitLog();
        log.accept(WARMUP, 1000);
        log.accept(PAUSED, 2000);
        log.accept(KPH.replace("1.77", "0"), 2000);
        eq("paused, silent for a minute", false, log.stale(62_000));
    }

    static final String START_KEY = "11-13 19:43:48.547 V/mono-stdout(29446): [40] [Trace:FitPro] KeyCode observed: "
            + "[KeyObj Code:Start, RawKeyCode:18446742978492891135, TimePressed:0, TimeHeld:130]";
    static final String NO_KEY = "11-13 19:26:20.000 V/mono-stdout(29446): [6] [Trace:FitPro] KeyCode observed: "
            + "[KeyObj Code:NoKey, RawKeyCode:0, TimePressed:0, TimeHeld:0]";
    static final String STOP_KEY = "11-13 19:45:02.814 V/mono-stdout(29446): [10] [Trace:FitPro] KeyCode observed: "
            + "[KeyObj Code:Stop, RawKeyCode:18446742978492235775, TimePressed:28, TimeHeld:40]";

    static void countsStartKeyPresses() {
        IfitLog log = new IfitLog();
        eq("no presses yet", 0, log.startPresses());
        log.accept(START_KEY, 1000);
        eq("one press", 1, log.startPresses());
        log.accept(START_KEY, 1200); // the same press reported twice
        eq("repeat within a second ignored", 1, log.startPresses());
        log.accept(NO_KEY, 2000);
        log.accept(STOP_KEY, 2500);
        eq("other keys ignored", 1, log.startPresses());
        log.accept(START_KEY, 5000);
        eq("second press", 2, log.startPresses());
    }

    static void workoutStateIsRunning() {
        IfitLog log = new IfitLog(); // seen on the 1750: Idle -> WarmUp -> Workout -> Paused
        log.accept(stateLine("WarmUp", "Workout"), 1000);
        eq("Workout mode", IfitLog.Mode.RUNNING, log.mode(1000));
    }

    static void workoutResultsIsNoWorkout() {
        IfitLog log = new IfitLog(); // seen on the 1750 after a second Stop: Paused -> WorkoutResults -> Idle
        log.accept(stateLine("Paused", "WorkoutResults"), 1000);
        eq("WorkoutResults mode", IfitLog.Mode.IDLE, log.mode(1000));
    }

    static String resumed(String view) {
        return "11-13 20:56:29.258 V/mono-stdout( 3228): [1] [Trace:MainApplication] " + view + " is being resumed";
    }

    static final String DIALOG_ON = "11-13 19:53:18.522 V/mono-stdout(29446): [1] [Trace:Navigation] Setting dialog "
            + "visibility to: True for: Wolf.Android.Views.Dialogs.SimpleConfirmation.SimpleConfirmationDialog";
    static final String DIALOG_OFF = DIALOG_ON.replace("to: True", "to: False");

    static void tracksScreenFromLog() {
        IfitLog log = new IfitLog();
        eq("no screen yet", "", log.screen());
        log.accept(resumed("EmbeddedNoWifiDashboardView"), 1000);
        eq("screen", "EmbeddedNoWifiDashboardView", log.screen());
        log.accept("11-13 20:56:28.834 V/mono-stdout( 3228): [1] [Trace:MainApplication] "
                + "EmbeddedNoWifiDashboardView is being paused", 1000);
        eq("pause doesn't change it", "EmbeddedNoWifiDashboardView", log.screen());
    }

    static void tracksScreenFromForegroundClass() {
        IfitLog log = new IfitLog();
        log.noteScreen("crc6421f033f4f5053777.EmbeddedOnboardingWebView");
        eq("simple name", "EmbeddedOnboardingWebView", log.screen());
        log.noteScreen("DashboardView");
        eq("already simple", "DashboardView", log.screen());
    }

    static void tracksDialogs() {
        IfitLog log = new IfitLog();
        log.accept(resumed("InWorkoutView"), 1000);
        log.accept(DIALOG_ON, 1000);
        eq("dialog up", true, log.dialogShown());
        log.accept(DIALOG_OFF, 2000);
        eq("dialog closed", false, log.dialogShown());
        log.accept(DIALOG_ON, 3000);
        log.accept(resumed("DashboardView"), 4000);
        eq("new screen clears it", false, log.dialogShown());
    }

    static void readyOnlyOnDashboardWhenIdle() {
        for (String view : new String[] {"DashboardView", "EmbeddedNoWifiDashboardView"}) {
            IfitLog log = new IfitLog();
            log.accept(resumed(view), 1000);
            eq(view + " ready", true, log.ready(1000));
        }
        for (String view : new String[] {"InWorkoutView", "EmbeddedOnboardingWebView", "PermissionsActivity",
                "SplashScreen", "WorkoutResultsView"}) {
            IfitLog log = new IfitLog();
            log.accept(resumed(view), 1000);
            eq(view + " not ready when idle", false, log.ready(1000));
        }
    }

    static void readyOnAnyScreenDuringAWorkout() {
        IfitLog log = new IfitLog(); // Start resumes a paused workout from anywhere
        log.accept(resumed("InWorkoutView"), 1000);
        log.accept(PAUSED, 1000);
        eq("paused on InWorkoutView", true, log.ready(1000));
        log.accept(stateLine("Paused", "Workout"), 2000);
        eq("running", true, log.ready(2000));
    }

    static void neverReadyWithADialog() {
        IfitLog log = new IfitLog();
        log.accept(resumed("DashboardView"), 1000);
        log.accept(DIALOG_ON, 1000);
        eq("dashboard with dialog", false, log.ready(1000));
    }

    static void unknownScreenCountsAsReady() {
        eq("nothing seen yet", true, new IfitLog().ready(1000)); // don't hide our home over a guess
    }

    /** Replays a real capture end to end and prints what the bar would end up showing. */
    static void replay(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.ISO_8859_1);
        IfitLog log = new IfitLog();
        long t = 0;
        int speedChanges = 0;
        double lastKph = log.kph();
        for (String line : lines) {
            log.accept(line, t += 10);
            if (log.kph() != lastKph) {
                speedChanges++;
                lastKph = log.kph();
            }
        }
        System.out.printf("replay %s: %d lines, %d speed changes, final kph=%.2f grade=%.1f state=%s mode=%s%n",
                Paths.get(path).getFileName(), lines.size(), speedChanges, log.kph(), log.grade(), log.state(),
                log.mode(t));
        eq("replay ends stopped", 0.0, log.kph());
        eq("replay ends paused", IfitLog.Mode.PAUSED, log.mode(t));
    }

    static String stateLine(String from, String to) {
        return "11-13 19:25:20.000 V/mono-stdout(29446): [5] [Trace:FitPro] Changed state from: " + from + " to: " + to;
    }

    static void eq(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }
}
