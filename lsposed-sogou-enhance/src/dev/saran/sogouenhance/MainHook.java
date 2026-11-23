package dev.saran.sogouenhance;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Sogou official-to-OEM migration and OEM enhancement hooks.
 *
 * The copy limit and blur whitelist are both inline DEX expressions. The
 * module changes their observable inputs instead of replacing either large
 * business method.
 */
public final class MainHook extends XposedModule {
    private static final String TAG = "SogouEnhance";
    private static final String SOURCE_PACKAGE =
            "com.sohu.inputmethod.sogou";
    private static final String TARGET_PACKAGE =
            "com.sohu.inputmethod.sogouoem";
    private static final String SOURCE_PROCESS = SOURCE_PACKAGE;
    private static final String TARGET_PROCESS = TARGET_PACKAGE;

    private static final int ORIGINAL_COPY_LIMIT = 5000;
    private static final String[] CLIPBOARD_LISTENER_CLASSES = {
            "y17", // original 10.32.0722.2611161508
            "e77"  // matching modified sample, useful for diagnostics
    };
    private static final String THEME_SERVICE_CLASS =
            "com.sogou.inputmethod.oem.oppo.theme.ThemeCustomServiceImpl";
    private static final String OEM_THEME_UTILS_CLASS =
            "com.sogou.inputmethod.oem.util.OemThemeUtils";
    private static final String SHORTCUT_PROXY_ACTIVITY_CLASS =
            "com.sogou.androidtool.ShortCutProxyActivity";
    private static final String GAUSS_WHITELIST_FIELD = "GAUSS_BLUR_EDITORS";
    private static final ThreadLocal<Boolean> CLIPBOARD_LISTENER_ACTIVE =
            new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SHORTCUT_PROXY_ACTIVE =
            new ThreadLocal<>();

    private final AtomicBoolean copyHitLogged = new AtomicBoolean(false);
    private final AtomicBoolean copyTempHookFailureLogged =
            new AtomicBoolean(false);
    private final AtomicBoolean systemThemeLogged = new AtomicBoolean(false);
    private final AtomicBoolean shortcutProxyToastSuppressed =
            new AtomicBoolean(false);
    private final AtomicBoolean migrationReceiverRegistered =
            new AtomicBoolean(false);
    private final Set<String> blurDecisionLogged = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private final Object clipboardHookLock = new Object();

