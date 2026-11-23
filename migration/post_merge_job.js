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
        const context = Java.use('android.app.ActivityThread').currentApplication();
        const IMEInterface = Java.use('com.sohu.inputmethod.engine.IMEInterface');
        const ime = IMEInterface.getInstance(context);
        const Bundle = Java.use('android.os.Bundle');
        const Job = Java.use('com.sohu.inputmethod.engine.Job');
        const job = Job.$new(0x0C, null, Bundle.$new());
        ime.callCoreWorker(job);
        emit('merge-job-result', { jobId: 0x0C, queued: true });
      } catch (e) {
        emit('merge-job-error', { error: '' + e, stack: e.stack || null });
      }
    });
  });
});
