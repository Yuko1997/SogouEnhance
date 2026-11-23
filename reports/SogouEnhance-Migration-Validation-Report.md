# 搜狗输入法官方版到 OEM 版用户词库迁移验证报告

## 1. 报告信息

| 项目 | 内容 |
|---|---|
| 报告日期 | 2026-11-23 |
| 测试环境 | CPH2449 开发机 |
| Android 版本 | Android 16 / SDK 36 |
| 设备架构 | arm64-v8a |
| Root 状态 | 可用 |
| 动态分析 | Frida host 17.18.0；设备侧 root frida-server 17.18.0 |
| 迁移形式 | 数据迁移，不修改 APK |
| 源版本 | 搜狗输入法 11.56 |
| 目标版本 | 搜狗输入法定制版 10.32.0722.2611161508 |

## 2. 结论摘要

本次验证确认：通过 OEM 输入法内核自身的 UUD 和快捷短语接口，可以完成官方版到 OEM 版的用户词库迁移，无需手工重建 SGPU/SGOU 二进制记录，也无需覆盖整个 APK。

核心结论如下：

1. 中文和混合文本用户词条可完整合并；合并结果在语义集合上与源、目标基线并集一致。
2. 原始方案中的 worker Job 编号需要修正：持久化合并应使用 `0x0C`，不是 `0x0D`。
3. 直接调用 `mergeUUD2UsrDict()` 只改变当前进程内存，不能作为最终交付路径。
4. 通过 `0x0C` worker Job 合并后，`sgim_gd_usr.bin` 会落盘；冷启动和重复合并均通过。
5. UUD 通道未纳入 13 条 `pinyin == word` 的 ASCII 直词；这些条目需要由单独英文用户词库路径处理。
6. 快捷短语通过 `ShortcutPhraseServiceImpl.addQuickPhrase(String)` 合并，最终集合精确等于双方有效条目的并集。

总体验收结论为：

- 中文/混合用户词库：`PASS`
- 持久化与幂等性：`PASS`
- 快捷短语：`PASS`
- 英文用户词库：`PASS_WITH_BOUNDARY`
- 整体方案：`PASS_WITH_BOUNDARY`

英文边界的具体含义是：4 个官方英文用户词库文件已按字节复制到 OEM 对应目录，OEM 英文键盘可正常加载；但尚未解析 `en_*.dat` 的私有记录格式进行逐词回读。

## 3. 输入工件与设备部署

### 3.1 APK 与版本

| 项目 | 官方版 | OEM 版 |
|---|---|---|
| Package | `com.sohu.inputmethod.sogou` | `com.sohu.inputmethod.sogouoem` |
| versionName | `11.56` | `10.32.0722.2611161508` |
| versionCode | `2151` | `2736` |
| APK SHA-256 | `952b3cade4db3b2cd4841a6ca8c1c63b9ba39141354c6b46cb7df1f82ca47a21` | `87e92697b9e9be05ba26eed2d412ac936ca8ea3d3aa556e5b4b986c405176c9d` |

两个 APK 已同时安装，设备侧 `base.apk` 哈希与本地备份一致。

### 3.2 数据恢复

分别恢复以下备份：

- `user.tar`
- `data.tar`
- `user_de.tar`

恢复后执行：

- UID/GID 校正
- 权限校正
- `restorecon` SELinux 标签恢复

官方版与 OEM 版 `files/dict` 的关键文件哈希均与备份逐项一致。

### 3.3 输入法初始化

验证期间确认：

- OEM 输入法服务可启动。
- Sogou 键盘可在真实文本输入框中显示。
- 默认输入法最终设置为 `com.sohu.inputmethod.sogouoem/.SogouIME`。
- 当前键盘处于可见状态。

## 4. UUD 数据格式

`v3new.uud` 是 UTF-16LE 文本文件，不是不可读的私有二进制容器。

文件结构：

```text
FormatVersion=v2.0
<pinyin_key><TAB><word><TAB><frequency><TAB><optional_metadata>
...
```

特征：

- 首行为格式版本。
- 中文词条以方括号拼音键开头。
- 英文直词可使用普通 ASCII 字段。
- 每条记录至少包含拼音键和词条两列。
- 实测文件无重复键、无解析异常行。

## 5. 关键调用链与修正

### 5.1 官方版导出

官方版使用：

```text
pa1.Lq(Context, Bundle)
```

内部调用：

