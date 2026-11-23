# 搜狗输入法定制增强

面向搜狗输入法官方版与 ColorOS OEM 版的 LSPosed 模块，包含输入增强和双进程词库迁移工具。

当前目标：

| 作用 | Package | Version |
|---|---|---|
| 数据源 | `com.sohu.inputmethod.sogou` | `11.56` / `2151` |
| 数据目标 | `com.sohu.inputmethod.sogouoem` | `10.32.0722.2611161508` / `2736` |

模块提供四项能力：

1. 解除 OEM 版搜狗剪贴板单条文本的 5000 字限制。
2. 绕过 OEM 版 ColorOS 键盘高斯模糊的 `GAUSS_BLUR_EDITORS` 包名白名单。
3. 从官方版 Hook 进程读取私有词库，经模块 Provider 中转，再由 OEM 版 Hook 进程合并。
4. 屏蔽冷启动代理 Activity 弹出的“抱歉，该功能已下线”Toast，不拦截其他 Toast。

## 双进程迁移架构

模块作用域同时包含：

```text
com.sohu.inputmethod.sogou
com.sohu.inputmethod.sogouoem
```

迁移全程不需要 Root，也不要求用户手动导出或选择文件。

### 1. 创建读取任务

用户从桌面打开模块 UI，点击“读取官方版数据”。模块应用在自己的私有目录创建任务，向官方版包发送定向触发广播；若进程未运行，还会通过官方版已导出的透明代理 Activity 在后台拉起主进程，再补发广播。

### 2. 官方版导出

官方版进程 Hook：

- `com.sohu.inputmethod.sogou.SogouIME.onStartInputView(EditorInfo, boolean)`
- `com.sogou.bu.ims.support.BaseInputMethodService.onStartInputView(EditorInfo, boolean)`

官方版进程完成 `Application.onCreate` 后会注册动态触发器。若进程仍在运行，收到广播即可立即开始导出；若进程已被系统回收，模块会自动唤醒主进程并重发广播，不需要用户切换键盘。导出开始后：

1. 调用官方版 `pa1.Lq(Context, Bundle)` 导出 `files/dict/v3new.uud`。
2. 校验 UUD 格式、记录数与 SHA-256。
3. 读取官方版 `files/shortcutphrases/basephrases_v2.json`。
4. 读取官方版 `files/foreign/user_dict/1` 下的四个英文词库文件。
5. 将数据写入模块私有 `MigrationBrokerProvider`。

原始词条只在官方版进程、模块 Provider 与 OEM 进程之间传递，不经过网络，也不写入模块日志或结果正文。

### 3. 用户确认提交

模块 UI 在前台每秒轮询一次状态，显示中文词条数、快捷短语数、英文文件数和完成状态。用户检查后点击“提交迁移”。

提交时模块会向 OEM 包临时授予本次任务 URI 的只读权限，并发送显式触发广播，避免要求 OEM 修改 Manifest 或获得全局包可见性。若 OEM 进程未运行，模块会使用同一套后台唤醒流程拉起主进程。

### 4. OEM 版合并

OEM 版进程 Hook 在键盘就绪后领取任务：

1. 从模块 Provider 拉取文件并再次验证大小与 SHA-256。
2. 备份 OEM 现有 `sgim_gd_usr.bin`、`v3new.uud`、四个英文词库和快捷短语 JSON。
3. 将 `v3new.uud` 原子写入 `files/dict/v3new.uud`。
4. 通过 `Job(0x0C, null, Bundle)` 调用 `IMEInterface.callCoreWorker` 持久化合并中文词库。
5. 复制英文词库文件。
6. 通过 `ShortcutPhraseServiceImpl.addQuickPhrase(String)` 合并快捷短语。
7. 将不含词条正文的结果写回模块 Provider。

`0x0C` 是持久化合并 Job。`0x0D` 属于另一条 `other.uud` 路径，不使用。

## 中转安全边界

模块应用声明：

```text
dev.saran.sogouenhance.migration
```

该 Provider：

- 只接受官方版、OEM 版和模块自身 UID。
- 文件名使用固定白名单。
- 请求编号限制为安全字符集。
- 官方版只能写入当前 `requested` / `collecting` 任务。
- OEM 只能读取已由用户提交的任务。
- 文件写入后按大小与 SHA-256 校验。
- OEM 的 URI 权限在任务完成后撤销。

