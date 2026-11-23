package dev.saran.sogouenhance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MigrationBrokerProvider extends ContentProvider {
    private static final long MAX_SOURCE_FILE_BYTES = 96L * 1024L * 1024L;

    private File brokerRoot;
    private File stateFile;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        brokerRoot = MigrationContract.brokerRoot(
                context.getFilesDir());
        stateFile = new File(brokerRoot, "source.json");
        return brokerRoot.exists() || brokerRoot.mkdirs();
    }

    @Override
    public synchronized Bundle call(
            String method, String arg, Bundle extras) {
        Bundle response = new Bundle();
        try {
            JSONObject state;
            if (MigrationContract.CALL_CREATE_SOURCE.equals(method)) {
                requireOwn();
                state = createSource();
            } else if (MigrationContract.CALL_GET_PENDING_SOURCE
                    .equals(method)) {
                requireSource();
                state = pendingSource();
            } else if (MigrationContract.CALL_COMPLETE_SOURCE
                    .equals(method)) {
                requireSource();
                state = completeSource(arg, extras);
            } else if (MigrationContract.CALL_FAIL_SOURCE
                    .equals(method)) {
                requireSource();
                state = failSource(arg, extras);
            } else if (MigrationContract.CALL_GET_STATE
                    .equals(method)) {
                requireAnyBrokerRole();
                state = readState();
            } else if (MigrationContract.CALL_SUBMIT_DESTINATION
                    .equals(method)) {
                requireOwn();
                state = submitDestination();
            } else if (MigrationContract.CALL_CLAIM_DESTINATION
                    .equals(method)) {
                requireDestination();
                state = claimDestination();
            } else if (MigrationContract.CALL_COMPLETE_DESTINATION
                    .equals(method)) {
                requireDestination();
                state = completeDestination(extras);
            } else {
                throw new IllegalArgumentException(
                        "未知中转操作: " + method);
            }

            response.putBoolean(MigrationContract.EXTRA_OK, true);
            if (state != null) {
                response.putString(
                        MigrationContract.EXTRA_STATE_JSON,
                        withResult(state).toString());
            }
            return response;
        } catch (Throwable t) {
            response.putBoolean(MigrationContract.EXTRA_OK, false);
            response.putString(MigrationContract.EXTRA_ERROR,
                    messageOf(t));
            return response;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(
            Uri uri, String mode) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3
                || !"source".equals(segments.get(0))) {
            throw new FileNotFoundException("Unsupported URI");
        }
        String requestId = segments.get(1);
        String fileName = segments.get(2);
        if (!MigrationContract.isValidRequestId(requestId)
                || !isAllowedFileName(fileName)) {
            throw new FileNotFoundException("Invalid broker path");
        }

        boolean writing = mode.startsWith("w")
                || mode.startsWith("rwt");
        if (writing) {
            requireSource();
        } else {
            if (!callerIsSource() && !callerIsDestination()
                    && !callerIsOwn()) {
                throw new SecurityException(
                        "Caller may not read migration files");
            }
        }
        JSONObject state = readState();
        if (!requestId.equals(state.optString("requestId", ""))) {
            throw new FileNotFoundException("Migration request mismatch");
        }
        String status = state.optString("status", "");
        if (writing
                && !MigrationContract.STATUS_REQUESTED.equals(status)
                && !MigrationContract.STATUS_COLLECTING.equals(status)) {
            throw new FileNotFoundException(
                    "Migration request is not accepting files");
        }

        File file = sourceFile(requestId, fileName);
        File parent = file.getParentFile();
        if (writing && parent != null && !parent.exists()
                && !parent.mkdirs() && !parent.isDirectory()) {
            throw new FileNotFoundException(
                    "Cannot create broker directory");
        }
        if (!writing && !file.isFile()) {
            throw new FileNotFoundException(
                    "Migration file is not ready");
        }
        if (writing && file.length() > MAX_SOURCE_FILE_BYTES) {
            throw new FileNotFoundException("Migration file is too large");
        }
        try {
            return ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.parseMode(mode));
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(
            Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(
            Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private JSONObject createSource() throws Exception {
        JSONObject current = readState();
        String status = current.optString(
                "status", MigrationContract.STATUS_IDLE);
        if (isActiveSourceStatus(status)) {
            String requestId =
                    current.optString("requestId", "");
            if (MigrationContract.isValidRequestId(requestId)) {
                grantSourceAccess(requestId);
            }
            prepareDestinationAccess();
            return current;
        }

        deleteTree(new File(brokerRoot, "source"));
        String requestId = MigrationContract.newRequestId();
        JSONObject state = new JSONObject();
        state.put("schemaVersion",
                        MigrationContract.BROKER_SCHEMA_VERSION)
                .put("requestId", requestId)
                .put("status", MigrationContract.STATUS_REQUESTED)
                .put("createdAt", now())
                .put("updatedAt", now());
        grantSourceAccess(requestId);
        prepareDestinationAccess();
        writeState(state);
        return state;
    }

    private JSONObject pendingSource() throws Exception {
        JSONObject state = readState();
        String status = state.optString(
                "status", MigrationContract.STATUS_IDLE);
        if (!MigrationContract.STATUS_REQUESTED.equals(status)
                && !MigrationContract.STATUS_COLLECTING.equals(status)) {
            return null;
        }
        if (MigrationContract.STATUS_REQUESTED.equals(status)) {
            state.put("status", MigrationContract.STATUS_COLLECTING)
                    .put("updatedAt", now());
            writeState(state);
        }
        return state;
    }

    private JSONObject completeSource(
            String requestId, Bundle extras) throws Exception {
        JSONObject state = requireState(requestId);
        String status = state.optString("status", "");
        if (!MigrationContract.STATUS_REQUESTED.equals(status)
                && !MigrationContract.STATUS_COLLECTING.equals(status)) {
            throw new IllegalStateException("官方版任务状态不允许完成");
        }
        JSONObject manifest = new JSONObject(extras.getString(
                MigrationContract.EXTRA_MANIFEST_JSON,
                "{}"));
        validateManifest(requestId, manifest);
        state.put("status", MigrationContract.STATUS_READY)
                .put("manifest", manifest)
                .put("updatedAt", now())
                .remove("error");
        writeState(state);
        revokeSourceAccess(requestId);
        return state;
    }

    private JSONObject failSource(
            String requestId, Bundle extras) throws Exception {
        JSONObject state = requireState(requestId);
        state.put("status", MigrationContract.STATUS_ERROR)
                .put("error", extras == null
                        ? "官方版导出失败"
                        : extras.getString(
                        MigrationContract.EXTRA_ERROR,
                        "官方版导出失败"))
                .put("updatedAt", now());
        writeState(state);
        revokeSourceAccess(requestId);
        return state;
    }

    private JSONObject submitDestination() throws Exception {
        JSONObject state = readState();
        if (!MigrationContract.STATUS_READY.equals(
                state.optString("status", ""))) {
            throw new IllegalStateException("官方版数据尚未准备完成");
        }
        grantDestinationAccess(state);
        state.put("status", MigrationContract.STATUS_SUBMITTED)
                .put("submittedAt", now())
                .put("updatedAt", now());
        writeState(state);
        return state;
    }

    private JSONObject claimDestination() throws Exception {
        JSONObject state = readState();
        String status = state.optString("status", "");
        if (MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)) {
            if (!MigrationContract.isValidRequestId(
                    state.optString("requestId", ""))
                    || state.optJSONObject("manifest") == null) {
                state.put("status", MigrationContract.STATUS_ERROR)
                        .put("error", "迁移任务缺少有效数据清单")
                        .put("updatedAt", now());
                writeState(state);
                return null;
            }
            state.put("status", MigrationContract.STATUS_CLAIMED)
                    .put("claimedAt", now())
                    .put("updatedAt", now());
            writeState(state);
            return state;
        }
        return null;
    }

    private JSONObject completeDestination(Bundle extras)
            throws Exception {
        JSONObject state = readState();
        if (!MigrationContract.STATUS_CLAIMED.equals(
                state.optString("status", ""))) {
            throw new IllegalStateException("OEM 任务尚未领取");
        }
        JSONObject result = new JSONObject(extras.getString(
                MigrationContract.EXTRA_RESULT_JSON, "{}"));
        String requestId = state.optString("requestId", "");
        if (!requestId.equals(result.optString("requestId", ""))) {
            throw new IllegalStateException("迁移结果任务编号不一致");
        }

        writeResult(result);
        String resultStatus = result.optString("status", "error");
        state.put("result", result)
                .put("status", "error".equals(resultStatus)
                        ? MigrationContract.STATUS_ERROR
                        : MigrationContract.STATUS_DONE)
                .put("updatedAt", now());
        if ("error".equals(resultStatus)) {
            state.put("error", result.optString("message", "迁移失败"));
        }
        writeState(state);
        revokeDestinationAccess(state);
        if (!"error".equals(resultStatus)) {
            deleteTree(new File(brokerRoot,
                    "source/" + requestId));
        }
        return state;
    }

    private void grantDestinationAccess(JSONObject state)
            throws Exception {
        Context context = getContext();
        String requestId = state.optString("requestId", "");
        JSONObject manifest = state.optJSONObject("manifest");
        if (context == null || manifest == null) {
            throw new IllegalStateException("官方版数据清单缺失");
        }
        grantUri(context, MigrationContract.TARGET_PACKAGE,
                Uri.parse(MigrationContract.BROKER_BASE_URI),
                Intent.FLAG_GRANT_READ_URI_PERMISSION);

        JSONObject uud = manifest.optJSONObject("uud");
        if (uud != null) {
            grantUri(context, MigrationContract.TARGET_PACKAGE,
                    MigrationBrokerClient.sourceFileUri(
                            requestId,
                            MigrationContract.UUD_FILE_NAME),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        JSONObject shortcut = manifest.optJSONObject("shortcut");
        if (shortcut != null) {
            grantUri(context, MigrationContract.TARGET_PACKAGE,
                    MigrationBrokerClient.sourceFileUri(
                            requestId,
                            MigrationContract.SHORTCUT_FILE_NAME),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        JSONArray english = manifest.optJSONArray("english");
        if (english != null) {
            for (int i = 0; i < english.length(); i++) {
                grantUri(context, MigrationContract.TARGET_PACKAGE,
                        MigrationBrokerClient.sourceFileUri(
                                requestId,
                                english.getJSONObject(i)
                                        .getString("fileName")),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private void prepareDestinationAccess() {
        Context context = getContext();
        if (context != null) {
            grantUri(context, MigrationContract.TARGET_PACKAGE,
                    Uri.parse(MigrationContract.BROKER_BASE_URI),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    private void revokeDestinationAccess(JSONObject state) {
        Context context = getContext();
        JSONObject manifest = state.optJSONObject("manifest");
        if (context == null || manifest == null) {
            return;
        }
        try {
            context.revokeUriPermission(
                    MigrationContract.TARGET_PACKAGE,
                    Uri.parse(MigrationContract.BROKER_BASE_URI),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String requestId = state.optString("requestId", "");
            JSONObject uud = manifest.optJSONObject("uud");
            if (uud != null) {
                revokeUriForTarget(context,
                        MigrationBrokerClient.sourceFileUri(
                                requestId,
                                MigrationContract.UUD_FILE_NAME));
            }
            JSONObject shortcut =
                    manifest.optJSONObject("shortcut");
            if (shortcut != null) {
                revokeUriForTarget(context,
                        MigrationBrokerClient.sourceFileUri(
                                requestId,
                                MigrationContract.SHORTCUT_FILE_NAME));
            }
            JSONArray english = manifest.optJSONArray("english");
            if (english != null) {
                for (int i = 0; i < english.length(); i++) {
                    revokeUriForTarget(context,
                            MigrationBrokerClient.sourceFileUri(
                                    requestId,
                                    english.getJSONObject(i)
                                            .getString("fileName")));
                }
            }
        } catch (Throwable ignored) {
            // Grants also expire when the provider process is replaced.
        }
    }

    private void grantSourceAccess(String requestId) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        grantUri(context, MigrationContract.SOURCE_PACKAGE,
                Uri.parse(MigrationContract.BROKER_BASE_URI), flags);
        for (String fileName : allSourceFileNames()) {
            grantUri(context, MigrationContract.SOURCE_PACKAGE,
                    MigrationBrokerClient.sourceFileUri(
                            requestId, fileName), flags);
        }
    }

    private void revokeSourceAccess(String requestId) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.revokeUriPermission(
                    MigrationContract.SOURCE_PACKAGE,
                    Uri.parse(MigrationContract.BROKER_BASE_URI),
                    flags);
            for (String fileName : allSourceFileNames()) {
                context.revokeUriPermission(
                        MigrationContract.SOURCE_PACKAGE,
                        MigrationBrokerClient.sourceFileUri(
                                requestId, fileName),
                        flags);
            }
        } catch (Throwable ignored) {
            // Grants also expire when the provider process is replaced.
        }
    }

    private static List<String> allSourceFileNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        names.add(MigrationContract.UUD_FILE_NAME);
        names.add(MigrationContract.SHORTCUT_FILE_NAME);
        java.util.Collections.addAll(names,
                MigrationContract.ENGLISH_FILE_NAMES);
        return names;
    }

    private static void grantUri(
            Context context, String targetPackage,
            Uri uri, int flags) {
        context.grantUriPermission(
                targetPackage, uri, flags);
    }

    private static void revokeUriForTarget(
            Context context, Uri uri) {
        context.revokeUriPermission(
                MigrationContract.TARGET_PACKAGE,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private JSONObject readState() {
        if (!stateFile.isFile()) {
            JSONObject idle = new JSONObject();
            try {
                idle.put("schemaVersion",
                                MigrationContract.BROKER_SCHEMA_VERSION)
                        .put("status", MigrationContract.STATUS_IDLE);
            } catch (Exception ignored) {
                // JSONObject construction above cannot fail in practice.
            }
            return idle;
        }
        try {
            return new JSONObject(readUtf8(stateFile));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private JSONObject withResult(JSONObject state) {
        JSONObject copy = new JSONObject();
        try {
            copy = new JSONObject(state.toString());
            File resultFile = new File(brokerRoot,
                    "results/latest.json");
            if (resultFile.isFile()) {
                copy.put("result",
                        new JSONObject(readUtf8(resultFile)));
            }
        } catch (Throwable ignored) {
            // Return the state alone if an old result is unreadable.
        }
        return copy;
    }

    private void writeState(JSONObject state) throws Exception {
        File temp = new File(brokerRoot, ".source.json.tmp");
        MigrationContract.writeUtf8(temp, state.toString(2));
        Files.move(temp.toPath(), stateFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeResult(JSONObject result) throws Exception {
        File results = new File(brokerRoot, "results");
        if (!results.exists() && !results.mkdirs()
                && !results.isDirectory()) {
            throw new IOException("无法创建结果目录");
        }
        File destination = new File(results, "latest.json");
        File temp = new File(results, ".latest.json.tmp");
        MigrationContract.writeUtf8(temp, result.toString(2));
        Files.move(temp.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void validateManifest(
            String requestId, JSONObject manifest)
            throws Exception {
        if (manifest.optInt("schemaVersion", -1)
                != MigrationContract.BROKER_SCHEMA_VERSION
                || !requestId.equals(
                manifest.optString("requestId", ""))) {
            throw new IllegalStateException("官方版数据清单无效");
        }
        JSONObject uud = manifest.optJSONObject("uud");
        JSONObject shortcut = manifest.optJSONObject("shortcut");
        JSONArray english = manifest.optJSONArray("english");
        if (uud == null && shortcut == null
                && (english == null || english.length() == 0)) {
            throw new IllegalStateException("官方版没有可迁移数据");
        }
        if (uud != null) {
            validateFile(requestId, uud,
                    MigrationContract.UUD_FILE_NAME);
        }
        if (shortcut != null) {
            validateFile(requestId, shortcut,
                    MigrationContract.SHORTCUT_FILE_NAME);
        }
        if (english != null) {
            for (int i = 0; i < english.length(); i++) {
                JSONObject spec = english.getJSONObject(i);
                String fileName = spec.optString("fileName", "");
                if (!MigrationFileInspector
                        .isAllowedEnglishName(fileName)) {
                    throw new IllegalStateException(
                            "英文词库文件名无效");
                }
                validateFile(requestId, spec, fileName);
            }
        }
    }

    private void validateFile(
            String requestId, JSONObject spec, String expectedName)
            throws Exception {
        String fileName = spec.optString("fileName", "");
        if (!expectedName.equals(fileName)) {
            throw new IllegalStateException("数据文件名不一致");
        }
        File file = sourceFile(requestId, fileName);
        if (!file.isFile()
                || file.length() != spec.optLong("size", -1)
                || !spec.optString("sha256", "")
                .equalsIgnoreCase(MigrationContract.sha256(file))) {
            throw new IllegalStateException(
                    "官方版数据校验失败: " + fileName);
        }
        if (MigrationContract.UUD_FILE_NAME.equals(fileName)
                && MigrationFileInspector.inspectUud(file).records
                != spec.optInt("records", -1)) {
            throw new IllegalStateException(
                    "官方版 UUD 记录数不一致");
        }
        if (MigrationContract.SHORTCUT_FILE_NAME.equals(fileName)
                && MigrationFileInspector.countVisibleShortcuts(file)
                != spec.optInt("validCount", -1)) {
            throw new IllegalStateException(
                    "官方版快捷短语数量不一致");
        }
    }

    private JSONObject requireState(String requestId)
            throws Exception {
        JSONObject state = readState();
        if (!MigrationContract.isValidRequestId(requestId)
                || !requestId.equals(
                state.optString("requestId", ""))) {
            throw new IllegalStateException("迁移任务不存在");
        }
        return state;
    }

    private File sourceFile(String requestId, String fileName) {
        return new File(new File(brokerRoot,
                "source/" + requestId), fileName);
    }

    private static boolean isAllowedFileName(String name) {
        return MigrationContract.UUD_FILE_NAME.equals(name)
                || MigrationContract.SHORTCUT_FILE_NAME.equals(name)
                || MigrationFileInspector
                .isAllowedEnglishName(name);
    }

    private static boolean isActiveSourceStatus(String status) {
        return MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status)
                || MigrationContract.STATUS_READY.equals(status)
                || MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status);
    }

    private void requireOwn() {
        if (!callerIsOwn()) {
            throw new SecurityException("仅模块应用可执行该操作");
        }
    }

    private void requireSource() {
        if (!callerIsSource() && !callerIsOwn()) {
            throw new SecurityException("仅官方版可写入迁移数据");
        }
    }

    private void requireDestination() {
        if (!callerIsDestination() && !callerIsOwn()) {
            throw new SecurityException("仅 OEM 版可领取迁移数据");
        }
    }

    private void requireAnyBrokerRole() {
        if (!callerIsOwn() && !callerIsSource()
                && !callerIsDestination()) {
            throw new SecurityException("调用方不在迁移白名单中");
        }
    }

    private boolean callerIsOwn() {
        return Binder.getCallingUid() == Process.myUid();
    }

    private boolean callerIsSource() {
        return callerHasPackage(MigrationContract.SOURCE_PACKAGE);
    }

    private boolean callerIsDestination() {
        return callerHasPackage(MigrationContract.TARGET_PACKAGE);
    }

    private boolean callerHasPackage(String packageName) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) {
            return true;
        }
        PackageManager manager = context.getPackageManager();
        String[] packages = manager.getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String candidate : packages) {
            if (packageName.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String now() {
        return new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .format(new Date());
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

    private static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
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
}
