package hev.htproxy;

/** Exact JNI contract exported by the pinned HEV Socks5 Tunnel 2.16.0 source. */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() { }

    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();
}
