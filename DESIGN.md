# HyperOS Swipe Gesture — 架构设计文档与实机逆向对齐手册

本文档记录 `HyperOS-swipe-gesture` 的完整设计决策、对参考项目 `hyper-gesture` v1.6.7 的根因审计结论，以及针对 **小米 17 / HyperOS 4.0.6.0（Android 17 / SDK 37）** 的本地实机逆向与落地步骤。

---

## 1. 参考项目 `hyper-gesture`（v1.6.7）问题根因审计

通过对 `https://gitee.com/lsdkjf/hyper-gesture`（v1.6.7）源码逐行排查，已确认你在小米 17（HyperOS 4.0.6.0）上遇到「偶发重启」与「侧滑返回穿透」的直接根因：

### 1.1 导致系统偶发重启（`system_server` 崩溃 / Watchdog）的原因

1. **API 102 `system_server` 内混用旧版 Xposed 82 API（`NoClassDefFoundError` 风险）**：
   - `ModernSystemGestureHook` 跑在 `libxposed` API 102 的 `system_server` 中，但直接调用了导入 `de.robv.android.xposed.XposedBridge` / `XposedHelpers` 的 `TaskHelper`、`PreviewHelper`、`RingPreviewHelper`、`OverlayContextHelper` 与 `HgLog`。
   - 一旦进入异常分支（如 `PreviewHelper.showPreview` / `hidePreview` 的 `catch` 块调用 `XposedBridge.log`）或反射调用 `XposedHelpers`，在纯 API 102 环境下会抛出未捕获的 `NoClassDefFoundError`，直接击穿 `system_server` 关键线程引发重启。
2. **在 `system_server` 关键输入/显示线程内执行同步跨进程 IPC 与 UI 挂载**：
   - 每次边缘 `ACTION_DOWN` 都在 `SystemGesturesPointerEventListener.mHandler` 线程**同步**调用 `ContentResolver.query("content://com.example.hypergesture.settings/prefs")`；若模块进程正被 HyperOS 墓碑（Millet）冻结或发生 AMS/WMS 锁竞争，极易阻塞 `system_server` 触发 Watchdog 重启。
   - 每次状态切换都同步执行 `Settings.Global.putLong(...)` 写系统设置。
   - 直接在 `system_server` 中调用 `createPackageContext(..., CONTEXT_INCLUDE_CODE)`、同步加载应用图标并 `WindowManager.addView(..., TYPE_APPLICATION_OVERLAY)`，将 UI 渲染与窗口生命周期风险直接暴露在系统框架进程内。
   - 同时 Hook 了高频热路径 `InputManagerService.filterInputEvent(InputEvent, int)`，在 Native 输入线程对全机所有触摸事件复制并投递 `MotionEvent`。

### 1.2 导致系统侧滑返回未被屏蔽（当前应用被误返回/退出）的原因

1. **主输入监控通道未绑定**：
   - `ModernMainHook.onSystemServerStarting()` 实例化并安装了 `SystemInputMonitor`，但**漏掉了 `hook.setInputMonitor(...)` 调用**，导致 `ModernSystemGestureHook.pilferPointers()` 中的 `inputMonitor` 始终为 `null`，退化去抢占错误的通道。
2. **返回拦截链路不完整，且只「吞回调」不「取消会话」**：
   - 仅拦截了 `InputManagerService.injectInputEvent*` 中的 `KeyEvent.KEYCODE_BACK`（旧式按键注入）以及 `com.miui.home` 进程内的 `IOnBackInvokedCallback$Stub$Proxy.onBackInvoked()`。
   - Android 17 / HyperOS 4 的预测性返回与原生 Rust 手势层（`hyos_spawner` / `libapp_launcher.so` / `system_server` `BackNavigationController` / `com.android.systemui` WM Shell `BackAnimationController`）可能完全绕过上述两个点，导致松手后仍向源应用下发返回事件。
3. **滑回边缘取消时提前清除了返回屏蔽**：
   - 原版在 `CANCEL_SWITCH` 分支立即调用 `setBackSuppression(context, 0L)`，导致用户呼出菜单后滑回边缘取消、松手瞬间仍可能漏发一次系统返回。

---

## 2. 新项目已确认的设计决策

