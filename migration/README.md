# Sogou User Dictionary Migration

This toolkit migrates user dictionary data from the official Sogou IME package to the OEM package without modifying either APK.

## Validated Scope

- Source package: `com.sohu.inputmethod.sogou` 11.56 (versionCode 2151)
- Destination package: `com.sohu.inputmethod.sogouoem` 10.32.0722.2611161508 (versionCode 2736)
- Device: CPH2449, Android 16, arm64-v8a, rooted
- Transport: root-owned `frida-server` 17.18.0 on `127.0.0.1:27042`

## Critical Corrections

- Use worker job `0x0C` for merging `files/dict/v3new.uud`. Job `0x0D` is the separate `other.uud` path.
- Calling `mergeUUD2UsrDict()` directly only changes the live process. It does not persist the merged dictionary.
- The OEM input engine must be initialized before running the migration. Open a text field once so the Sogou keyboard is visible.
- The UUD merge covers non-ASCII user dictionary entries. Direct ASCII entries with `pinyin == word` are not imported by the Chinese UUD merge and require the separate English user dictionary files.

## Files

- `export_official_uud.js`: exports official `files/dict/v3new.uud`.
- `export_oem_uud.js`: exports the OEM dictionary for baseline and post-merge comparison.
- `post_merge_job.js`: queues worker job `0x0C`; this is the persistent merge path.
- `migrate_shortcut_phrases.js`: reads a copied official `basephrases_v2.json` and adds non-deleted phrases through `ShortcutPhraseServiceImpl.addQuickPhrase`.
- `compare_uud.py`: compares UUD records as a set without printing user words.
- `inspect_uud_delta.py`: summarizes missing records without printing their contents.

## Procedure

1. Install both APKs and restore their backup data.
2. Start both apps and open a text field once so the Sogou input engine is initialized.
3. Export the official UUD:

   ```powershell
   frida -H 127.0.0.1:27042 -p <official-pid> -q -t 8 -l migration/export_official_uud.js
   ```

4. Export the OEM baseline UUD:

   ```powershell
   frida -H 127.0.0.1:27042 -p <oem-pid> -q -t 8 -l migration/export_oem_uud.js
   ```

5. Copy the official `v3new.uud` to the OEM private dictionary directory and fix ownership, mode, and SELinux context.
6. Queue the persistent merge job:

   ```powershell
   frida -H 127.0.0.1:27042 -p <oem-pid> -q -t 8 -l migration/post_merge_job.js
   ```

7. Wait for the worker thread to finish, export `v3new.uud` again, and compare:

   ```powershell
   python migration/compare_uud.py official.uud oem-baseline.uud oem-merged.uud --json-out report.json
   ```

8. Copy these files from the official package to the same OEM path:

   ```text
   files/foreign/user_dict/1/en_un.dat
   files/foreign/user_dict/1/en_uml.dat
   files/foreign/user_dict/1/en_ul.dat
   files/foreign/user_dict/1/en_uel.dat
   ```

9. Copy the official shortcut phrase JSON to an OEM-readable staging path, then run:

   ```powershell
   frida -H 127.0.0.1:27042 -p <oem-pid> -q -t 8 -l migration/migrate_shortcut_phrases.js
   ```

10. Force-stop and restart the OEM app, export once more, and verify that the merged count and MD5 are unchanged.

## Validation Result

Observed on the connected CPH2449:

- Official UUD: 21,259 records.
- OEM baseline UUD: 15,539 records.
- OEM merged UUD: 28,617 records.
- Expected set union: 28,630 records.
- Missing from the Chinese UUD result: 13 ASCII-only records; non-ASCII missing count: 0.
- Repeated merge after restart: 28,617 records with the same dictionary MD5.
- Shortcut phrases: official 7 valid + OEM 4 valid -> 11 unique, with no missing, extra, or duplicate entries.

The English dictionary files were copied byte-exactly and the OEM English keyboard loaded normally. Per-entry readback from the proprietary `en_*.dat` files is not implemented, so that remains the only narrower verification boundary.
