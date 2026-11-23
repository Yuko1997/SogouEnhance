## Frida Server

**Frida Server 必须以 root 启动。** 使用普通 `adb shell` 启动会得到 shell UID，
无法正确 attach 目标，也不符合本项目调试约定。

推荐流程：

```powershell
adb -s <serial> push .\frida-server-arm64 /data/local/tmp/frida-server
adb -s <serial> shell su -c "chmod 0755 /data/local/tmp/frida-server"
```

在单独的持久终端中以前台 root 进程启动：

```powershell
adb -s <serial> shell su -c "/data/local/tmp/frida-server -l 127.0.0.1:27042"
```

确认是 root：

```powershell
adb -s <serial> shell su -c "pidof frida-server"
adb -s <serial> shell su -c 'cat /proc/$(pidof frida-server)/status | grep "^Uid:"'
```

连接：

```powershell
adb -s <serial> forward tcp:27042 tcp:27042
frida-ps -H 127.0.0.1:27042
```

结束并清理：

```powershell
adb -s <serial> forward --remove tcp:27042
adb -s <serial> shell su -c 'kill $(pidof frida-server)'
```

Frida 规则：

- 只使用与设备 ABI 匹配的 Frida Server。
- 不要从普通 shell UID 启动。
- Frida attach 失败时记录错误，不要反复强制重试导致宿主状态污染。
- 调试完成后停止 Frida Server、删除 adb forward，并清理 `/data/local/tmp`
  中本次生成的临时文件。