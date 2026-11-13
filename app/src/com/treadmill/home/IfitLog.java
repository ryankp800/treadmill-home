package com.treadmill.home;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Belt state read from iFit's own log lines, for consoles without GlassOS (e.g. a NordicTrack
 * Commercial 1750 on iFit "standalone" 2.6.x). There, the iFit app itself drives the motor board
 * over USB and writes every change to logcat under the tag mono-stdout, for example:
 *
 *   [Trace:FitPro] Changed KPH to: 1.77
 *   [Trace:FitPro] Changed Grade to: 0.5
 *   [Trace:FitPro] Changed state from: Idle to: WarmUp
 *   [Trace:FitPro] Changed CurrentTime to: 106      (about once a second while a workout runs)
 *
 * Read-only: nothing here can change the belt. Plain Java (no Android types) so it can be tested
 * on the PC with app\test\run-tests.ps1. Not thread-safe; the reader thread owns it.
 */
public final class IfitLog {

    public enum Mode { IDLE, RUNNING, PAUSED }

    /** While running, iFit logs CurrentTime every second; this much silence means we lost it. */
    public static final long STALE_MS = 10_000;

    // Only FitPro lines: FitnessConsole repeats the same changes ("Kph changed from a to b").
    private static final Pattern FITPRO = Pattern.compile("\\[Trace:FitPro\\] (.*)$");
    private static final Pattern KPH = Pattern.compile("^Changed KPH to: (-?[0-9.]+)\\s*$");
    private static final Pattern GRADE = Pattern.compile("^Changed Grade to: (-?[0-9.]+)\\s*$");
    private static final Pattern STATE = Pattern.compile("^Changed state from: (\\w+) to: (\\w+)\\s*$");
    private static final Pattern START_KEY = Pattern.compile("^KeyCode observed: \\[KeyObj Code:Start[,\\]]");
    /** One physical press can be reported more than once; presses this close together count once. */
    static final long KEY_REPEAT_MS = 1000;

    private double kph;
    private double grade;
    private String state = "";
    private long lastFitProAt = -1;
    private int startPresses;
    private long lastStartAt = Long.MIN_VALUE / 2;

    /** Feeds one logcat line, seen at {@code nowMs} (any monotonic clock). */
    public void accept(String line, long nowMs) {
        if (line == null) return;
        Matcher fitPro = FITPRO.matcher(line);
        if (!fitPro.find()) return;
        lastFitProAt = nowMs;
        String msg = fitPro.group(1);
        try {
            Matcher m;
            if ((m = KPH.matcher(msg)).matches()) {
                kph = Math.max(0, Double.parseDouble(m.group(1)));
            } else if ((m = GRADE.matcher(msg)).matches()) {
                grade = Double.parseDouble(m.group(1));
            } else if ((m = STATE.matcher(msg)).matches()) {
                state = m.group(2);
            } else if (START_KEY.matcher(msg).find()) {
                if (nowMs - lastStartAt >= KEY_REPEAT_MS) startPresses++;
                lastStartAt = nowMs;
            }
        } catch (NumberFormatException ignored) {
            // A malformed number: keep the last good value.
        }
    }

    public double kph() {
        return kph;
    }

    public double grade() {
        return grade;
    }

    /**
     * How many times the console's Start key has been pressed. iFit logs every press, but only acts
     * on one while its own dashboard is on screen (or to resume a paused workout); compare with an
     * earlier count to spot a press that iFit ignored.
     */
    public int startPresses() {
        return startPresses;
    }

    /** iFit's last workout state name, e.g. "WarmUp" or "Paused"; empty until one is seen. */
    public String state() {
        return state;
    }

    public Mode mode(long nowMs) {
        switch (state) {
            case "Paused":
                return Mode.PAUSED;
            case "":
                return kph > 0 ? Mode.RUNNING : Mode.IDLE; // started mid-workout: go by the belt
            case "Idle":
            case "Results":
            case "Finished":
            case "Summary":
            case "Done":
                return Mode.IDLE;
            default: // WarmUp, Running, CoolDown and anything else iFit calls an active workout
                return Mode.RUNNING;
        }
    }

    /** True when a workout is running but iFit has gone quiet, so the numbers can't be trusted. */
    public boolean stale(long nowMs) {
        return mode(nowMs) == Mode.RUNNING && kph > 0
                && (lastFitProAt < 0 || nowMs - lastFitProAt > STALE_MS);
    }
}