| 维度 | 设计定稿 |
| :--- | :--- |
| **目标平台** | 仅专注 **HyperOS 4（Android 17 / SDK 37 / `libxposed` API 102）**，彻底摒弃旧版 Xposed API 82。 |
| **工程基线** | Kotlin 2.3 + Jetpack Compose + Miuix `0.8.8` + `libxposed` API 102 `RemotePreferences`（零重启热同步）+ GitHub Actions CI。 |
| **手势交互** | 保留「侧滑悬停」触发：快速侧滑走系统原生返回（零干扰）；滑过距离并停顿后彻底接管并屏蔽返回；滑回边缘取消（带回滞阈值防抖，且**保持返回屏蔽锁存直到松手后宽限期结束**）。 |
| **切换模式** | 默认「多应用悬浮菜单」（纯最近运行任务列表，按最近使用自上而下排列，默认选中顶部“上一个应用”，下滑切更早应用、上滑回退、首尾边界停止并带触感反馈）；同时保留可选「仅切上一个应用」单切模式。 |
| **进程隔离架构** | **`system` (`system_server`)** 保持极简（仅输入监控、纯状态机、多层返回屏蔽、任务查询与 `moveTaskToFront`，零 UI / 零图标解码 / 零阻塞 IPC）；**`com.android.systemui`** 托管悬浮菜单渲染（通过 `IBinder.FLAG_ONEWAY` 异步 Binder 通信，常驻不被墓碑冻结、免悬浮窗权限、可复用 HyperOS 原生模糊，并同步拦截 WM Shell 预测返回）。 |
| **悬浮菜单 UI** | **对侧悬浮胶囊**（左滑出在右侧、右滑出在左侧彻底防拇指遮挡），HyperOS 毛玻璃质感、平滑入场/缩放动画、选中项高亮与伴随应用名胶囊。 |
| **场景与边界** | 锁屏 / 下拉控制中心 / 通知栏强制禁用；支持左右边缘独立开关与自定义垂直触发高度区间（自动避开顶部状态栏与底部输入法区）。 |
| **Miuix 设置页** | 模块与作用域状态卡（支持一键向 LSPosed 申请作用域）+ 核心开关与触发参数 + 悬浮菜单外观与实时预览 + 应用黑名单选择器 + 诊断日志与关于页。 |

---

## 3. 代码模块结构

```text
app/src/main/java/com/healer/hyperos/swipegesture/
├── policy/
│   ├── SwipeGestureSettings.kt        # 设置数据模型、合法性裁剪、RemotePreferences 序列化协议
│   └── SwipeGestureStateMachine.kt    # 纯 Kotlin 状态机（无 Android 依赖，100% JVM 单测覆盖）
├── ipc/
│   └── OverlayChannelContract.kt      # system_server -> SystemUI 异步 oneway Binder 协议
├── hook/
│   ├── SwipeGestureModuleEntry.kt     # libxposed API 102 入口（system + com.android.systemui）
│   ├── RuntimeSettingsHolder.kt       # 零阻塞 RemotePreferences 内存缓存
│   ├── SystemServerGestureHooks.kt    # 极简 system_server 控制器（待对接实机验证后的 Hook 点）
│   ├── SystemUiOverlayHooks.kt        # SystemUI 悬浮宿主、oneway Binder 注册与 WM Shell 拦截位
│   └── OppositeEdgeCapsuleView.kt     # 对侧毛玻璃胶囊悬浮 View（带 MiBlur 反射安全降级）
└── ui/
    ├── SwipeGestureApplication.kt     # XposedServiceHelper 绑定、RemotePreferences 与作用域申请
    ├── MainActivity.kt                # MiuixTheme Compose 容器
    └── SwipeGestureScreen.kt          # Miuix 分组设置页 + 实时对侧胶囊预览 + 黑名单选择器
```

---

## 4. 本地实机逆向对齐清单（小米 17 · HyperOS 4.0.6.0）

