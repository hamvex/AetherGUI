package com.firstham.aethergui;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class CoreProcessGuardTest {
    private static final String EXE = "/data/app/aethon/lib/arm64/libaether.so";
    private static final String STATUS = "Name:\taether\nUid:\t10123\t10123\t10123\t10123\n";

    @Test public void orphanCleanupRequiresExactExecutableAndAllUidFields() {
        assertTrue(CoreProcessGuard.isOwnedCore(EXE, EXE, STATUS, 10123));
        assertFalse(CoreProcessGuard.isOwnedCore("/other/libaether.so", EXE, STATUS, 10123));
        assertFalse(CoreProcessGuard.isOwnedCore(EXE + " --other", EXE, STATUS, 10123));
        assertFalse(CoreProcessGuard.isOwnedCore(EXE, EXE, STATUS, 10124));
        assertFalse(CoreProcessGuard.isOwnedCore(EXE, EXE, STATUS.replaceFirst("10123", "0"), 10123));
        assertFalse(CoreProcessGuard.isOwnedCore(EXE, EXE, "", 10123));
        assertFalse(CoreProcessGuard.isOwnedCore("", "", STATUS, 10123));
    }

    @Test public void gracefulShutdownDoesNotForceKill() {
        Child child = new Child(true, true, false);
        assertTrue(CoreProcessGuard.stop(child, 750));
        assertEquals(1, child.destroyCalls);
        assertEquals(0, child.forceCalls);
        assertFalse(child.isAlive());
    }

    @Test public void helperCleanupRequiresExactPrivateExecutableAndUid() {
        String helper = "/data/app/aethon/lib/arm64/liblyrebird.so";
        java.util.Set<String> expected = new java.util.HashSet<>(java.util.Arrays.asList(EXE, helper));
        assertTrue(CoreProcessGuard.isOwnedExecutable(helper, expected, STATUS, 10123));
        assertFalse(CoreProcessGuard.isOwnedExecutable("/other/liblyrebird.so", expected, STATUS, 10123));
        assertFalse(CoreProcessGuard.isOwnedExecutable(helper, expected, STATUS, 10124));
        assertFalse(CoreProcessGuard.isOwnedExecutable(helper + " --child", expected, STATUS, 10123));
    }

    @Test public void unresponsiveChildIsForcedDownBeforeOwnershipIsReleased() {
        Child child = new Child(false, true, false);
        assertTrue(CoreProcessGuard.stop(child, 750));
        assertEquals(1, child.forceCalls);
        assertEquals(2, child.waitCalls);
        assertFalse(child.isAlive());
    }

    @Test public void aStillLiveChildIsNotReportedStopped() {
        Child child = new Child(false, false, false);
        assertFalse(CoreProcessGuard.stop(child, 750));
        assertTrue(child.isAlive());
        assertEquals(1, child.forceCalls);
    }

    @Test public void interruptedShutdownStillForcesTheChildAndRestoresCancellation() {
        Child child = new Child(false, true, true);
        try {
            assertTrue(CoreProcessGuard.stop(child, 750));
            assertEquals(1, child.forceCalls);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(child.isAlive());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class Child extends Process {
        final boolean graceful, forceStops, interrupt;
        boolean alive = true;
        int destroyCalls, forceCalls, waitCalls;
        Child(boolean graceful, boolean forceStops, boolean interrupt) {
            this.graceful = graceful; this.forceStops = forceStops; this.interrupt = interrupt;
        }
        @Override public void destroy() { destroyCalls++; if (graceful) alive = false; }
        @Override public Process destroyForcibly() { forceCalls++; if (forceStops) alive = false; return this; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            assertEquals(750, unit.toMillis(timeout));
            waitCalls++;
            if (interrupt && waitCalls == 1) throw new InterruptedException("cancelled");
            return !alive;
        }
        @Override public int waitFor() { return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    }
}
