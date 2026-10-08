package com.firstham.aethergui;

/** One-use location hint from a verified HTTPS response, scoped to one child and session. */
final class ReadinessTrace {
    private static final long MAX_AGE_MS = 30_000L;
    private Object owner;
    private long session;
    private long receivedAt;
    private String body;

    synchronized void remember(Object owner, long session, long receivedAt, String body) {
        this.owner = owner; this.session = session; this.receivedAt = receivedAt; this.body = body;
    }

    synchronized String take(Object expectedOwner, long expectedSession, long now) {
        String result = owner != null && owner == expectedOwner && session == expectedSession
                && now >= receivedAt && now - receivedAt <= MAX_AGE_MS ? body : null;
        clear();
        return result;
    }

    synchronized void clear() { owner = null; body = null; }
}
