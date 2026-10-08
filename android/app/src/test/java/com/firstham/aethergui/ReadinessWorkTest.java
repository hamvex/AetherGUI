package com.firstham.aethergui;

import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class ReadinessWorkTest {
    @Test public void cancellingAProbeActuallyUnblocksSocketIo() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept(); ProbeCancellation probes = new ProbeCancellation()) {
            probes.track(client);
            CountDownLatch reading = new CountDownLatch(1);
            Future<Boolean> finished = worker.submit(() -> {
                reading.countDown();
                try { client.getInputStream().read(); return false; }
                catch (IOException cancelled) { return true; }
            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            probes.close();
            assertTrue("closing the group must unblock a read without waiting for a network timeout",
                    finished.get(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
            assertFalse("the peer belongs to another owner", peer.isClosed());
        } finally { worker.shutdownNow(); }
    }

    @Test public void lateSocketsAndQueuedWorkCannotEscapeCancellation() throws Exception {
        ProbeCancellation probes = new ProbeCancellation();
        FutureTask<Void> queued = new FutureTask<>(() -> null);
        probes.track(queued);
        probes.close(); probes.close();
        assertTrue(queued.isCancelled());
        Socket late = new Socket();
        try { probes.track(late); fail("late socket was accepted"); }
        catch (IOException expected) { assertTrue(late.isClosed()); }
        FutureTask<Void> lateTask = new FutureTask<>(() -> null);
        probes.track(lateTask);
        assertTrue(lateTask.isCancelled());
    }

    @Test public void provingOneProtocolDoesNotCancelTheOtherProtocol() throws Exception {
        try (ProbeCancellation socks = new ProbeCancellation(); ProbeCancellation http = new ProbeCancellation();
             Socket socksSocket = new Socket(); Socket httpSocket = new Socket()) {
            socks.track(socksSocket); http.track(httpSocket);
            socks.close();
            assertTrue(socksSocket.isClosed());
            assertFalse(httpSocket.isClosed());
            ProxyMode.TrafficProof proof = new ProxyMode.TrafficProof(true);
            assertFalse(proof.record(false));
            assertTrue(proof.record(true));
        }
    }

    @Test public void locationTraceCanBeConsumedOnceByItsOwnLiveSession() {
        ReadinessTrace trace = new ReadinessTrace();
        Object child = new Object();
        String iran = "ip=203.0.113.1\nloc=IR\nwarp=on\n";
        trace.remember(child, 4, 100, iran);
        assertEquals(iran, trace.take(child, 4, 101));
        assertNull(trace.take(child, 4, 102));
        trace.remember(child, 4, 100, iran);
        assertNull(trace.take(new Object(), 4, 101));
        trace.remember(child, 4, 100, iran);
        assertNull(trace.take(child, 5, 101));
        trace.remember(child, 4, 100, iran);
        assertNull(trace.take(child, 4, 30_101));
        trace.remember(child, 4, 100, iran);
        assertNull(trace.take(child, 4, 99));
        trace.remember(child, 4, 100, iran);
        trace.clear();
        assertNull(trace.take(child, 4, 101));
    }

    @Test public void everyPrivacyEndpointCanBeCancelledIndependentlyAcrossTenCycles() throws Exception {
        for (int cycle = 0; cycle < 10; cycle++) {
            ProbeCancellation[] probes = new ProbeCancellation[4];
            Socket[] sockets = new Socket[4];
            try {
                for (int index = 0; index < probes.length; index++) {
                    probes[index] = new ProbeCancellation();
                    sockets[index] = new Socket();
                    probes[index].track(sockets[index]);
                }
                probes[0].close();
                assertTrue(sockets[0].isClosed());
                for (int index = 1; index < sockets.length; index++) assertFalse(sockets[index].isClosed());
            } finally {
                for (ProbeCancellation probe : probes) if (probe != null) probe.close();
            }
            for (Socket socket : sockets) assertTrue(socket.isClosed());
        }
    }
}
