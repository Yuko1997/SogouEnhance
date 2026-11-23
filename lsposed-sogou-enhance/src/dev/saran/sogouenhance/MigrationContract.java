package dev.saran.sogouenhance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class MigrationContract {
    static final String SOURCE_PACKAGE =
            "com.sohu.inputmethod.sogou";
    static final String TARGET_PACKAGE =
            "com.sohu.inputmethod.sogouoem";
    static final String SOURCE_WAKE_ACTIVITY =
            "com.sogou.androidtool.ShortCutProxyActivity";
    static final String TARGET_WAKE_ACTIVITY =
            "com.sogou.androidtool.ShortCutProxyActivity";
    static final String BROKER_AUTHORITY =
            "dev.saran.sogouenhance.migration";
    static final String BROKER_BASE_URI =
            "content://" + BROKER_AUTHORITY + "/";
    static final String ACTION_TRIGGER_SOURCE =
            "dev.saran.sogouenhance.action.TRIGGER_SOURCE";
    static final String ACTION_TRIGGER_TARGET =
            "dev.saran.sogouenhance.action.TRIGGER_TARGET";
    static final int BROKER_SCHEMA_VERSION = 1;

    static final String STATUS_IDLE = "idle";
    static final String STATUS_REQUESTED = "requested";
    static final String STATUS_COLLECTING = "collecting";
    static final String STATUS_READY = "ready";
    static final String STATUS_SUBMITTED = "submitted";
    static final String STATUS_CLAIMED = "claimed";
    static final String STATUS_DONE = "done";
    static final String STATUS_ERROR = "error";

    static final String CALL_CREATE_SOURCE = "create_source";
    static final String CALL_GET_PENDING_SOURCE = "get_pending_source";
    static final String CALL_COMPLETE_SOURCE = "complete_source";
    static final String CALL_FAIL_SOURCE = "fail_source";
    static final String CALL_GET_STATE = "get_state";
    static final String CALL_SUBMIT_DESTINATION = "submit_destination";
    static final String CALL_CLAIM_DESTINATION = "claim_destination";
    static final String CALL_COMPLETE_DESTINATION = "complete_destination";

    static final String EXTRA_STATE_JSON = "state_json";
    static final String EXTRA_MANIFEST_JSON = "manifest_json";
    static final String EXTRA_RESULT_JSON = "result_json";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_OK = "ok";

    static final String UUD_FILE_NAME = "v3new.uud";
    static final String SHORTCUT_FILE_NAME = "basephrases_v2.json";
    static final String[] ENGLISH_FILE_NAMES = {
            "en_un.dat",
            "en_uml.dat",
            "en_ul.dat",
            "en_uel.dat"
    };

    private MigrationContract() {
    }

    static String newRequestId() {
        String timestamp = new SimpleDateFormat(
                "yyyyMMddHHmmss", Locale.US).format(new Date());
        String suffix = UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        return timestamp + "-" + suffix;
    }

    static boolean isValidRequestId(String requestId) {
        return requestId != null
                && requestId.matches("[A-Za-z0-9_-]{8,80}");
    }

    static File migrationRoot(File filesDir) {
        return new File(filesDir, "migration");
    }

    static File brokerRoot(File filesDir) {
        return new File(filesDir, "broker");
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        StringBuilder output = new StringBuilder(64);
        for (byte value : digest.digest()) {
            output.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return output.toString();
    }

    static void writeUtf8(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("Cannot create " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    static boolean isInside(File root, File candidate) {
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String candidatePath = candidate.getCanonicalPath();
            return candidatePath.startsWith(rootPath);
        } catch (IOException ignored) {
            return false;
        }
    }
}
