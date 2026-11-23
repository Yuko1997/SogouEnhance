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
        const values = {};
        for (const fieldName of ['mHandler', 'mSogouCoreWorker', 'mEngine']) {
          try {
            const value = ime[fieldName].value;
            values[fieldName] = value === null ? null : '' + value;
          } catch (e) {
            values[fieldName] = '<error: ' + e + '>';
          }
        }
        emit('engine-state-result', {
          ime: '' + ime,
          values: values,
          coreInfo: ime.getCoreInfo()
        });
      } catch (e) {
        emit('engine-state-error', { error: '' + e, stack: e.stack || null });
      }
    });
  });
});
