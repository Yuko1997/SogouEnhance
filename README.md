# SogouEnhance

面向搜狗输入法官方版与 ColorOS OEM 版的 LSPosed 增强模块，以及配套的双进程用户数据迁移工具。

项目当前解决两类问题：

1. 解除 OEM 版搜狗输入法的部分输入限制，并改善模块后台唤醒时的用户体验。
2. 将官方版的用户词库、快捷短语和英文用户词库迁移到 OEM 版，避免手工重建私有词库格式。

> 本项目面向受控开发、迁移和验证环境，不是通用的一键输入法替代工具。执行数据迁移前应阅读版本范围、数据备份和回滚说明。

## 功能

### LSPosed 模块

- 解除 OEM 版搜狗剪贴板单条文本的 5000 字限制。
- 绕过 OEM 版 ColorOS 键盘高斯模糊的 `GAUSS_BLUR_EDITORS` 包名白名单。
- 通过模块私有 `ContentProvider` 在官方版和 OEM 版进程之间中转迁移数据。
- 屏蔽冷启动代理 Activity 弹出的“抱歉，该功能已下线”Toast。
- 通过 LSPosed 双进程通信完成迁移，不额外依赖 root Frida Server，也不要求用户手动导出文件。

### 词库迁移

- 迁移 `files/dict/v3new.uud` 中的中文及混合文本用户词条。
- 通过 OEM 输入法内核的 `0x0C` worker Job 持久化合并词库。
- 合并快捷短语，并由 OEM 实现生成 `qid`、`mtime` 和可见列表。
- 复制以下英文用户词库文件：

```text
files/foreign/user_dict/1/en_un.dat
files/foreign/user_dict/1/en_uml.dat
files/foreign/user_dict/1/en_ul.dat
files/foreign/user_dict/1/en_uel.dat
```

## 当前适配范围

| 角色 | Package | Version |
|---|---|---|
| 数据源 | `com.sohu.inputmethod.sogou` | `11.56` / `2151` |
| 数据目标 | `com.sohu.inputmethod.sogouoem` | `10.32.0722.2611161508` / `2736` |

模块和迁移脚本依赖搜狗内部混淆类及 worker Job；输入法升级后需要重新确认调用链和兼容性。

## 双进程迁移流程

```text
LSPosed 模块 UI
    -> 创建迁移任务
    -> 触发官方版导出词库、快捷短语和英文用户词库
    -> 写入模块私有 MigrationBrokerProvider
    -> 用户确认提交
    -> 触发 OEM 版领取任务
    -> OEM 输入法内核合并并持久化
    -> 返回不含词条正文的迁移结果
```

安全边界：

- Provider 只接受官方版、OEM 版和模块自身 UID。
- 文件名使用固定白名单，任务编号限制为安全字符集。
- 文件传输后按大小和 SHA-256 校验。
- OEM 只读取已经由用户提交的任务。
- 原始词条不经过网络，不写入模块日志或结果正文。

详细实现见 [lsposed-sogou-enhance/README.md](lsposed-sogou-enhance/README.md)。

## 验证结果

已在 CPH2449、Android 16、arm64-v8a 环境完成端到端验证：

| 项目 | 结果 |
|---|---|
| 中文及混合词库 | 官方版 21,259 条，OEM 基线 15,539 条，合并后 28,617 条 |
| 非 ASCII 并集 | 完整保留 |
| ASCII 直词 | 13 条按设计由英文用户词库路径处理 |
| 持久化 | 冷启动后词条数量和词典 MD5 保持不变 |
| 幂等性 | 重复合并未增加词条 |
| 快捷短语 | 7 条官方有效项与 4 条 OEM 有效项合并为 11 条 |
| 英文用户词库 | 4 个文件字节级复制成功，OEM 英文键盘可加载 |
| Toast 屏蔽 | 官方版和 OEM 版冷启动路径均已验证 |

完整结论和验证边界见 [reports/SogouEnhance-Migration-Validation-Report.md](reports/SogouEnhance-Migration-Validation-Report.md)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `lsposed-sogou-enhance/` | LSPosed 模块源码、资源、构建脚本和安装说明 |
| `migration/` | 独立 Frida 迁移脚本、格式比较工具和复现流程 |
| `reports/` | 面向交付的验证报告 |
| `analysis/` | 逆向分析脚本、DEX 分析材料和模糊探测工程 |
| `artifacts/validation-summary.json` | 不包含用户词条正文的验证摘要 |
| `artifacts/uud/uud-compare-report.json` | 用户词库集合比较结果 |
| `artifacts/*.png` | 模块 UI 与最终迁移结果截图 |

## 构建

### 环境要求

- Windows PowerShell
- JDK，包含 `javac`、`jar` 和 `keytool`
- Android SDK，包含 `build-tools` 和 `platforms`
- 模块依赖 `libs/libxposed-api-102.jar`

### 构建模块

```powershell
Set-Location .\lsposed-sogou-enhance
.\build.ps1
```

构建脚本依次执行：

```text
aapt2 compile/link
    -> javac
    -> jar
    -> d8
    -> aapt add
    -> zipalign
    -> apksigner
```

默认输出：

```text
lsposed-sogou-enhance/SogouEnhance-v1.2.1.apk
```

安装、LSPosed 作用域和界面操作步骤见 [lsposed-sogou-enhance/README.md](lsposed-sogou-enhance/README.md)。

## 数据安全

`apps/`、`data/`、原始 `.uud` 文件和快捷短语验证数据可能包含账号、Cookie、设备标识、剪贴板记录或用户词库正文。这些内容已在 [.gitignore](.gitignore) 中排除，禁止提交到版本库。

建议：

- 迁移前保存 OEM 词库、英文用户词库和快捷短语的回滚备份。
- 将完整设备备份存放在 Git 工作区之外的安全位置。
- 验证结束后删除解包后的 `data/` 和不再需要的原始用户数据。
- 提交或分享日志前检查账号、设备标识、私有路径和用户词条。

## 已知边界

- 英文 `en_*.dat` 已通过文件级复制和 OEM 引擎加载验证，但尚未实现逐词语义回读。
- 中文 UUD 合并不会导入 `pinyin == word` 的 ASCII 直词；这部分由英文用户词库路径覆盖。
- 迁移代码硬编码了当前版本的混淆类、Provider 触发方式和 worker Job，升级后需要重新适配。
- 独立 `migration/` 工具使用 root 启动的 Frida Server；LSPosed 模块路径本身不要求用户手动运行 Frida。
- 生产迁移前应冻结或关闭云词库同步，避免云端状态覆盖本地合并结果。

## 文档

- [LSPosed 模块说明](lsposed-sogou-enhance/README.md)
- [词库迁移复现工具](migration/README.md)
- [迁移验证报告](reports/SogouEnhance-Migration-Validation-Report.md)
