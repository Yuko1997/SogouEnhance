package dev.saran.sogouenhance;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.List;

final class MigrationProcessLauncher {
    private static final String TAG = "SogouEnhance";

    private MigrationProcessLauncher() {
    }

    static boolean wakeInBackground(Activity activity, boolean source) {
        if (launchTaskBehind(activity, source)) {
            return true;
        }
        return wakeWithInputMethod(activity, source);
    }

    private static boolean launchTaskBehind(
            Activity activity, boolean source) {
        String packageName = source
                ? MigrationContract.SOURCE_PACKAGE
                : MigrationContract.TARGET_PACKAGE;
        String className = source
                ? MigrationContract.SOURCE_WAKE_ACTIVITY
                : MigrationContract.TARGET_WAKE_ACTIVITY;
        Intent intent = Intent.makeMainActivity(
                new ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            activity.startActivity(intent,
                    ActivityOptions.makeTaskLaunchBehind().toBundle());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean wakeWithInputMethod(
            Activity activity, boolean source) {
        InputMethodManager manager = activity.getSystemService(
                InputMethodManager.class);
        EditText wakeInput = activity.findViewById(R.id.wake_input);
        if (manager == null || wakeInput == null) {
            return false;
        }

        String packageName = source
                ? MigrationContract.SOURCE_PACKAGE
                : MigrationContract.TARGET_PACKAGE;
        InputMethodInfo target = findInputMethod(manager, packageName);
        if (target == null) {
            Log.i(TAG, "input-method wake unavailable: "
                    + packageName + " is not enabled");
            return false;
        }

        InputMethodInfo previous =
                manager.getCurrentInputMethodInfo();
        String previousId = previous == null
                ? "" : previous.getId();
        wakeInput.setFocusableInTouchMode(true);
        wakeInput.requestFocus();
        if (wakeInput.getWindowToken() == null) {
            Log.i(TAG, "input-method wake unavailable: no window token");
            return false;
        }

        try {
            manager.setInputMethod(
                    wakeInput.getWindowToken(), target.getId());
            boolean shown = manager.showSoftInput(
                    wakeInput, InputMethodManager.SHOW_IMPLICIT);
            if (!shown) {
                shown = manager.showSoftInput(
                        wakeInput, InputMethodManager.SHOW_FORCED);
            }
            if (!shown) {
                if (!previousId.isEmpty()) {
                    manager.setInputMethod(
                            wakeInput.getWindowToken(), previousId);
                }
                Log.i(TAG, "input-method wake rejected: " + packageName);
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "input-method wake failed: " + packageName, t);
            return false;
        }

        Log.i(TAG, "input-method wake requested: " + packageName);
        wakeInput.postDelayed(() -> {
            try {
                if (!previousId.isEmpty()
                        && wakeInput.getWindowToken() != null) {
                    manager.setInputMethod(
                            wakeInput.getWindowToken(), previousId);
                }
                manager.hideSoftInputFromWindow(
                        wakeInput.getWindowToken(), 0);
                wakeInput.clearFocus();
            } catch (Throwable ignored) {
                // The wake request has already reached the target process.
            }
        }, 1_600L);
        return true;
    }

    private static InputMethodInfo findInputMethod(
            InputMethodManager manager, String packageName) {
        List<InputMethodInfo> methods =
                manager.getEnabledInputMethodList();
        for (InputMethodInfo method : methods) {
            if (packageName.equals(method.getPackageName())) {
                return method;
            }
        }
        return null;
    }
}
