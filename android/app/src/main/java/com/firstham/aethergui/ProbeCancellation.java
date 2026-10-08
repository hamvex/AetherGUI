package com.firstham.aethergui;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** Cancels a group of readiness requests, including blocked socket I/O. */
final class ProbeCancellation implements AutoCloseable {
    private final List<Socket> sockets = new ArrayList<>();
    private final List<Future<?>> futures = new ArrayList<>();
    private boolean closed;

    synchronized void track(Socket socket) throws IOException {
        if (closed) {
            socket.close();
            throw new IOException("Readiness probe cancelled");
        }
        sockets.add(socket);
    }

    synchronized void track(Future<?> future) {
        if (closed) future.cancel(true);
        else futures.add(future);
    }

    @Override public void close() {
        List<Socket> toClose;
        List<Future<?>> toCancel;
        synchronized (this) {
            if (closed) return;
            closed = true;
            toClose = new ArrayList<>(sockets);
            toCancel = new ArrayList<>(futures);
            sockets.clear(); futures.clear();
        }
        for (Future<?> future : toCancel) future.cancel(true);
        for (Socket socket : toClose) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