    private volatile String processName;
    private volatile String currentEditorPackage;
    private volatile ClassLoader targetClassLoader;
    private volatile Boolean materialBlurEnabled;
    private volatile BroadcastReceiver migrationTriggerReceiver;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "loaded: " + processName);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getDefaultClassLoader();
        targetClassLoader = classLoader;
        if (SOURCE_PACKAGE.equals(packageName)) {
            if (processName != null
                    && !SOURCE_PROCESS.equals(processName)) {
                log(Log.INFO, TAG, "skip source process: " + processName);
                return;
            }
            installApplicationTriggerHook(
                    param, classLoader, true);
            installShortcutProxyToastSuppression(classLoader);
            installSourceEngineReadyHook(classLoader);
            return;
        }
        if (TARGET_PACKAGE.equals(packageName)) {
            if (processName != null
                    && !TARGET_PROCESS.equals(processName)) {
                log(Log.INFO, TAG, "skip target process: " + processName);
                return;
            }
            installApplicationTriggerHook(
                    param, classLoader, false);
            installShortcutProxyToastSuppression(classLoader);
            installClipboardHook(classLoader);
            installBlurWhitelistHook(classLoader);
        }
    }

    private void installShortcutProxyToastSuppression(
            ClassLoader classLoader) {
        try {
            Class<?> proxyActivity = Class.forName(
                    SHORTCUT_PROXY_ACTIVITY_CLASS, false, classLoader);
            Method onCreate = proxyActivity.getDeclaredMethod(
                    "onCreate", Bundle.class);
            Method toastShow = Toast.class.getDeclaredMethod("show");

            hook(toastShow)
                    .setId("sogou-shortcut-proxy-toast")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            if (Boolean.TRUE.equals(
                                    SHORTCUT_PROXY_ACTIVE.get())) {
                                if (shortcutProxyToastSuppressed
                                        .compareAndSet(false, true)) {
                                    log(Log.INFO, TAG,
                                            "shortcut proxy toast suppressed");
                                }
                                return null;
                            }
                            return chain.proceed();
                        }
                    });

            hook(onCreate)
                    .setId("sogou-shortcut-proxy-lifecycle")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            Boolean previous =
                                    SHORTCUT_PROXY_ACTIVE.get();
                            SHORTCUT_PROXY_ACTIVE.set(Boolean.TRUE);
                            try {
                                return chain.proceed();
                            } finally {
                                if (previous == null) {
                                    SHORTCUT_PROXY_ACTIVE.remove();
                                } else {
                                    SHORTCUT_PROXY_ACTIVE.set(previous);
                                }
                            }
                        }
                    });

            log(Log.INFO, TAG,
                    "shortcut proxy toast hook installed: "
                            + SHORTCUT_PROXY_ACTIVITY_CLASS);
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "shortcut proxy toast hook skipped", t);
        }
    }

    private void installApplicationTriggerHook(
            PackageLoadedParam param, ClassLoader classLoader,
            boolean sourceRole) {
        try {
            String className = param.getApplicationInfo().className;
            if (className == null || className.isEmpty()) {
                className = "android.app.Application";
            }
            Class<?> applicationClass = Class.forName(
                    className, false, classLoader);
            Method onCreate = findMethodInHierarchy(
                    applicationClass, "onCreate");
            hook(onCreate)
                    .setId("sogou-migration-trigger:" + className)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            Object result = chain.proceed();
                            Object application = chain.getThisObject();
                            if (application instanceof Context) {
                                registerMigrationReceiver(
                                        (Context) application,
                                        classLoader, sourceRole);
                            }
                            return result;
                        }
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "migration trigger hook skipped", t);
        }
    }

    private void registerMigrationReceiver(
            Context context, ClassLoader classLoader,
            boolean sourceRole) {
        if (!migrationReceiverRegistered.compareAndSet(
                false, true)) {
            return;
        }
        IntentFilter filter = new IntentFilter(
                sourceRole
                        ? MigrationContract.ACTION_TRIGGER_SOURCE
                        : MigrationContract.ACTION_TRIGGER_TARGET);
        MigrationTriggerReceiver receiver =
                new MigrationTriggerReceiver(
                        this, classLoader, sourceRole);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                    receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        migrationTriggerReceiver = receiver;
        log(Log.INFO, TAG,
                "migration trigger receiver registered: "
                        + (sourceRole ? "source" : "target"));
    }

    private void installSourceEngineReadyHook(ClassLoader classLoader) {
        boolean installed = hookSourceInputView(
                classLoader,
                "com.sohu.inputmethod.sogou.SogouIME");
        installed |= hookSourceInputView(
                classLoader,
                "com.sogou.bu.ims.support.BaseInputMethodService");
        if (!installed) {
            log(Log.ERROR, TAG,
                    "official source input-view hooks unavailable");
        }
    }

    private boolean hookSourceInputView(
            ClassLoader classLoader, String className) {
        try {
            Class<?> inputService = Class.forName(
                    className, false, classLoader);
            Method startInputView = inputService.getDeclaredMethod(
                    "onStartInputView", EditorInfo.class, boolean.class);
            hook(startInputView)
                    .setId("sogou-source-engine-ready:" + className)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            Object result = chain.proceed();
                            MigrationSource.onEngineReady(
                                    MainHook.this,
                                    targetClassLoader);
                            return result;
                        }
                    });
            log(Log.INFO, TAG,
                    "official source input-view hook installed: "
                            + className);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "official source input-view hook skipped: "
                            + className, t);
            return false;
        }
    }

    private void installClipboardHook(ClassLoader classLoader) {
        try {
            Class<?> listenerClass = findClipboardListener(classLoader);
            if (listenerClass == null) {
                log(Log.WARN, TAG, "clipboard listener class not found");
                return;
            }

            Method listenerMethod = findClipboardLimitMethod(listenerClass);
            if (listenerMethod == null) {
                log(Log.WARN, TAG, "clipboard limit method not found in "
                        + listenerClass.getName());
                return;
            }

            final Method lengthMethod = String.class.getDeclaredMethod("length");
            hook(listenerMethod)
                    .setId("sogou-clipboard-listener")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain)
                                throws Throwable {
                            CLIPBOARD_LISTENER_ACTIVE.set(Boolean.TRUE);
                            try {
                                synchronized (clipboardHookLock) {
                                    final XposedInterface.HookHandle lengthHook;
                                    try {
                                        lengthHook = hook(lengthMethod)
                                                .setId("sogou-clipboard-length-temp")
                                                .intercept(
                                                        new XposedInterface.Hooker() {
                                                            @Override
                                                            public Object intercept(
                                                                    XposedInterface.Chain
                                                                            lengthChain)
                                                                    throws Throwable {
                                                                Object result =
                                                                        lengthChain.proceed();
                                                                if (!(result instanceof Integer)) {
                                                                    return result;
                                                                }

                                                                int actualLength =
                                                                        (Integer) result;
                                                                boolean listenerActive =
                                                                        Boolean.TRUE.equals(
                                                                                CLIPBOARD_LISTENER_ACTIVE.get());
                                                                if (actualLength <= ORIGINAL_COPY_LIMIT
                                                                        || !listenerActive) {
                                                                    return result;
                                                                }

                                                                if (copyHitLogged
                                                                        .compareAndSet(false, true)) {
                                                                    log(Log.INFO, TAG,
                                                                            "clipboard length limit bypass active ("
                                                                                    + actualLength
                                                                                    + " -> "
                                                                                    + ORIGINAL_COPY_LIMIT
                                                                                    + ")");
                                                                }
                                                                return ORIGINAL_COPY_LIMIT;
                                                            }
                                                        });
                                    } catch (Throwable t) {
                                        if (copyTempHookFailureLogged
                                                .compareAndSet(false, true)) {
                                            log(Log.ERROR, TAG,
                                                    "temporary clipboard hook failed",
                                                    t);
                                        }
                                        return chain.proceed();
                                    }

                                    try {
                                        return chain.proceed();
                                    } finally {
                                        lengthHook.unhook();
                                    }
                                }
                            } finally {
                                CLIPBOARD_LISTENER_ACTIVE.remove();
                            }
                        }
                    });

            log(Log.INFO, TAG, "clipboard entry hook installed: "
                    + listenerClass.getName() + "."
                    + listenerMethod.getName());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "clipboard hook failed", t);
        }
    }

    private void installBlurWhitelistHook(ClassLoader classLoader) {
        try {
            Class<?> themeClass = Class.forName(
                    THEME_SERVICE_CLASS, true, classLoader);
            Class<?> oemThemeUtilsClass = Class.forName(
                    OEM_THEME_UTILS_CLASS, true, classLoader);

            Method isSystemThemeMethod = oemThemeUtilsClass.getDeclaredMethod(
                    "isSystemTheme");
            hook(isSystemThemeMethod)
                    .setId("sogou-gauss-system-theme")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain)
                                throws Throwable {
                            Object result = chain.proceed();
                            if (result instanceof Boolean
                                    && systemThemeLogged.compareAndSet(
                                    false, true)) {
                                log(Log.INFO, TAG, "system theme=" + result);
                            }
                            return result;
                        }
                    });

            installBlurDecisionHooks(themeClass);
            Field whitelistField = themeClass.getDeclaredField(
                    GAUSS_WHITELIST_FIELD);
            whitelistField.setAccessible(true);
            final Object whitelistValue = whitelistField.get(null);
            if (!(whitelistValue instanceof String[])) {
                throw new IllegalStateException("GAUSS_BLUR_EDITORS is null");
            }
            final String[] whitelistArray = (String[]) whitelistValue;

            Class<?> arraysListClass = Class.forName(
                    "java.util.Arrays$ArrayList", false, classLoader);
            Method containsMethod = arraysListClass.getDeclaredMethod(
                    "contains", Object.class);

            hook(containsMethod)
                    .setId("sogou-gauss-whitelist")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain)
                                throws Throwable {
                            Object receiver = chain.getThisObject();
                            Object argument = chain.getArg(0);
                            if (receiver instanceof List
                                    && argument instanceof String
                                    && matchesStringList(
                                    (List<?>) receiver, whitelistArray)) {
                                if (logBlurDecisionOnce(
                                        "gauss:" + argument)) {
                                    log(Log.INFO, TAG,
                                            "gauss whitelist bypass active for "
                                                    + argument);
                                }
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });

            log(Log.INFO, TAG, "gauss whitelist hook installed: "
                    + THEME_SERVICE_CLASS + "." + GAUSS_WHITELIST_FIELD);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "gauss whitelist hook failed", t);
        }
    }

    private void installBlurDecisionHooks(Class<?> themeClass) {
        hookBlurDecisionMethod(themeClass, "configTheme",
                new Class<?>[]{EditorInfo.class});
        hookBlurDecisionMethod(themeClass, "isBlurSupportedInternal",
                new Class<?>[]{EditorInfo.class});
        hookBlurDecisionMethod(themeClass, "isLightBlurSceneForEditor",
                new Class<?>[]{EditorInfo.class});
        hookBlurDecisionMethod(themeClass, "isNonGaussianSkip",
                new Class<?>[]{boolean.class, boolean.class,
                        boolean.class});
        hookBlurDecisionMethod(themeClass, "applyBlurTheme",
                new Class<?>[]{boolean.class});
        hookBlurDecisionMethod(themeClass, "switchToNonGaussianTheme",
                new Class<?>[0]);
        hookBlurDecisionMethod(themeClass, "getBlurBackground",
                new Class<?>[]{View.class, boolean.class, float.class,
                        float.class, float.class, float.class});
    }

    private void hookBlurDecisionMethod(Class<?> themeClass, String name,
                                        Class<?>[] parameterTypes) {
        try {
            Method method = themeClass.getDeclaredMethod(name, parameterTypes);
            hook(method)
                    .setId("sogou-gauss-decision-" + name)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain)
                                throws Throwable {
                            EditorInfo editor = null;
                            if (parameterTypes.length == 1
                                    && chain.getArg(0) instanceof EditorInfo) {
                                editor = (EditorInfo) chain.getArg(0);
                                currentEditorPackage = editor.packageName;
                            }
                            if ("configTheme".equals(name) && editor != null) {
                                logBlurDecisionOnce(
                                        "enter:" + name + ":"
                                                + editor.packageName,
                                        "blur config enter pkg="
                                                + editor.packageName
                                                + ", inputType="
                                                + editor.inputType
                                                + ", imeOptions="
                                                + editor.imeOptions
                                                + ", materialBlurEnabled="
                                                + readMaterialBlurEnabled());
                            }

                            Object result = chain.proceed();
                            String value = formatBlurDecisionResult(result);
                            logBlurDecisionOnce(
                                    name + ":" + currentEditorPackage,
                                    "blur decision " + name
                                            + ", pkg=" + currentEditorPackage
                                            + ", result=" + value);
                            if ("configTheme".equals(name)
                                    || ("isBlurSupportedInternal".equals(name)
                                    && result instanceof Boolean)) {
                                MigrationRuntime.onEngineReady(
                                        MainHook.this,
                                        targetClassLoader);
                            }
                            return result;
                        }
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "blur decision hook skipped: " + name, t);
        }
    }

    private boolean logBlurDecisionOnce(String key) {
        return blurDecisionLogged.add(key);
    }

    private void logBlurDecisionOnce(String key, String message) {
        if (logBlurDecisionOnce(key)) {
            log(Log.INFO, TAG, message);
        }
    }

    private static String formatBlurDecisionResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof Boolean) {
            return result.toString();
        }
        return result.getClass().getName();
    }

    private Boolean readMaterialBlurEnabled() {
        if (materialBlurEnabled != null) {
            return materialBlurEnabled;
        }
        try {
            Class<?> type = Class.forName(
                    "yx", true, targetClassLoader);
            Object instance = type.getDeclaredMethod("a").invoke(null);
            Field field = type.getDeclaredField("d");
            field.setAccessible(true);
            materialBlurEnabled = field.getBoolean(instance);
        } catch (Throwable ignored) {
            materialBlurEnabled = null;
        }
        return materialBlurEnabled;
    }

    private static Class<?> findClipboardListener(ClassLoader classLoader) {
        for (String className : CLIPBOARD_LISTENER_CLASSES) {
            try {
                Class<?> candidate = Class.forName(
                        className, false, classLoader);
                if (findClipboardLimitMethod(candidate) != null) {
                    return candidate;
                }
            } catch (Throwable ignored) {
                // Try the next known obfuscated class name.
            }
        }
        return null;
    }

    private static Method findClipboardLimitMethod(Class<?> candidate) {
        for (Method method : candidate.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3
                    && params[0] == ClipData.class
                    && params[1] == ClipDescription.class
                    && params[2] == boolean.class
                    && method.getReturnType() == void.class) {
                return method;
            }
        }
        return null;
    }

    private static boolean matchesStringList(
            List<?> list, String[] expected) {
        if (list.size() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(list.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Method findMethodInHierarchy(
            Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(
                        name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // Continue through the superclass chain.
            }
        }
        throw new NoSuchMethodException(name);
    }

    void logRuntime(int priority, String message, Throwable throwable) {
        if (throwable == null) {
            log(priority, TAG, message);
        } else {
            log(priority, TAG, message, throwable);
        }
    }

    void logRuntime(int priority, String message) {
        logRuntime(priority, message, null);
    }
}
