'use strict';

const OFFICIAL_JSON = '/data/user/0/com.sohu.inputmethod.sogouoem/files/migration/official-basephrases_v2.json';
const OEM_JSON = '/data/user/0/com.sohu.inputmethod.sogouoem/files/shortcutphrases/basephrases_v2.json';

function emit(kind, data) {
  const payload = Object.assign({ kind: kind }, data || {});
  send(payload);
  console.log(JSON.stringify(payload));
}

function readUtf8(path) {
  const fis = Java.use('java.io.FileInputStream').$new(path);
  const reader = Java.use('java.io.BufferedReader').$new(
    Java.use('java.io.InputStreamReader').$new(fis, 'UTF-8')
  );
  const builder = Java.use('java.lang.StringBuilder').$new();
  try {
    let line;
    while ((line = reader.readLine()) !== null) {
      builder.append(line);
      builder.append('\n');
    }
  } finally {
    reader.close();
  }
  return '' + builder.toString();
}

function visiblePhrases(path) {
  const root = Java.use('org.json.JSONObject').$new(readUtf8(path));
  const groups = root.getJSONObject('data').getJSONArray('list');
  const result = [];
  for (let groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
    const group = groups.getJSONObject(groupIndex);
    if (group.optInt('is_del', 0) !== 0) {
      continue;
    }
    const phrases = group.optJSONArray('list');
    if (phrases === null) {
      continue;
    }
    for (let phraseIndex = 0; phraseIndex < phrases.length(); phraseIndex++) {
      const phrase = phrases.getJSONObject(phraseIndex);
      if (phrase.optInt('is_del', 0) !== 0) {
        continue;
      }
      const content = phrase.optString('content', '');
      if (content.length !== 0) {
        result.push(content);
      }
    }
  }
  return result;
}

setImmediate(function () {
  Java.perform(function () {
    setImmediate(function () {
      try {
        const official = visiblePhrases(OFFICIAL_JSON);
        const existing = visiblePhrases(OEM_JSON);
        const seen = {};
        for (let i = 0; i < existing.length; i++) {
          seen[existing[i]] = true;
        }

        const Service = Java.use('com.sogou.shortcutphrase.ShortcutPhraseServiceImpl');
        const service = Service.$new();
        let added = 0;
        let skipped = 0;
        let failed = 0;

        for (let i = 0; i < official.length; i++) {
          const content = official[i];
          if (seen[content]) {
            skipped++;
            continue;
          }
          let ok = false;
          try {
            ok = service.addQuickPhrase
              .overload('java.lang.String')
              .call(service, content);
          } catch (e) {
            ok = false;
          }
          if (ok) {
            added++;
            seen[content] = true;
          } else {
            failed++;
          }
        }

        emit('shortcut-migration-result', {
          officialValid: official.length,
          oemExistingValid: existing.length,
          added: added,
          skipped: skipped,
          failed: failed
        });
      } catch (e) {
        emit('shortcut-migration-error', { error: '' + e, stack: e.stack || null });
      }
    });
  });
});