在本地连接 USB 并开启 ADB 后，先运行仓库内置的只读采集脚本（**纯只读，不修改手机任何文件**）：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dump_hyperos4_readonly.ps1
```

然后重点核对以下 4 个问题，再将精确的方法签名填入 `SystemServerGestureHooks.kt` 与 `SystemUiOverlayHooks.kt` 的 `TODO(HyperOS-4.0.6.0)` 位置：

### 检查点 A：是谁持有侧滑返回输入通道？（看 `dumpsys_input.txt`）
- 在 `InputDispatcher` 的 `Windows` 与 `Global monitors` 段落中，按 Z-order 从高到低列出所有 `GestureMonitor` / `InputChannel`（例如 `edge-swipe`、`miui_gesture`、`hyos`、`PointerEventDispatcher`、`TouchInputRecever`）。
- 确认两点：
  1. 哪个常驻且安全的通道能收到完整触摸流（优先单一通道，不再同时 Hook `filterInputEvent`）。
  2. 调用 `InputManager.pilferPointers(token)` 时，**哪个通道的 token 优先级高于系统原生返回手势窗口/监听器**，才能在 `onPauseElapsed()` 时真正把正在进行的侧滑返回流“抢走并向原生返回发送 `ACTION_CANCEL`”。

### 检查点 B：HyperOS 4.0.6.0 侧滑返回究竟通过哪条链路提交？（看 `back_gesture_logcat.txt` + 系统包）
重点核对以下 4 条候选路径在小米 17 HyperOS 4.0.6.0 上实际触发了哪一条（或多条）：
1. **`system_server` 预测性返回主控**：`com.android.server.wm.BackNavigationController`（`startBackNavigation` / `onBackNavigationDone` / 主动取消正在进行的导航会话）。
2. **`com.android.systemui` WM Shell**：`com.android.wm.shell.back.BackAnimationController` / `EdgeBackGestureHandler`（`onBackMotion` / `finishBackNavigation` / 取消返回动画并下发 `onBackCancelled()` 而非直接静默吞掉 `onBackInvoked()`）。
3. **`system_server` 输入注入**：`com.android.server.input.InputManagerService`（`injectInputEvent*` / 小米私有 `MiuiInputManager`）下发的 `KeyEvent.KEYCODE_BACK`。
4. **`com.miui.home` / `hyos_spawner` 原生桌面手势**：若 `com.miui.home` APK（如 `RELEASE-8.01.02.9259`）或 `libapp_launcher.so` 仍通过 Java/JNI 桥调用返回提交或 `IOnBackInvokedCallback`，则仅在实机证实后把 `com.miui.home` 补入 `scope.list` 并加最小化精准 Hook。

### 检查点 C：下拉控制中心 / 通知栏 / 输入法防误触状态读取
- `system_server` 内通过 `KeyguardManager.isKeyguardLocked` 已拦截锁屏。
- 核对 `WindowManagerService` / `DisplayPolicy` 中判断控制中心/通知栏展开状态及 IME 可见高度的字段（或由 `SystemUI` 侧将 shade 展开状态通过极简信号同步），补齐 `isSystemUiBlockingGesture()`。

### 检查点 D：SystemUI 悬浮窗层级与原生毛玻璃
- 验证 `OppositeEdgeCapsuleView` 在 `com.android.systemui` 内使用的 `WindowManager.LayoutParams.type`（如 `TYPE_APPLICATION_OVERLAY` 或 SystemUI 特权层级 `TYPE_NAVIGATION_BAR_PANEL` / `TYPE_STATUS_BAR_SUB_PANEL`）在全屏应用与输入法上方均能正常显示且不抢焦点。
- 对齐 `MiuiSystemUI.apk` 中的 `MiBlurCompat` / `setMiBackgroundBlurMode` / `setMiBackgroundBlendColors` 精确签名，使胶囊毛玻璃与 HyperOS 4 原厂质感完全统一。

---

## 5. 迭代开发顺序建议

1. **Phase 1（本地逆向对齐）**：运行 `scripts/dump_hyperos4_readonly.ps1`，必要时只读 `adb pull` 对应 `services.jar` / `miui-services.jar` / `MiuiSystemUI.apk`（或 `oat/arm64/*.vdex`），确认检查点 A–D 的精确类与方法签名。
2. **Phase 2（`system_server` 零穿透闭环）**：接入验证后的输入通道与多层返回取消/屏蔽链路，开启「详细手势诊断日志」实机测试：
   - 快速侧滑 → 100% 原生返回；
   - 侧滑悬停切应用 → 成功切换，且切回原应用确认**未触发返回**；
   - 侧滑悬停后滑回边缘取消 → 菜单关闭，且当前应用**未触发返回**。
3. **Phase 3（SystemUI 视觉与触感打磨）**：在 `OppositeEdgeCapsuleView` 对齐原厂柔光玻璃模糊、平滑缩放/位置动画与小米线性马达触感。
4. **Phase 4（压力测试与发布）**：连续切换/取消/锁屏/横竖屏/桌面/分屏/小窗回归测试无崩溃、无 ANR、无重启，再通过 GitHub Actions Tag 自动构建签名 Release APK。
