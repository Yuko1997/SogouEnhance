'use strict';

function emit(kind, data) {
  const payload = Object.assign({ kind: kind }, data || {});
  send(payload);
  console.log(JSON.stringify(payload));
}

setImmediate(function () {
  Java.perform(function () {
    setImmediate(function () {
      try {
        const ActivityThread = Java.use('android.app.ActivityThread');
        const context = ActivityThread.currentApplication();
        if (context === null) {
          throw new Error('ActivityThread.currentApplication() returned null');
        }

        const Bundle = Java.use('android.os.Bundle');
        const bundle = Bundle.$new();
        bundle.putBoolean('upload_user_dict_check_md5', false);
        bundle.putBoolean('upload_user_dict_need_merge', false);

        const SyncUtil = Java.use('pa1');
        const syncUtil = SyncUtil.$new();
        const result = SyncUtil.Lq
          .overload('android.content.Context', 'android.os.Bundle')
          .call(syncUtil, context, bundle);

        emit('export-result', {
          result: result,
          count: bundle.containsKey('upload_user_dict_count')
            ? bundle.getInt('upload_user_dict_count')
            : null,
          md5: bundle.containsKey('upload_uud_md5')
            ? '' + bundle.getString('upload_uud_md5')
            : null
        });
      } catch (e) {
        emit('export-error', { error: '' + e, stack: e.stack || null });
      }
    });
  });
});
