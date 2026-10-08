package com.firstham.aethergui;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ownership and bounded shutdown for the child that owns all Core proxy listeners. */
final class CoreProcessGuard {
    static boolean isOwnedExecutable(String executable, java.util.Set<String> expected, String status, int appUid) {
        return expected.contains(executable) && isOwnedCore(executable, executable, status, appUid);
    }
    private static final Pattern UID = Pattern.compile("(?m)^Uid:\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");

    static boolean isOwnedCore(String executable, String expectedExecutable, String status, int appUid) {
        if (expectedExecutable == null || expectedExecutable.isEmpty() || !expectedExecutable.equals(executable)) return false;
        Matcher uid = UID.matcher(status);
        if (!uid.find()) return false;
        try {
            for (int index = 1; index <= 4; index++) if (Integer.parseInt(uid.group(index)) != appUid) return false;
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** Cancellation still forces a stubborn child down; an unconfirmed stop keeps its owner. */
    static boolean stop(Process process, long graceMs) {
        boolean interrupted = false;
        try {
            process.destroy();
            try {
                if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException cancelled) {
                interrupted = true;
            }
            process.destroyForcibly();
            try {
                return process.waitFor(graceMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException cancelled) {
                interrupted = true;
                return !process.isAlive();
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private CoreProcessGuard() { }
}
