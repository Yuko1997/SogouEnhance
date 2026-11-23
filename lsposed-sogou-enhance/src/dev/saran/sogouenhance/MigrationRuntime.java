package dev.saran.sogouenhance;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class MigrationRuntime {
    private static final int MERGE_JOB_ID = 0x0C;
    private static final long WORKER_WAIT_MILLIS = 20_000L;
    private static final long CHECK_THROTTLE_MILLIS = 2_000L;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "SogouEnhance-Migration");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean CHECK_IN_FLIGHT =
            new AtomicBoolean(false);
    private static final AtomicLong LAST_CHECK =
            new AtomicLong(0L);

    private MigrationRuntime() {
    }

    static void onEngineReady(
            MainHook owner, ClassLoader targetClassLoader) {
        enqueue(owner, targetClassLoader, false);
    }

    static void triggerNow(
            MainHook owner, ClassLoader targetClassLoader) {
        enqueue(owner, targetClassLoader, true);
    }

    private static void enqueue(
            MainHook owner, ClassLoader targetClassLoader,
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
                processBrokerTask(owner, targetClassLoader);
            } catch (Throwable t) {
                owner.logRuntime(Log.ERROR,
                        "migration runner failed", t);
            } finally {
                CHECK_IN_FLIGHT.set(false);
            }
        });
    }

    private static void processBrokerTask(
            MainHook owner, ClassLoader classLoader) throws Exception {
        Application context = currentApplication();
        if (context == null) {
            return;
        }
        JSONObject state;
        try {
            state = MigrationBrokerClient.claimDestination(context);
        } catch (Throwable unavailable) {
            if (isBrokerUnavailable(unavailable)) {
                return;
            }
            if (unavailable instanceof Exception) {
                throw (Exception) unavailable;
            }
            throw new RuntimeException(unavailable);
        }
        if (state == null
                || !MigrationContract.STATUS_CLAIMED.equals(
                state.optString("status", ""))) {
            return;
        }

        String requestId = state.optString("requestId", "");
        JSONObject manifest = state.optJSONObject("manifest");
        if (!MigrationContract.isValidRequestId(requestId)
                || manifest == null) {
            return;
        }

        owner.logRuntime(Log.INFO,
                "migration task claimed: " + requestId);
        File stage = new File(MigrationContract.migrationRoot(
                context.getFilesDir()), "incoming/" + requestId);
        try {
            downloadManifestFiles(
                    context, requestId, manifest, stage);
            JSONObject result = executeTask(
                    context, classLoader, requestId, manifest, stage);
            MigrationBrokerClient.completeDestination(context, result);
            owner.logRuntime(Log.INFO,
                    "migration task result: " + requestId
                            + " status=" + result.optString("status"));
        } catch (Throwable t) {
            JSONObject result = baseResult(
                    requestId, "error", messageOf(t));
            try {
                MigrationBrokerClient.completeDestination(
                        context, result);
            } catch (Throwable writeFailure) {
                owner.logRuntime(Log.ERROR,
                        "cannot publish migration failure",
                        writeFailure);
            }
            owner.logRuntime(Log.ERROR,
                    "migration task failed: " + requestId, t);
        } finally {
            deleteTree(stage);
        }
    }

    private static void downloadManifestFiles(
            Context context, String requestId,
            JSONObject manifest, File stage) throws Exception {
        if (!stage.exists() && !stage.mkdirs()
                && !stage.isDirectory()) {
            throw new IOException("无法创建迁移暂存目录");
        }

        JSONObject uud = manifest.optJSONObject("uud");
        JSONObject shortcut = manifest.optJSONObject("shortcut");
        JSONArray english = manifest.optJSONArray("english");
        if (uud == null && shortcut == null
                && (english == null || english.length() == 0)) {
            throw new IllegalStateException("没有可迁移数据");
        }
        if (uud != null) {
            downloadFile(context, requestId, uud, stage,
                    MigrationContract.UUD_FILE_NAME);
        }
        if (shortcut != null) {
            downloadFile(context, requestId, shortcut, stage,
                    MigrationContract.SHORTCUT_FILE_NAME);
        }
        if (english != null) {
            for (int i = 0; i < english.length(); i++) {
                JSONObject spec = english.getJSONObject(i);
                String name = spec.optString("fileName", "");
                if (!MigrationFileInspector
                        .isAllowedEnglishName(name)) {
                    throw new IOException("英文词库文件名无效");
                }
                downloadFile(context, requestId, spec, stage, name);
            }
        }
    }

    private static void downloadFile(
            Context context, String requestId,
            JSONObject spec, File stage,
            String expectedName) throws Exception {
        Uri source = MigrationBrokerClient.sourceFileUri(
                requestId, expectedName);
        File destination = new File(stage, expectedName);
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output =
                     new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException(
                        "无法读取中转文件: " + expectedName);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (destination.length() != spec.optLong("size", -1)
                || !spec.optString("sha256", "")
                .equalsIgnoreCase(
                        MigrationContract.sha256(destination))) {
            destination.delete();
            throw new IOException(
                    "中转文件校验失败: " + expectedName);
        }
    }

    private static JSONObject executeTask(
            Application context,
            ClassLoader classLoader,
            String requestId,
            JSONObject manifest,
            File stage) throws Exception {
        JSONObject sources = manifest;
        File backup = new File(MigrationContract.migrationRoot(
                context.getFilesDir()), "backups/" + requestId);
        ensureDirectory(backup);
        File filesDir = context.getFilesDir();
        File dictDir = new File(filesDir, "dict");
        File englishDir = new File(filesDir,
                "foreign/user_dict/1");
        File shortcutDir = new File(filesDir, "shortcutphrases");
        File userDictionary = new File(dictDir, "sgim_gd_usr.bin");
        File oemUud = new File(dictDir,
                MigrationContract.UUD_FILE_NAME);
        File oemShortcut = new File(shortcutDir,
                MigrationContract.SHORTCUT_FILE_NAME);

        backupFile(userDictionary, backup);
        backupFile(oemUud, backup);
        backupFile(oemShortcut, backup);
        for (String name : MigrationContract.ENGLISH_FILE_NAMES) {
            backupFile(new File(englishDir, name), backup);
        }

        long dictionarySizeBefore = userDictionary.length();
        long dictionaryModifiedBefore = userDictionary.lastModified();
        String dictionaryShaBefore = userDictionary.isFile()
                ? MigrationContract.sha256(userDictionary) : "";

        int uudRecords = 0;
        boolean workerQueued = false;
        int englishFiles = 0;
        int shortcutsAdded = 0;
        int shortcutsSkipped = 0;
        int shortcutsFailed = 0;

        JSONObject uudSpec = sources.optJSONObject("uud");
        if (uudSpec != null) {
            copyAtomic(new File(stage,
                    MigrationContract.UUD_FILE_NAME), oemUud);
            uudRecords = uudSpec.optInt("records", 0);
            queueMergeJob(context, classLoader);
            workerQueued = true;
        }

        JSONArray english = sources.optJSONArray("english");
        if (english != null) {
            ensureDirectory(englishDir);
            for (int i = 0; i < english.length(); i++) {
                String fileName = english.getJSONObject(i)
                        .optString("fileName", "");
                if (!MigrationFileInspector
                        .isAllowedEnglishName(fileName)) {
                    throw new IllegalArgumentException(
                            "英文词库文件名不受支持");
                }
                copyAtomic(new File(stage, fileName),
                        new File(englishDir, fileName));
                englishFiles++;
            }
        }

        JSONObject shortcutSpec = sources.optJSONObject("shortcut");
        if (shortcutSpec != null) {
            ShortcutResult shortcuts = mergeShortcutPhrases(
                    classLoader,
                    new File(stage,
                            MigrationContract.SHORTCUT_FILE_NAME),
                    oemShortcut);
            shortcutsAdded = shortcuts.added;
            shortcutsSkipped = shortcuts.skipped;
            shortcutsFailed = shortcuts.failed;
        }

        boolean dictionaryChanged = false;
        long dictionarySizeAfter = dictionarySizeBefore;
        long dictionaryModifiedAfter = dictionaryModifiedBefore;
        if (workerQueued) {
            long deadline = System.currentTimeMillis()
                    + WORKER_WAIT_MILLIS;
            long firstChangeAt = 0L;
            while (System.currentTimeMillis() < deadline) {
                dictionarySizeAfter = userDictionary.length();
                dictionaryModifiedAfter = userDictionary.lastModified();
                if (dictionarySizeAfter != dictionarySizeBefore
                        || dictionaryModifiedAfter
                        != dictionaryModifiedBefore) {
                    dictionaryChanged = true;
                    if (firstChangeAt == 0L) {
                        firstChangeAt = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - firstChangeAt
                            >= 1_500L) {
                        break;
                    }
                }
                Thread.sleep(500L);
            }
        }

        String dictionaryShaAfter = userDictionary.isFile()
                ? MigrationContract.sha256(userDictionary) : "";
        if (!dictionaryShaBefore.equals(dictionaryShaAfter)) {
            dictionaryChanged = true;
        }

        String status;
        String message;
        if (shortcutsFailed > 0) {
            status = "partial";
            message = "迁移已完成，但有快捷短语添加失败";
        } else if (workerQueued && !dictionaryChanged) {
            status = "queued";
            message = "worker 已提交，词典文件暂未观察到变化";
        } else {
            status = "success";
            message = "迁移已提交并完成";
        }

        JSONObject result = baseResult(
                requestId, status, message);
        result.put("uudRecords", uudRecords)
                .put("englishFiles", englishFiles)
                .put("shortcutsAdded", shortcutsAdded)
                .put("shortcutsSkipped", shortcutsSkipped)
                .put("shortcutsFailed", shortcutsFailed)
                .put("workerQueued", workerQueued)
                .put("dictionaryChanged", dictionaryChanged)
                .put("dictionarySize", dictionarySizeAfter)
                .put("dictionarySha256", dictionaryShaAfter)
                .put("backupRequestId", requestId);
        return result;
    }

    private static void queueMergeJob(
            Context context, ClassLoader classLoader)
            throws Exception {
        Class<?> imeInterface = Class.forName(
                "com.sohu.inputmethod.engine.IMEInterface",
                true, classLoader);
        Object instance = invokeGetInstance(
                imeInterface, context);

        Class<?> jobClass = Class.forName(
                "com.sohu.inputmethod.engine.Job",
                true, classLoader);
        Constructor<?> constructor = findJobConstructor(jobClass);
        Object job = constructor.newInstance(
                MERGE_JOB_ID, null, new Bundle());
        Method call = findCallCoreWorker(
                instance.getClass(), jobClass);
        call.setAccessible(true);
        call.invoke(instance, job);
    }

    private static Object invokeGetInstance(
            Class<?> type, Context context) throws Exception {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"getInstance".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0]
                        .isAssignableFrom(context.getClass())) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(null, context);
            }
        }
        throw new NoSuchMethodException(
                "IMEInterface.getInstance(Context)");
    }

    private static Constructor<?> findJobConstructor(
            Class<?> jobClass) throws Exception {
        for (Constructor<?> constructor
                : jobClass.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 3
                    && (params[0] == int.class
                    || params[0] == Integer.class)
                    && !params[1].isPrimitive()
                    && Bundle.class.isAssignableFrom(params[2])) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException(
                "Job(int,IReplySheet,Bundle)");
    }

    private static Method findCallCoreWorker(
            Class<?> type, Class<?> jobClass) throws Exception {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"callCoreWorker".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0]
                        .isAssignableFrom(jobClass)) {
                    continue;
                }
                return method;
            }
        }
        throw new NoSuchMethodException(
                "IMEInterface.callCoreWorker(Job)");
    }

    private static ShortcutResult mergeShortcutPhrases(
            ClassLoader classLoader,
            File officialJson,
            File oemJson) throws Exception {
        List<String> official =
                MigrationFileInspector.visibleShortcuts(officialJson);
        Set<String> existing = new HashSet<>();
        if (oemJson.isFile()) {
            try {
                existing.addAll(
                        MigrationFileInspector.visibleShortcuts(
                                oemJson));
            } catch (Throwable ignored) {
                // A malformed OEM list is treated as empty and backed up.
            }
        }

        Class<?> serviceClass = Class.forName(
                "com.sogou.shortcutphrase.ShortcutPhraseServiceImpl",
                true, classLoader);
        Constructor<?> constructor =
                serviceClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object service = constructor.newInstance();
        Method addQuickPhrase = findMethod(
                serviceClass, "addQuickPhrase", String.class);
        addQuickPhrase.setAccessible(true);

        int added = 0;
        int skipped = 0;
        int failed = 0;
        for (String content : official) {
            if (!existing.add(content)) {
                skipped++;
                continue;
            }
            try {
                Object result = addQuickPhrase.invoke(service, content);
                if (Boolean.TRUE.equals(result)) {
                    added++;
                } else {
                    failed++;
                }
            } catch (Throwable ignored) {
                failed++;
            }
        }
        return new ShortcutResult(added, skipped, failed);
    }

    private static Method findMethod(
            Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // Search the superclass.
            }
        }
        throw new NoSuchMethodException(name);
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

    private static void backupFile(File source, File backupDir)
            throws IOException {
        if (!source.isFile()) {
            return;
        }
        File parent = source.getParentFile();
        String relative = parent == null
                ? source.getName()
                : parent.getName() + "/" + source.getName();
        File destination = new File(backupDir, relative);
        ensureDirectory(destination.getParentFile());
        Files.copy(source.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyAtomic(File source, File destination)
            throws IOException {
        File parent = destination.getParentFile();
        ensureDirectory(parent);
        File temp = new File(parent,
                "." + destination.getName() + "."
                        + System.nanoTime() + ".tmp");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }

        try {
            Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureDirectory(File directory)
            throws IOException {
        if (directory != null && !directory.exists()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("无法创建目录: " + directory);
        }
    }

    private static JSONObject baseResult(
            String requestId, String status, String message)
            throws Exception {
        return new JSONObject()
                .put("schemaVersion",
                        MigrationContract.BROKER_SCHEMA_VERSION)
                .put("requestId", requestId)
                .put("status", status)
                .put("finishedAt", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                        .format(new Date()))
                .put("message", message == null ? "" : message);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
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

    private static boolean isBrokerUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.US);
                if (lower.contains("permission")
                        || lower.contains("unknown url")
                        || lower.contains("not found")
                        || lower.contains("unable to find provider")
                        || lower.contains("not visible")) {
                    return true;
                }
            }
            if (current instanceof SecurityException
                    || current instanceof IllegalArgumentException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class ShortcutResult {
        final int added;
        final int skipped;
        final int failed;

        ShortcutResult(int added, int skipped, int failed) {
            this.added = added;
            this.skipped = skipped;
            this.failed = failed;
        }
    }
}
