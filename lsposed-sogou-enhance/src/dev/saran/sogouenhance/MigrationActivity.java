package dev.saran.sogouenhance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrationActivity extends Activity {
    private static final long WAKE_DELAY_MILLIS = 900L;
    private static final long WAKE_FALLBACK_MILLIS = 8_000L;
    private static final long BROADCAST_REFRESH_MILLIS = 2_500L;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshInFlight =
            new AtomicBoolean(false);

    private JSONObject brokerState = new JSONObject();
    private boolean busy;
    private String wakeKey = "";
    private long pendingSince;
    private long lastBroadcastAt;
    private boolean wakeStarted;
    private boolean fallbackShown;

    private View statusBand;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView endpointSummary;
    private TextView stepRead;
    private TextView stepConfirm;
    private TextView stepCommit;
    private TextView uudSummary;
    private TextView shortcutSummary;
    private TextView englishSummary;
    private TextView uudBadge;
    private TextView shortcutBadge;
    private TextView englishBadge;
    private TextView dataReadyCount;
    private TextView resultTitle;
    private TextView resultDetail;
    private View primaryAction;
    private ImageView primaryActionIcon;
    private TextView primaryActionLabel;
    private Button openSettings;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_migration);
        bindViews();
        configureWindow();
        configurePrimaryActionAccessibility();
        primaryAction.setOnClickListener(view -> handlePrimaryAction());
        openSettings.setOnClickListener(view ->
                startActivity(new Intent(
                        Settings.ACTION_INPUT_METHOD_SETTINGS)));
        renderEndpointSummary();
        renderState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshState(false);
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(pollState);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        statusBand = findViewById(R.id.status_band);
        statusIcon = findViewById(R.id.status_icon);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        endpointSummary = findViewById(R.id.endpoint_summary);
        stepRead = findViewById(R.id.step_read);
        stepConfirm = findViewById(R.id.step_confirm);
        stepCommit = findViewById(R.id.step_commit);
        uudSummary = findViewById(R.id.uud_summary);
        shortcutSummary = findViewById(R.id.shortcut_summary);
        englishSummary = findViewById(R.id.english_summary);
        uudBadge = findViewById(R.id.uud_badge);
        shortcutBadge = findViewById(R.id.shortcut_badge);
        englishBadge = findViewById(R.id.english_badge);
        dataReadyCount = findViewById(R.id.data_ready_count);
        resultTitle = findViewById(R.id.result_title);
        resultDetail = findViewById(R.id.result_detail);
        primaryAction = findViewById(R.id.capture_source);
        primaryActionIcon = findViewById(R.id.primary_action_icon);
        primaryActionLabel = findViewById(R.id.primary_action_label);
        openSettings = findViewById(R.id.open_settings);
        progress = findViewById(R.id.progress);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void configurePrimaryActionAccessibility() {
        primaryAction.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                        info.setClickable(host.isEnabled());
                    }
                });
    }

    private void handlePrimaryAction() {
        if (busy) {
            return;
        }
        String status = currentStatus();
        if (MigrationContract.STATUS_READY.equals(status)) {
            confirmSubmit();
        } else if (MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status)
                || MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)) {
            retryWake();
        } else {
            captureSource();
        }
    }

    private void captureSource() {
        setBusy(true, getString(R.string.creating_source_request));
        executor.execute(() -> {
            try {
                JSONObject state =
                        MigrationBrokerClient.createSourceRequest(this);
                brokerState = state;
                runOnUiThread(() -> {
                    setBusy(false, null);
                    renderState();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showError(messageOf(t));
                });
            }
        });
    }

    private void confirmSubmit() {
        if (busy
                || !MigrationContract.STATUS_READY.equals(
                currentStatus())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_migration_title)
                .setMessage(buildConfirmation())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.submit_migration,
                        (dialog, which) -> submitDestination())
                .show();
    }

    private void submitDestination() {
        setBusy(true, getString(R.string.submitting_task));
        executor.execute(() -> {
            try {
                brokerState =
                        MigrationBrokerClient.submitDestination(this);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    renderState();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showError(messageOf(t));
                });
            }
        });
    }

    private void retryWake() {
        String status = currentStatus();
        boolean source = MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status);
        sendProcessTrigger(source);
        if (MigrationProcessLauncher.wakeInBackground(this, source)) {
            scheduleBroadcastRefresh(source, wakeKey, 500L);
            scheduleBroadcastRefresh(source, wakeKey, 1_800L);
        } else {
            showWakeFallback(source);
        }
    }

    private void refreshState(boolean showErrors) {
        if (busy || !refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject state =
                        MigrationBrokerClient.getState(this);
                runOnUiThread(() -> {
                    brokerState = state;
                    renderState();
                });
            } catch (Throwable t) {
                if (showErrors) {
                    runOnUiThread(() -> showError(messageOf(t)));
                }
            } finally {
                refreshInFlight.set(false);
            }
        });
    }

    private void renderState() {
        if (brokerState == null) {
            brokerState = new JSONObject();
        }
        String status = currentStatus();
        JSONObject manifest =
                brokerState.optJSONObject("manifest");
        JSONObject result = brokerState.optJSONObject("result");
        renderManifest(manifest, status);
        renderSteps(status, manifest);

        if (MigrationContract.STATUS_IDLE.equals(status)) {
            showStatus(R.string.status_idle_title,
                    R.string.status_idle_detail, StatusTone.NEUTRAL);
        } else if (MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status)) {
            showStatus(R.string.status_collecting_title,
                    R.string.status_collecting_detail,
                    StatusTone.QUEUED);
        } else if (MigrationContract.STATUS_READY.equals(status)) {
            showStatus(R.string.status_ready_title,
                    R.string.status_ready_detail, StatusTone.READY);
        } else if (MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)) {
            showStatus(R.string.status_migrating_title,
                    R.string.status_migrating_detail,
                    StatusTone.QUEUED);
        } else if (MigrationContract.STATUS_DONE.equals(status)
                && result != null) {
            renderResultTone(result);
        } else {
            showStatus(getString(R.string.status_error_title),
                    brokerState.optString("error",
                            getString(R.string.status_error_detail)),
                    StatusTone.ERROR);
        }

        resultTitle.setText(result == null
                ? getString(R.string.result_empty_title)
                : getString(R.string.result_completed_title,
                formatTime(result.optString("finishedAt", ""))));
        resultDetail.setText(result == null
                ? getString(R.string.result_empty_detail)
                : buildResultDetail(result));

        renderPrimaryAction(status);
        ensurePendingProcessWake(status);
        schedulePollIfNeeded();
    }

    private final Runnable pollState = new Runnable() {
        @Override
        public void run() {
            refreshState(false);
            if (isActiveStatus(currentStatus())) {
                mainHandler.postDelayed(this, 1_000L);
            }
        }
    };

    private void schedulePollIfNeeded() {
        mainHandler.removeCallbacks(pollState);
        if (isActiveStatus(currentStatus())) {
            mainHandler.postDelayed(pollState, 1_000L);
        }
    }

    private void renderManifest(
            JSONObject manifest, String status) {
        JSONObject uud = manifest == null
                ? null : manifest.optJSONObject("uud");
        JSONObject shortcut = manifest == null
                ? null : manifest.optJSONObject("shortcut");
        JSONArray english = manifest == null
                ? null : manifest.optJSONArray("english");
        boolean collecting =
                MigrationContract.STATUS_REQUESTED.equals(status)
                        || MigrationContract.STATUS_COLLECTING.equals(status);

        uudSummary.setText(uud == null
                ? getString(R.string.uud_not_ready)
                : getString(R.string.uud_ready,
                formatNumber(uud.optInt("records", 0)),
                formatBytes(uud.optLong("size", 0))));
        shortcutSummary.setText(shortcut == null
                ? getString(R.string.shortcut_not_ready)
                : getString(R.string.shortcut_ready,
                shortcut.optInt("validCount", 0),
                formatBytes(shortcut.optLong("size", 0))));
        if (english == null || english.length() == 0) {
            englishSummary.setText(R.string.english_not_ready);
        } else {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < english.length(); i++) {
                names.add(english.optJSONObject(i)
                        .optString("fileName", ""));
            }
            englishSummary.setText(getString(
                    R.string.english_ready,
                    english.length(), TextUtils.join(" · ", names)));
        }

        applyDataBadge(uudBadge, uud != null,
                collecting);
        applyDataBadge(shortcutBadge, shortcut != null,
                collecting);
        int englishCount = english == null ? 0 : english.length();
        applyEnglishBadge(englishBadge, englishCount, collecting);

        int readyCount = (uud != null ? 1 : 0)
                + (shortcut != null ? 1 : 0)
                + (englishCount > 0 ? 1 : 0);
        dataReadyCount.setText(readyCount == 0
                ? getString(R.string.badge_waiting)
                : getString(R.string.data_ready_count, readyCount));
    }

    private void renderSteps(
            String status, JSONObject manifest) {
        boolean sourceReady = manifest != null
                || MigrationContract.STATUS_READY.equals(status)
                || MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)
                || MigrationContract.STATUS_DONE.equals(status);
        boolean writeStarted =
                MigrationContract.STATUS_SUBMITTED.equals(status)
                        || MigrationContract.STATUS_CLAIMED.equals(status)
                        || MigrationContract.STATUS_DONE.equals(status);
        boolean writeDone = MigrationContract.STATUS_DONE.equals(status);

        applyStep(stepRead, sourceReady
                ? StepState.COMPLETE
                : StepState.ACTIVE);
        if (writeStarted) {
            applyStep(stepConfirm, StepState.COMPLETE);
        } else {
            applyStep(stepConfirm,
                    MigrationContract.STATUS_READY.equals(status)
                            ? StepState.ACTIVE
                            : StepState.PENDING);
        }
        if (writeDone) {
            applyStep(stepCommit, StepState.COMPLETE);
        } else if (MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)) {
            applyStep(stepCommit, StepState.ACTIVE);
        } else {
            applyStep(stepCommit, StepState.PENDING);
        }
    }

    private void renderPrimaryAction(String status) {
        if (busy) {
            primaryAction.setEnabled(false);
            return;
        }
        if (MigrationContract.STATUS_READY.equals(status)) {
            setPrimaryAction(R.string.submit_migration,
                    R.drawable.ic_migrate, true);
        } else if (MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status)
                || MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status)) {
            setPrimaryAction(R.string.retry_wake,
                    R.drawable.ic_refresh, true);
        } else if (MigrationContract.STATUS_DONE.equals(status)
                || MigrationContract.STATUS_ERROR.equals(status)) {
            setPrimaryAction(R.string.retry_source,
                    R.drawable.ic_refresh, true);
        } else {
            setPrimaryAction(R.string.capture_source,
                    R.drawable.ic_download, true);
        }
    }

    private void setPrimaryAction(
            int labelResource, int iconResource, boolean enabled) {
        primaryActionLabel.setText(labelResource);
        primaryActionIcon.setImageResource(iconResource);
        setPrimaryActionEnabled(enabled);
    }

    private void ensurePendingProcessWake(String status) {
        boolean source = MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status);
        boolean target = MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status);
        if (!source && !target) {
            wakeKey = "";
            wakeStarted = false;
            fallbackShown = false;
            openSettings.setVisibility(View.GONE);
            return;
        }

        String requestId = brokerState.optString("requestId", "");
        String key = (source ? "source:" : "target:") + requestId;
        long now = SystemClock.elapsedRealtime();
        if (!key.equals(wakeKey)) {
            wakeKey = key;
            pendingSince = now;
            lastBroadcastAt = 0L;
            wakeStarted = false;
            fallbackShown = false;
            openSettings.setVisibility(View.GONE);
        }

        if (now - lastBroadcastAt >= BROADCAST_REFRESH_MILLIS) {
            sendProcessTrigger(source);
        }

        if (!wakeStarted
                && now - pendingSince >= WAKE_DELAY_MILLIS) {
            wakeStarted = true;
            if (MigrationProcessLauncher.wakeInBackground(
                    this, source)) {
                scheduleBroadcastRefresh(source, key, 500L);
                scheduleBroadcastRefresh(source, key, 1_800L);
            } else {
                showWakeFallback(source);
            }
        }

        if (!fallbackShown
                && now - pendingSince >= WAKE_FALLBACK_MILLIS) {
            showWakeFallback(source);
        }
    }

    private void sendProcessTrigger(boolean source) {
        lastBroadcastAt = SystemClock.elapsedRealtime();
        if (source) {
            MigrationBrokerClient.triggerSource(this);
        } else {
            MigrationBrokerClient.triggerTarget(this);
        }
    }

    private void scheduleBroadcastRefresh(
            boolean source, String expectedKey, long delayMillis) {
        mainHandler.postDelayed(() -> {
            if (expectedKey.equals(wakeKey)) {
                sendProcessTrigger(source);
            }
        }, delayMillis);
    }

    private void showWakeFallback(boolean source) {
        if (!fallbackShown) {
            fallbackShown = true;
            openSettings.setVisibility(View.VISIBLE);
            Toast.makeText(this,
                    source ? R.string.wake_source_failed
                            : R.string.wake_target_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderResultTone(JSONObject result) {
        String status = result.optString("status", "error");
        if ("success".equals(status)) {
            showStatus(R.string.status_success_title,
                    R.string.status_success_detail,
                    StatusTone.SUCCESS);
        } else if ("queued".equals(status)) {
            showStatus(R.string.status_queued_title,
                    R.string.status_queued_detail,
                    StatusTone.QUEUED);
        } else if ("partial".equals(status)) {
            showStatus(R.string.status_partial_title,
                    R.string.status_partial_detail,
                    StatusTone.WARNING);
        } else {
            showStatus(getString(R.string.status_error_title),
                    result.optString("message",
                            getString(R.string.status_error_detail)),
                    StatusTone.ERROR);
        }
    }

    private String buildConfirmation() {
        JSONObject manifest = brokerState.optJSONObject("manifest");
        List<String> lines = new ArrayList<>();
        if (manifest != null) {
            JSONObject uud = manifest.optJSONObject("uud");
            JSONObject shortcut = manifest.optJSONObject("shortcut");
            JSONArray english = manifest.optJSONArray("english");
            if (uud != null) {
                lines.add(getString(R.string.confirm_uud,
                        formatNumber(uud.optInt("records", 0))));
            }
            if (shortcut != null) {
                lines.add(getString(R.string.confirm_shortcuts,
                        shortcut.optInt("validCount", 0)));
            }
            if (english != null) {
                lines.add(getString(R.string.confirm_english,
                        english.length()));
            }
        }
        lines.add(getString(R.string.confirm_backup_note));
        lines.add(getString(R.string.confirm_trigger_note));
        return TextUtils.join("\n\n", lines);
    }

    private String buildResultDetail(JSONObject result) {
        List<String> parts = new ArrayList<>();
        int uud = result.optInt("uudRecords", 0);
        int english = result.optInt("englishFiles", 0);
        int added = result.optInt("shortcutsAdded", 0);
        int skipped = result.optInt("shortcutsSkipped", 0);
        if (uud > 0) {
            parts.add(getString(
                    R.string.result_uud, formatNumber(uud)));
        }
        if (english > 0) {
            parts.add(getString(
                    R.string.result_english, english));
        }
        if (added > 0 || skipped > 0) {
            parts.add(getString(
                    R.string.result_shortcuts, added, skipped));
        }
        if (result.optBoolean("dictionaryChanged", false)) {
            parts.add(getString(R.string.result_dictionary_changed));
        }
        String message = result.optString("message", "");
        if (!message.isEmpty()
                && !"success".equals(result.optString("status"))) {
            parts.add(message);
        }
        return TextUtils.join("\n", parts);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        setPrimaryActionEnabled(!value);
        openSettings.setEnabled(!value);
        if (value && message != null) {
            showStatus(message, null, StatusTone.WORKING);
        }
    }

    private void setPrimaryActionEnabled(boolean enabled) {
        int contentColor = getColor(enabled
                ? R.color.on_primary : R.color.text_secondary);
        primaryActionLabel.setTextColor(contentColor);
        primaryActionIcon.setImageTintList(
                ColorStateList.valueOf(contentColor));
        primaryAction.setEnabled(enabled);
    }

    private void showError(String message) {
        showStatus(getString(R.string.status_error_title),
                message, StatusTone.ERROR);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showStatus(int titleResource, int detailResource,
                            StatusTone tone) {
        showStatus(getString(titleResource),
                getString(detailResource), tone);
    }

    private void showStatus(
            String title, String detail, StatusTone tone) {
        statusTitle.setText(title);
        if (detail == null || detail.isEmpty()) {
            statusDetail.setVisibility(View.GONE);
        } else {
            statusDetail.setVisibility(View.VISIBLE);
            statusDetail.setText(detail);
        }

        int fill;
        int text;
        int icon;
        switch (tone) {
            case SUCCESS:
                fill = getColor(R.color.status_success_surface);
                text = getColor(R.color.status_success_text);
                icon = R.drawable.ic_status_ready;
                break;
            case QUEUED:
                fill = getColor(R.color.status_queued_surface);
                text = getColor(R.color.status_queued_text);
                icon = R.drawable.ic_status_sync;
                break;
            case WORKING:
                fill = getColor(R.color.surface_alt);
                text = getColor(R.color.text_primary);
                icon = R.drawable.ic_status_sync;
                break;
            case WARNING:
                fill = getColor(R.color.status_warning_surface);
                text = getColor(R.color.status_warning_text);
                icon = R.drawable.ic_status_error;
                break;
            case ERROR:
                fill = getColor(R.color.status_error_surface);
                text = getColor(R.color.status_error_text);
                icon = R.drawable.ic_status_error;
                break;
            case READY:
                fill = getColor(R.color.status_ready_surface);
                text = getColor(R.color.status_ready_text);
                icon = R.drawable.ic_status_ready;
                break;
            default:
                fill = getColor(R.color.surface_alt);
                text = getColor(R.color.text_primary);
                icon = R.drawable.ic_status_waiting;
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(fill);
        background.setStroke((int) dp(1), getColor(R.color.outline));
        statusBand.setBackground(background);
        statusTitle.setTextColor(text);
        statusDetail.setTextColor(text);
        statusIcon.setImageResource(icon);
        statusIcon.setImageTintList(ColorStateList.valueOf(text));
    }

    private void applyDataBadge(
            TextView badge, boolean ready, boolean collecting) {
        if (ready) {
            setBadge(badge, R.string.badge_ready, BadgeTone.READY);
        } else if (collecting) {
            setBadge(badge, R.string.badge_collecting,
                    BadgeTone.COLLECTING);
        } else {
            setBadge(badge, R.string.badge_waiting,
                    BadgeTone.WAITING);
        }
    }

    private void applyEnglishBadge(
            TextView badge, int fileCount, boolean collecting) {
        if (fileCount >= 4) {
            setBadge(badge, R.string.badge_ready, BadgeTone.READY);
        } else if (fileCount > 0) {
            setBadge(badge, R.string.badge_partial,
                    BadgeTone.PARTIAL);
        } else if (collecting) {
            setBadge(badge, R.string.badge_collecting,
                    BadgeTone.COLLECTING);
        } else {
            setBadge(badge, R.string.badge_waiting,
                    BadgeTone.WAITING);
        }
    }

    private void setBadge(
            TextView badge, int labelResource, BadgeTone tone) {
        int fill;
        int text;
        switch (tone) {
            case READY:
                fill = getColor(R.color.status_ready_surface);
                text = getColor(R.color.status_ready_text);
                break;
            case COLLECTING:
                fill = getColor(R.color.status_queued_surface);
                text = getColor(R.color.status_queued_text);
                break;
            case PARTIAL:
                fill = getColor(R.color.status_warning_surface);
                text = getColor(R.color.status_warning_text);
                break;
            default:
                fill = getColor(R.color.surface_alt);
                text = getColor(R.color.text_secondary);
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(fill);
        badge.setBackground(background);
        badge.setTextColor(text);
        badge.setText(labelResource);
    }

    private void applyStep(TextView step, StepState state) {
        int fill;
        int text;
        switch (state) {
            case ACTIVE:
                fill = getColor(R.color.primary);
                text = getColor(R.color.on_primary);
                break;
            case COMPLETE:
                fill = getColor(R.color.primary_container);
                text = getColor(R.color.on_primary_container);
                break;
            default:
                fill = Color.TRANSPARENT;
                text = getColor(R.color.text_secondary);
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(fill);
        step.setBackground(background);
        step.setTextColor(text);
    }

    private void renderEndpointSummary() {
        String source = packageVersion(
                MigrationContract.SOURCE_PACKAGE);
        String target = packageVersion(
                MigrationContract.TARGET_PACKAGE);
        if (source == null || target == null) {
            endpointSummary.setText(R.string.migration_route_unknown);
        } else {
            endpointSummary.setText(getString(
                    R.string.migration_route_known, source, target));
        }
    }

    private String packageVersion(String packageName) {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String currentStatus() {
        return brokerState.optString(
                "status", MigrationContract.STATUS_IDLE);
    }

    private boolean isActiveStatus(String status) {
        return MigrationContract.STATUS_REQUESTED.equals(status)
                || MigrationContract.STATUS_COLLECTING.equals(status)
                || MigrationContract.STATUS_SUBMITTED.equals(status)
                || MigrationContract.STATUS_CLAIMED.equals(status);
    }

    private String messageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private String formatNumber(int number) {
        return NumberFormat.getIntegerInstance(Locale.CHINA)
                .format(number);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f MiB",
                    bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.CHINA, "%.1f KiB",
                    bytes / 1024.0);
        }
        return bytes + " B";
    }

    private String formatTime(String timestamp) {
        try {
            Date date = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    .parse(timestamp);
            return new SimpleDateFormat(
                    "M月d日 HH:mm", Locale.CHINA)
                    .format(date);
        } catch (Throwable ignored) {
            return getString(R.string.just_now);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private enum StatusTone {
        READY,
        NEUTRAL,
        WORKING,
        QUEUED,
        SUCCESS,
        WARNING,
        ERROR
    }

    private enum StepState {
        PENDING,
        ACTIVE,
        COMPLETE
    }

    private enum BadgeTone {
        WAITING,
        COLLECTING,
        READY,
        PARTIAL
    }
}
