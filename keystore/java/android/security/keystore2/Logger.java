package android.security.keystore2;

/**
 * @hide
 */
@SuppressWarnings("MissingNullability")
public class Logger {
    private Logger() {}
    private static final String TAG = "TrickyStore";
    static void d(String msg) {
	java.util.logging.Logger.global.info("Dumbdroid Cert: " + msg + "");
    }

    static void e(String msg) {
        d(msg);
    }

    static void e(String msg, Throwable t) {
        d(msg + " " + t.getMessage());
    }

    static void i(String msg) {
        d(msg);
    }

}