## 数据备份

OEM 私有目录：

```text
files/migration/backups/<request-id>/
```

备份仅在 OEM 执行阶段创建，包含迁移前存在的中文用户词典、`v3new.uud`、英文词库和快捷短语 JSON。

## 剪贴板 5000 字限制

原版 OEM `classes2.dex` 中，`SystemClipboardListener` 的混淆类为 `y17`。模块只在 `y17.a` 执行期间临时 Hook `String.length()`，把超过 5000 的返回值改为 5000，使原方法走未超限分支；方法返回后立即卸载临时 Hook。

## 高斯模糊白名单

OEM 版 `ThemeCustomServiceImpl.isBlurSupportedInternal(EditorInfo)` 最终使用 `GAUSS_BLUR_EDITORS.contains(editorInfo.packageName)`。模块只拦截 `java.util.Arrays$ArrayList.contains(Object)`，并且仅在内部数组与 `ThemeCustomServiceImpl.GAUSS_BLUR_EDITORS` 完全一致时返回 `true`。

系统主题、系统模糊开关、深浅色和模拟主题等前置条件仍然生效。

## 冷启动 Toast

官方版和 OEM 的 `com.sogou.androidtool.ShortCutProxyActivity.onCreate(Bundle)` 会无条件显示“抱歉，该功能已下线”，然后结束 Activity。模块后台唤醒时仍会使用该 Activity 拉起输入法进程，但只在它的 `onCreate` 执行线程上屏蔽 `Toast.show()`，因此保留原有冷启动行为，也不会影响搜狗其他页面或提示。

## 构建

模块使用 `libxposed/api:102`，构建脚本为 `build.ps1`。

```powershell
.\build.ps1
```

输出：

```text
SogouEnhance-v1.2.1.apk
```

构建链：

```text
aapt2 compile/link + R.java
  -> javac
  -> jar
  -> d8
  -> aapt add
  -> zipalign
  -> apksigner
```

## 安装与作用域

1. 安装 `SogouEnhance-v1.2.1.apk`。
2. 在 LSPosed Manager 中启用模块。
3. 确认作用域同时包含官方版和 OEM 版。
4. 强制停止两个搜狗输入法进程。
5. 打开模块 UI，点击“读取官方版数据”。
6. 官方版进程会由广播或后台代理 Activity 自动拉起。
7. 确认统计后点击“提交迁移”。
8. OEM 版进程会由广播或后台代理 Activity 自动拉起。
9. 保持或返回模块 UI，结果会自动刷新。

若自动唤醒长时间无响应，界面会显示“打开输入法设置”作为兜底入口。

## 日志

Tag：

```text
SogouEnhance
```

双端迁移日志：

```text
official source input-view hook installed: <class>
migration trigger receiver registered: source|target
official dictionary export requested: <request-id>
official dictionary export ready: <request-id>
migration task claimed: <request-id>
migration task result: <request-id> status=<status>
```

若系统 `logd` 不工作，请同时查看：

```text
/data/adb/lspd/log/modules_<timestamp>.log
```

## 兼容性与边界

- 当前版本硬编码官方版 `pa1.Lq(Context, Bundle)`、OEM 版 `0x0C` 合并路径，以及两端用于冷启动的 `com.sogou.androidtool.ShortCutProxyActivity`。
- 搜狗升级后，需要重新确认 `SogouIME`、`BaseInputMethodService`、`pa1`、`Job`、`ShortcutPhraseServiceImpl` 和英文词库目录。
- 英文 `en_*.dat` 仍为文件级复制，尚未解析私有记录格式做逐词回读。
- 中文 UUD 合并不会导入 `pinyin == word` 的 ASCII 直词；这些条目由英文用户词库路径覆盖。
- 生产迁移前应冻结或关闭云词库同步。
- 当前验证覆盖构建、APK 结构、双端 scope、Provider 导出、目标 DEX 签名、双端冷启动自动唤醒、冷启动 Toast 屏蔽和 CPH2449 端到端迁移。

## 验证报告

```text
../reports/SogouEnhance-Migration-Validation-Report.md
```
