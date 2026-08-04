package hev.htproxy;

/** JNI contract used by the official HEV Android tun2socks library. */
public final class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }
    private TProxyService() {}
    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