```text
IMEInterface.checkUploadUsrDict(byte[], boolean, boolean, boolean,
                                byte[], int, boolean)
```

实测结果：

- 返回：`true`
- 词数：`21259`
- 内核词典 MD5：`5201d28ac1412b43908a33898294475c`
- 输出文件：1,081,030 字节
- 文件 SHA-256：`9568c7c64e438ca93063d10836e1d395bbaab2575b30b6cd71ee7bd1e16850d5`

### 5.2 OEM 导出

OEM 使用：

```text
cg5.b(Context, Bundle)
```

该入口用于导出目标是：

- 词数
- 内核词典 MD5
- `files/dict/v3new.uud`

### 5.3 错误路径与修正

最初推断使用 worker Job `0x0D` 执行持久化合并。该推断已被否定。

实际确认：

| Job | 行为 |
|---|---|
| `0x0C` | 合并 `v3new.uud` 到用户词库，并持久化 |
| `0x0D` | 处理另一条 `other.uud` / `buildOtherUsrDict` 路径，不适用于本次载荷 |

直接调用 `mergeUUD2UsrDict()` 时：

- 当前进程内导出可看到合并后的 28,617 条。
- `sgim_gd_usr.bin` 哈希未改变。
- 冷启动后回到 OEM 基线。

因此直接调用属于临时内存效果，不能作为正式迁移入口。

正确路径：

```text
Job job = new Job(0x0C, null, new Bundle())
IMEInterface.getInstance(context).callCoreWorker(job)
```

使用该路径后：

- `sgim_gd_usr.bin` 从 741,140 字节变为 1,063,640 字节。
- 文件 SHA-256 变为 `7a67288ffb2f0f5035592e66b6a4d489196ce0ce73ec9b4635578028c194871f`。
- 冷启动后仍保持合并结果。

## 6. 合并结果

### 6.1 词数

| 数据集 | 记录数 | 文件大小 | 文件 SHA-256 |
|---|---:|---:|---|
| 官方 11.56 | 21,259 | 1,081,030 | `9568c7c64e438ca93063d10836e1d395bbaab2575b30b6cd71ee7bd1e16850d5` |
| OEM 基线 | 15,539 | 633,526 | `8072b2f4bb5a509d59b74c8694ea352806ca0da63b5ff2541b1ba3c79ac90d01` |
| OEM 合并后 | 28,617 | 1,400,268 | `332ab21b72d447b6db0b2cac5647b570a406e5a76c3209aa519315733ed5069a` |

理论并集：

```text
21,259 + 15,539 - 8,168 = 28,630
```

其中 8,168 条为双方相同的 `(拼音键, 词条)`。

### 6.2 集合比较

| 指标 | 结果 |
|---|---:|
| 理论并集 | 28,630 |
| 实际合并 | 28,617 |
| 额外词条 | 0 |
| 中文/混合词条缺失 | 0 |
| ASCII 直词缺失 | 13 |

13 条缺失记录的共同特征：

- `pinyin == word`
- 全部为 ASCII
- 全部来自官方 UUD
- 不属于中文拼音词条

结论：

- 中文和混合文本用户词条：完整并集。
- 13 条英文直词：不由中文 UUD worker Job 导入，应由英文用户词库路径覆盖。

### 6.3 持久化与幂等性

| 测试 | 记录数 | 内核 MD5 |
|---|---:|---|
| 首次合并 | 28,617 | `92733692845bf7916cafd80ee91a518b` |
| 冷启动后 | 28,617 | `92733692845bf7916cafd80ee91a518b` |
| 再次合并后 | 28,617 | `92733692845bf7916cafd80ee91a518b` |

结论：

- 落盘成功。
- 冷启动后保持。
- 重复合并不再增加词数。
- 词典内容 MD5 保持稳定。

## 7. 英文用户词库

已复制以下文件：

```text
files/foreign/user_dict/1/en_un.dat
files/foreign/user_dict/1/en_uml.dat
files/foreign/user_dict/1/en_ul.dat
files/foreign/user_dict/1/en_uel.dat
```

| 文件 | SHA-256 |
|---|---|
| `en_un.dat` | `14f79af249730b836663b4d8516236aa0d6c7781f173e39bbae33fa2c5d24ebd` |
| `en_uml.dat` | `0b54f89d5f5a6d3c909da7e1c2ab35f5a12d3d8748bec2de583ab3aafde7bb6a` |
| `en_ul.dat` | `4005d104945fa00e76e523c56ffee9c22fa3a56235eefd0b49adcc10166380a3` |
| `en_uel.dat` | `ea1915045f5a9ed4c77f76493f7643c17081ee92f829e47a3fd600161d845a84` |

