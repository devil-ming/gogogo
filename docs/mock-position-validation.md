# 主页模拟位置验证

主页的“模拟”标记来自 ServiceGo 的 WGS84 目标坐标，经本地转换后以 GCJ02 显示。它不依赖逆地址解析配额，也不代表微信已经接受该位置。

## 预期行为

1. 未启动模拟时不显示“模拟”标记或模拟状态条。
2. 选点并启动后，地图出现独立的“模拟”圆点，状态条显示经纬度。
3. 使用摇杆移动后，标记与坐标约每 500ms 更新一次；地图不持续强制居中，仍可自由拖动。
4. 选择另一个待传送位置时，选点标记和当前模拟位置同时显示。点击传送后，仅移除选点标记。
5. 返回桌面再进入主页，或重建主页后，重新连接仍在运行的服务并恢复标记。返回历史记录选点时，保留待传送位置的地图视野。
6. 点击状态条或地图回中按钮，回到当前模拟位置。
7. 明确停止模拟后，标记和状态条消失，恢复地图定位图层。
8. 主页面销毁只解绑，不停止用户开启的前台服务；服务退出时清除运行状态。
9. 推送循环或所有通路的成功写入超过 5 秒未更新，状态条显示“模拟位置待更新”，不继续显示“正在模拟”。

## 定位波动排查

日志每约 10 秒输出 SERVICEGO heartbeat，包含最近系统写入与 GMS 写入距今的毫秒数，-1 表示尚无成功写入。各通路失败包含异常类型和原因，同一路径最多每 10 秒输出一次。服务启动必须先收到有效目标坐标，不会先注入内置默认位置。GMS 写入的异步失败也会记录。

真机验证时，记录微信失效的时间，并对照系统/GMS 成功写入时间及影梭测试页位置更新时间。SDK 写入成功仅说明对应 API 接受请求，不保证其他应用采用该位置。

### 后台停摆与 WakeLock

前台服务阻止进程被回收，但不会阻止 CPU 休眠。屏幕熄灭后，HandlerThread 的 33ms tick 会被挂起，相邻两次 tick 的间隔远超预期，此时系统内的 mock 位置变成过期数据，读取定位的应用会回退到 WiFi/基站并拿到真实位置。

模拟运行期间 ServiceGo 持有 `PARTIAL_WAKE_LOCK`（不点亮屏幕），释放时机为 `onDestroy`。相邻两次 tick 超过 5 秒会在日志中输出：

```
SERVICEGO: loop stalled <挂起毫秒数>ms; wakeLockHeld=<true|false>
```

排查时按该行计数：修复后持锁应不再出现，若 `wakeLockHeld=false` 则说明锁未获取成功（PowerManager 不可用或权限被拒），需查同段日志中的 `acquireWakeLock failed`。部分定制 ROM 会在持锁情况下仍冻结进程，此时需要把应用加入电池优化白名单。

### 心跳与回读字段

心跳每 10 秒一行，除原有的写入时间外追加回读与环境信息：

```
SERVICEGO: heartbeat systemAgeMs=.. gmsAgeMs=.. readback[gps=.. net=.. fused=..] env[wifi=.. screen=..]
```

`readback` 对每个 Provider 调用 `getLastKnownLocation` 并与当前 mock 目标比对，字段含义：

| 字段 | 含义 |
|------|------|
| `null` | 该系统内根本没有该 Provider 的位置 |
| `age=..ms` | 系统记录的位置时间距今多久，远大于一个 tick 说明读到的是旧位置 |
| `dev=..m` | 与当前 mock 目标的偏差米数，接近 0 才说明该 Provider 存的就是目标位置 |
| `mock=true/false` | 系统是否仍标记为测试位置 |

`setTestProviderLocation` 不抛异常只代表调用被接受，不能证明位置已进入系统；`readback` 才是直接证据。若 `dev` 持续很大或 `mock=false`，说明 mock 未真正生效。

熄屏与亮屏会各输出一行 `screen off/on; loop=lastTickAgeMs=.. wakeLockHeld=..`，用于判断冻结是否跟随熄屏以及冻结发生的准确时刻。

### GMS 写入闸门超时

`setLocationFusedClient` 只允许一个 `setMockLocation` 请求在途，避免 GMS 过载。但原实现没有超时：一旦某次回调迟迟不返回，闸门会一直关闭，后续写入全部被静默丢弃。

日志实测该情况会周期性出现，`gmsAgeMs` 从约 6 秒涨到 26 秒再回落（694 次心跳中有 25 次超过 5 秒），而同期 `systemAgeMs` 始终为 0，说明仅 GMS 通路中断、`LocationManager` 通路正常。

中断期间，依赖 GMS fused 的读取方会退回 WiFi/基站并拿到真实位置，表现为"同一功能有时是模拟位置、有时是真实位置"。

现在超过 `FUSED_WRITE_TIMEOUT_MS`（1 秒）仍无回调时，新写入会强制接管并输出：

```
SERVICEGO: gms/write timeout, taking over after <毫秒>ms
```

迟到的旧回调通过 `mFusedWriteStartMs` 比对，不会误清新请求的 pending 标志。

### 精度取值与运动学一致性

注入的 `Location.setAccuracy()` 使用真实量级（GPS 8 米、网络 35 米）。此前传入的 `Criteria.ACCURACY_FINE`/`ACCURACY_COARSE` 是精度*等级*常量（值为 1/2），当作米数使用会让上报精度物理上不可信，融合定位可能因此降低该来源的权重。

瞬移不再一律插值。`snapshot()` 的处理分三档：

| 距离 | 行为 |
|------|------|
| ≤ 0.5m | 已在目标，速度上报 0 |
| 0.5m ~ 300m | 按 `TELEPORT_MAX_SPEED_MPS`（80 m/s）平滑过渡，速度取实际位移/时间 |
| > 300m | **直接落点**，不做插值，日志输出 `teleport jump <距离>m (interpolation skipped)` |

300 米以上的传送若仍按 80 m/s 过渡，17 公里需要 212 秒，期间持续上报 288km/h 的高速移动本身就是异常数据，且用户必须长时间等待才能到达目标。用户对"传送"的预期是立即到达，优先级高于运动学完美。插值仅用于消除短距离跳变。

心跳中的 `render` 字段用于观察过渡状态：

```
target[wgs=lat,lng alt=..] render[lat=..,lng=.. rem=..m v=..m/s]
```

`rem` 是距目标的剩余距离，`v` 是本帧上报速度。正常情况下 `v` 不应为 0 而 `rem` 很大；到达后 `rem≈0` 且 `v=0`。若长距离传送后 `v` 持续为 80，说明落点未生效。

## 自动化验证

MockPositionTest 覆盖目标尚未推送、系统/GMS 单通路成功、Provider 写入停滞、推送循环停滞、5 秒边界及未来时间戳。完整编译命令：bash ./gradlew :app:assembleDebug :app:testDebugUnitTest。
