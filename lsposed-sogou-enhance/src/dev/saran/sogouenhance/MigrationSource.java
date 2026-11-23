package dev.saran.sogouenhance;

import android.app.Application;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class MigrationSource {
    private static final long CHECK_THROTTLE_MILLIS = 2_000L;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "SogouEnhance-SourceExport");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean CHECK_IN_FLIGHT =
            new AtomicBoolean(false);
    private static final AtomicLong LAST_CHECK =
            new AtomicLong(0L);

    private MigrationSource() {
    }

    static void onEngineReady(
            MainHook owner, ClassLoader classLoader) {
        enqueue(owner, classLoader, false);
    }

    static void triggerNow(
            MainHook owner, ClassLoader classLoader) {
        enqueue(owner, classLoader, true);
    }

    private static void enqueue(
            MainHook owner, ClassLoader classLoader,
            boolean force) {
        long now = System.currentTimeMillis();
        long previous = LAST_CHECK.get();
        if ((!force && now - previous < CHECK_THROTTLE_MILLIS)
                || !LAST_CHECK.compareAndSet(previous, now)
                || !CHECK_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                exportIfRequested(owner, classLoader);
            } catch (Throwable t) {
                owner.logRuntime(Log.ERROR,
                        "official dictionary export failed", t);
            } finally {
                CHECK_IN_FLIGHT.set(false);
            }
        });
    }

    private static void exportIfRequested(
            MainHook owner, ClassLoader classLoader) throws Exception {
        Application context = currentApplication();
        if (context == null) {
            return;
        }
        JSONObject state = MigrationBrokerClient.getPendingSource(
                context);
        if (state == null) {
            return;
        }
        String requestId = state.optString("requestId", "");
        if (!MigrationContract.isValidRequestId(requestId)) {
            return;
        }

        owner.logRuntime(Log.INFO,
                "official dictionary export requested: " + requestId);
        try {
            forceOfficialUudExport(context, classLoader);
            JSONObject manifest = buildManifest(
                    context, requestId);
            uploadManifestFiles(
                    context, requestId, manifest);
            MigrationBrokerClient.completeSource(
                    context, requestId, manifest);
            owner.logRuntime(Log.INFO,
                    "official dictionary export ready: " + requestId);
        } catch (Throwable t) {
            MigrationBrokerClient.failSource(
                    context, requestId, messageOf(t));
            throw t;
        }
    }

    private static void forceOfficialUudExport(
            Application context, ClassLoader classLoader)
            throws Exception {
        Class<?> syncUtilClass = Class.forName(
                "pa1", true, classLoader);
        Constructor<?> constructor =
                syncUtilClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object syncUtil = constructor.newInstance();

        Method exportMethod = null;
        for (Method method : syncUtilClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if ("Lq".equals(method.getName())
                    && params.length == 2
                    && params[0].isAssignableFrom(
                    context.getClass())
                    && Bundle.class.isAssignableFrom(params[1])) {
                exportMethod = method;
                break;
            }
        }
        if (exportMethod == null) {
            throw new NoSuchMethodException("pa1.Lq(Context, Bundle)");
        }
        exportMethod.setAccessible(true);
        Bundle bundle = new Bundle();
        bundle.putBoolean("upload_user_dict_check_md5", false);
        bundle.putBoolean("upload_user_dict_need_merge", false);
        Object result = exportMethod.invoke(
                syncUtil, context, bundle);
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException(
                    "官方版 UUD 导出接口返回 false");
        }
    }

    private static JSONObject buildManifest(
            Application context, String requestId)
            throws Exception {
        JSONObject manifest = new JSONObject()
                .put("schemaVersion",
                        MigrationContract.BROKER_SCHEMA_VERSION)
                .put("requestId", requestId)
                .put("sourcePackage",
                        MigrationContract.SOURCE_PACKAGE)
                .put("createdAt", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                        .format(new Date()));

        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(
                            MigrationContract.SOURCE_PACKAGE, 0);
            manifest.put("sourceVersion", info.versionName)
                    .put("sourceVersionCode", info.getLongVersionCode());
        } catch (Throwable ignored) {
            // Version metadata is diagnostic only.
        }

        File filesDir = context.getFilesDir();
        File uud = new File(filesDir,
                "dict/" + MigrationContract.UUD_FILE_NAME);
        if (!uud.isFile()) {
            throw new IllegalStateException(
                    "官方版 v3new.uud 不存在");
        }
        MigrationFileInspector.UudInfo uudInfo =
                MigrationFileInspector.inspectUud(uud);
        if (uudInfo.records <= 0) {
            throw new IllegalStateException(
                    "官方版 v3new.uud 没有可迁移词条");
        }
        manifest.put("uud", new JSONObject()
                .put("fileName", MigrationContract.UUD_FILE_NAME)
                .put("size", uudInfo.size)
                .put("sha256", uudInfo.sha256)
                .put("records", uudInfo.records));

        File shortcut = new File(filesDir,
                "shortcutphrases/"
                        + MigrationContract.SHORTCUT_FILE_NAME);
        if (shortcut.isFile()) {
            manifest.put("shortcut", new JSONObject()
                    .put("fileName",
                            MigrationContract.SHORTCUT_FILE_NAME)
                    .put("size", shortcut.length())
                    .put("sha256",
                            MigrationContract.sha256(shortcut))
                    .put("validCount",
                            MigrationFileInspector
                                    .countVisibleShortcuts(shortcut)));
        }

        JSONArray english = new JSONArray();
        File englishDir = new File(filesDir,
                "foreign/user_dict/1");
        for (String name : MigrationContract.ENGLISH_FILE_NAMES) {
            File file = new File(englishDir, name);
            if (!file.isFile()) {
                continue;
            }
            english.put(new JSONObject()
                    .put("fileName", name)
                    .put("size", file.length())
                    .put("sha256",
                            MigrationContract.sha256(file)));
        }
        if (english.length() > 0) {
            manifest.put("english", english);
        }
        return manifest;
    }

    private static void uploadManifestFiles(
            Application context, String requestId,
            JSONObject manifest) throws Exception {
        File filesDir = context.getFilesDir();
        JSONObject uud = manifest.optJSONObject("uud");
        if (uud != null) {
            uploadFile(context, requestId,
                    new File(filesDir, "dict/"
                            + MigrationContract.UUD_FILE_NAME),
                    MigrationContract.UUD_FILE_NAME);
        }
        JSONObject shortcut =
                manifest.optJSONObject("shortcut");
        if (shortcut != null) {
            uploadFile(context, requestId,
                    new File(filesDir, "shortcutphrases/"
                            + MigrationContract.SHORTCUT_FILE_NAME),
                    MigrationContract.SHORTCUT_FILE_NAME);
        }
        JSONArray english = manifest.optJSONArray("english");
        if (english != null) {
            for (int i = 0; i < english.length(); i++) {
                String name = english.getJSONObject(i)
                        .getString("fileName");
                uploadFile(context, requestId,
                        new File(filesDir,
                                "foreign/user_dict/1/" + name),
                        name);
            }
        }
    }

    private static void uploadFile(
            Application context, String requestId,
            File source, String fileName) throws Exception {
        Uri destination = MigrationBrokerClient.sourceFileUri(
                requestId, fileName);
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(
                     destination, "w")) {
            if (output == null) {
                throw new IllegalStateException(
                        "无法打开中转文件: " + fileName);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            if (output instanceof FileOutputStream) {
                ((FileOutputStream) output).getFD().sync();
            }
        }
    }

    private static Application currentApplication()
            throws Exception {
        Class<?> activityThread = Class.forName(
                "android.app.ActivityThread");
        Method currentApplication = activityThread.getDeclaredMethod(
                "currentApplication");
        currentApplication.setAccessible(true);
        Object value = currentApplication.invoke(null);
        return value instanceof Application
                ? (Application) value : null;
    }

    private static String messageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
