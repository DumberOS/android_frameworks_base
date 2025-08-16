package android.security.keystore2;

import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

import android.annotation.FlaggedApi;

//@FlaggedApi("")
@SuppressWarnings("MissingNullability")
/**
 * Blabla
 * @hide
 */
public final class UtilKt {
    private static final String TAG = "UtilKt";

    // lazy bootHash
    private static volatile byte[] bootHash;
    /**
     * @hide
     */
    public static byte[] getBootHash() {
        if (bootHash == null) {
            synchronized (UtilKt.class) {
                if (bootHash == null) {
                    byte[] fromProp = getBootHashFromProp();
                    bootHash = fromProp != null ? fromProp : randomBytes();
                }
            }
        }
        return bootHash;
    }

    // lazy bootKey (TODO: verified boot keys)
    private static volatile byte[] bootKey;
    /**
     * @hide
     */
    public static byte[] getBootKey() {
        if (bootKey == null) {
            synchronized (UtilKt.class) {
                if (bootKey == null) {
                    bootKey = randomBytes();
                }
            }
        }
        return bootKey;
    }

    // lazy patchLevel and patchLevelLong
    private static volatile int patchLevel = -1;
    /**
     * @hide
     */
    public static int getPatchLevel() {
        if (patchLevel == -1) {
            synchronized (UtilKt.class) {
                if (patchLevel == -1) {
                    patchLevel = convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
                }
            }
        }
        return patchLevel;
    }

    private static volatile int patchLevelLong = -1;
    /**
     * @hide
     */
    public static int getPatchLevelLong() {
        if (patchLevelLong == -1) {
            synchronized (UtilKt.class) {
                if (patchLevelLong == -1) {
                    patchLevelLong = convertPatchLevel(Build.VERSION.SECURITY_PATCH, true);
                }
            }
        }
        return patchLevelLong;
    }

    // lazy osVersion
    private static volatile int osVersion = -1;
    /**
     * @hide
     */
    public static int getOsVersion() {
        if (osVersion == -1) {
            synchronized (UtilKt.class) {
                if (osVersion == -1) {
                    int sdk = Build.VERSION.SDK_INT;
                    if (sdk == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        osVersion = 140000;
                    } else if (sdk == Build.VERSION_CODES.TIRAMISU) {
                        osVersion = 130000;
                    } else if (sdk == Build.VERSION_CODES.S_V2) {
                        osVersion = 120100;
                    } else if (sdk == Build.VERSION_CODES.S) {
                        osVersion = 120000;
                    } else {
                        osVersion = 0;
                    }
                }
            }
        }
        return osVersion;
    }

    // getTransactCode equivalent
    /**
     * @hide
     */
    public static int getTransactCode(Class<?> clazz, String method) {
        try {
            String fieldName = "TRANSACTION_" + method;
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to get transact code for method: " + method, e);
        }
    }

    // getBootHashFromProp
    /**
     * @hide
     */
    public static byte[] getBootHashFromProp() {
        String b = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (b == null) return null;
        if (b.length() != 64) return null;
        try {
            return hexToByteArray(b);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "invalid boot hash hex string: " + b, e);
            return null;
        }
    }

    // randomBytes
    /**
     * @hide
     */
    public static byte[] randomBytes() {
        byte[] b = new byte[32];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }

    // convertPatchLevel implementation
    /**
     * @hide
     */
    public static int convertPatchLevel(String patch, boolean longVersion) {
        if (patch == null) {
            return 202404;
        }
        try {
            String[] parts = patch.split("-");
            if (longVersion) {
                // expecting at least 3 parts
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                int c = Integer.parseInt(parts[2]);
                return a * 10000 + b * 100 + c;
            } else {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                return a * 100 + b;
            }
        } catch (Exception e) {
            Log.e(TAG, "invalid patch level " + patch + " !", e);
            return 202404;
        }
    }

    // getPackageInfoCompat
    static PackageInfo getPackageInfoCompat(IPackageManager pm, String name, long flags, int userId) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Signature: getPackageInfo(String, long, int)
            return pm.getPackageInfo(name, flags, userId);
        } else {
            // older: flags is int
            return pm.getPackageInfo(name, (int) flags, userId);
        }
    }

    // trimLine
    /**
     * @hide
     */
    public static String trimLine(String s) {
        if (s == null) return null;
        String[] lines = s.trim().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].trim());
            if (i != lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // helper: hex string to byte array
    private static byte[] hexToByteArray(String s) {
        int len = s.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException("Invalid hex character in: " + s);
            }
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }

    // Private constructor to prevent instantiation
    private UtilKt() { }
}

