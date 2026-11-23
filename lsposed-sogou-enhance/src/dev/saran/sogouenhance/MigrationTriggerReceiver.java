package dev.saran.sogouenhance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

final class MigrationTriggerReceiver extends BroadcastReceiver {
    private final MainHook owner;
    private final ClassLoader targetClassLoader;
    private final boolean sourceRole;

    MigrationTriggerReceiver(
            MainHook owner, ClassLoader targetClassLoader,
            boolean sourceRole) {
        this.owner = owner;
        this.targetClassLoader = targetClassLoader;
        this.sourceRole = sourceRole;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (sourceRole) {
            MigrationSource.triggerNow(owner, targetClassLoader);
        } else {
            MigrationRuntime.triggerNow(owner, targetClassLoader);
        }
    }
}
