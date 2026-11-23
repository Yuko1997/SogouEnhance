package dev.saran.sogouenhance;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.IOException;

final class MigrationBrokerClient {
    private MigrationBrokerClient() {
    }

    static JSONObject getState(Context context) throws IOException {
        Bundle response = call(context,
                MigrationContract.CALL_GET_STATE, null, null);
        return parseState(response);
    }

    static JSONObject createSourceRequest(Context context)
            throws IOException {
        Bundle response = call(context,
                MigrationContract.CALL_CREATE_SOURCE, null, null);
        return parseState(response);
    }

    static JSONObject getPendingSource(Context context)
            throws IOException {
        Bundle response = call(context,
                MigrationContract.CALL_GET_PENDING_SOURCE, null, null);
        return optionalState(response);
    }

    static void completeSource(
            Context context, String requestId,
            JSONObject manifest) throws IOException {
        Bundle extras = new Bundle();
        extras.putString(MigrationContract.EXTRA_MANIFEST_JSON,
                manifest.toString());
        requireOk(call(context,
                MigrationContract.CALL_COMPLETE_SOURCE,
                requestId, extras));
    }

    static void failSource(
            Context context, String requestId, String message) {
        Bundle extras = new Bundle();
        extras.putString(MigrationContract.EXTRA_ERROR,
                message == null ? "官方版导出失败" : message);
        try {
            call(context, MigrationContract.CALL_FAIL_SOURCE,
                    requestId, extras);
        } catch (Throwable ignored) {
            // The source hook still logs the original failure.
        }
    }

    static JSONObject submitDestination(Context context)
            throws IOException {
        Bundle response = call(context,
                MigrationContract.CALL_SUBMIT_DESTINATION,
                null, null);
        return parseState(response);
    }

    static JSONObject claimDestination(Context context)
            throws IOException {
        Bundle response = call(context,
                MigrationContract.CALL_CLAIM_DESTINATION,
                null, null);
        return optionalState(response);
    }

    static void completeDestination(
            Context context, JSONObject result) throws IOException {
        Bundle extras = new Bundle();
        extras.putString(MigrationContract.EXTRA_RESULT_JSON,
                result.toString());
        requireOk(call(context,
                MigrationContract.CALL_COMPLETE_DESTINATION,
                result.optString("requestId", ""), extras));
    }

    static Uri sourceFileUri(String requestId, String fileName) {
        return Uri.parse(MigrationContract.BROKER_BASE_URI)
                .buildUpon()
                .appendPath("source")
                .appendPath(requestId)
                .appendPath(fileName)
                .build();
    }

    static void triggerSource(Context context) {
        sendTrigger(context, MigrationContract.SOURCE_PACKAGE,
                MigrationContract.ACTION_TRIGGER_SOURCE);
    }

    static void triggerTarget(Context context) {
        sendTrigger(context, MigrationContract.TARGET_PACKAGE,
                MigrationContract.ACTION_TRIGGER_TARGET);
    }

    private static void sendTrigger(
            Context context, String packageName, String action) {
        Intent intent = new Intent(action)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }

    private static Bundle call(
            Context context, String method,
            String arg, Bundle extras) throws IOException {
        Bundle response = context.getContentResolver().call(
                Uri.parse(MigrationContract.BROKER_BASE_URI),
                method, arg, extras);
        if (response == null) {
            throw new IOException("迁移中转服务无响应");
        }
        requireOk(response);
        return response;
    }

    private static JSONObject parseState(Bundle response)
            throws IOException {
        try {
            return new JSONObject(response.getString(
                    MigrationContract.EXTRA_STATE_JSON));
        } catch (Exception e) {
            throw new IOException("迁移中转状态无法解析", e);
        }
    }

    private static JSONObject optionalState(Bundle response)
            throws IOException {
        String state = response.getString(
                MigrationContract.EXTRA_STATE_JSON);
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(state);
        } catch (Exception e) {
            throw new IOException("迁移中转状态无法解析", e);
        }
    }

    private static void requireOk(Bundle response)
            throws IOException {
        if (!response.getBoolean(
                MigrationContract.EXTRA_OK, false)) {
            throw new IOException(response.getString(
                    MigrationContract.EXTRA_ERROR,
                    "迁移中转操作失败"));
        }
    }
}