验证结果：

- 复制后的哈希与官方版一致。
- OEM 英文键盘可正常加载。
- 冷启动后文件仍存在。

边界：

- 尚未逆向 `en_*.dat` 的记录格式。
- 因此英文侧目前是“文件级兼容与引擎加载已验证”，不是“逐词语义回读已验证”。

## 8. 快捷短语迁移

### 8.1 数据统计

| 数据集 | 总条目 | 有效条目 | 删除墓碑 |
|---|---:|---:|---:|
| 官方版 | 12 | 7 | 5 |
| OEM 基线 | 4 | 4 | 0 |

### 8.2 迁移接口

使用：

```text
ShortcutPhraseServiceImpl.addQuickPhrase(String)
```

由 OEM 自行生成：

- `qid`
- `mtime`
- `visibleList`

不直接覆盖或拼接 JSON。

### 8.3 验证结果

| 指标 | 结果 |
|---|---:|
| 官方有效 | 7 |
| OEM 基线有效 | 4 |
| 理论并集 | 11 |
| 合并后有效 | 11 |
| 缺失 | 0 |
| 额外 | 0 |
| 重复 | 0 |
| 冷启动后有效 | 11 |

最终 OEM 快捷短语 JSON：

- 大小：1,600 字节
- SHA-256：`2b7f072df5a22cd2d79643e68f83c523fd8ff529abb58db1d90b408dd1f9b1ef`

## 9. 安全与数据保护

本次操作遵循以下约束：

- 不修改原始 APK。
- 不手工写 SGPU/SGOU 记录。
- 不整目录覆盖 OEM 用户词库。
- 不迁移账号、云同步状态和设备标识。
- 测试前保存 OEM 词库基线。
- 所有设备侧文件操作均校正 UID、权限和 SELinux context。
- 报告和验证输出不包含实际用户词条、快捷短语正文、账号或设备标识。

## 10. 回滚

迁移前备份：

- 文件：`artifacts/backups/oem-dict-before-migration.tgz`
- SHA-256：`7fb895fc91e23d7c79afe06152a97add2e50fda16d4be1797fc45670c6bc4b01`

迁移后完整备份：

- 文件：`artifacts/backups/oem-migrated-final.tgz`
- SHA-256：`98bfc2a618cb8967c7a51298d80567941c3e6c99d9ae6f466689d72c7376311e`

回滚应恢复：

- `files/dict`
- `files/foreign/user_dict`
- `files/shortcutphrases/basephrases_v2.json`

恢复后重新执行所有权、权限和 SELinux 标签校正。

## 11. 复现工件

正式迁移目录：

- `migration/README.md`
- `migration/export_official_uud.js`
- `migration/export_oem_uud.js`
- `migration/post_merge_job.js`
- `migration/migrate_shortcut_phrases.js`
- `migration/compare_uud.py`
- `migration/inspect_uud_delta.py`

验证证据：

- `artifacts/validation-summary.json`
- `artifacts/uud/official-11.56-v3new.uud`
- `artifacts/uud/oem-10.32-baseline-v3new.uud`
- `artifacts/uud/oem-10.32-job-merged-v3new.uud`
- `artifacts/uud/uud-compare-report.json`
- `artifacts/shortcut/oem-basephrases-final.json`
- `artifacts/screenshots/sogou-final.png`

## 12. 剩余风险

1. 英文用户词库尚未做逐词语义回读。
2. 未验证长时间云端同步后的最终稳定性；生产迁移前应关闭云词库同步，或在隔离网络环境中完成后再开启。
3. 迁移工具依赖 root 和 Frida，适用于受控开发机或一次性迁移环境，不属于普通无 root 用户工具。
4. 后续 OEM 大版本升级后，应重新确认 `0x0C` Job、UUD 格式和英文词库版本目录。

## 13. 最终结论

本次最终方案在开发机上已完成端到端验证：

- 无需改 APK。
- 中文/混合用户词库无损合并。
- 落盘、冷启动和幂等性通过。
- 快捷短语并集精确通过。
- 英文用户词库文件级迁移通过，逐词验证保留边界。

因此，方案可以作为正式迁移方案使用，但应明确标注英文侧验证边界，并在生产迁移前关闭或冻结云同步。
