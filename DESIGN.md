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
| **悬浮菜单 UI** | **对侧悬浮胶囊**（左滑出在右侧、右滑出在左侧彻底防拇指遮挡），纯图标毛玻璃质感、平滑入场/缩放动画与选中项高亮环（不额外弹出应用标题胶囊）。 |
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

## 4. 实机逆向对齐结论（小米 17 · HyperOS 4.0.6.0.XPCCNXM）

已于小米 17（`pudding` / `25113PN0EC`，`OS4.0.6.0.XPCCNXM`，Android 17 / SDK 37）完成纯只读 `dumpsys`、系统产物（`services.jar`、`miui-services.jar`、`framework.jar`、`MiuiSystemUI.apk`、活动态 `com.miui.home` `RELEASE-8.01.02.9259`）及活体侧滑 `logcat` 采证，四项检查点已全部闭环对齐：

### 检查点 A：输入通道与抢占层级（已实机验证）
- 实机 `dumpsys input` 留存了原模块 `PointerEventDispatcher0` 阻塞 `system_server` `android.ui` 线程导致 5000ms ANR 的现场记录，且 `PointerEventDispatcherOverlay0` 带有 `DO_NOT_PILFER` 标志。
- 实际可用通道 Z-order：
  - `[Gesture Monitor] miui-gesture`（`system_server` 独立实时线程 `miui-gesture-0`，`SPY`，**无 `DO_NOT_PILFER`**）严格位于 `com.miui.home` 的 `GestureStubLeft` / `GestureStubRight` 之上。
- **已落地实现**（`SystemServerGestureHooks.kt`）：
  - Hook `WindowManagerService.systemReady()` 获取 `mContext` / `mH` / `mGlobalLock` / `mPolicy`。
  - 通过 `com.miui.server.input.gesture.MiuiGestureMonitor.getInstance(context)` 注册动态代理 `MiuiGestureListener.onPointerEvent(MotionEvent)`（异常时自动兜底 `InputManagerGlobal.monitorGestureInput`）。
  - 在 `ArmPause` 阶段提前在 `workerExecutor` 后台预取最近任务列表，确保 `miui-gesture` 实时线程零阻塞。

### 检查点 B：HyperOS 4.0.6.0 侧滑返回提交链路（已实机验证）
- 实机 `dumpsys_systemui.txt` 确认 SystemUI `EdgeBackGestureHandler` 已关闭（`mIsEnabled=false`）。
- 活动态 `com.miui.home` 为纯原生 Rust/Flutter 架构（`libapp_launcher.so`，无 Java DEX），其 `GestureInputBackHelper` 全部经由 `system_server` 触发返回，活体 `CoreBackPreview` 日志证实走 `BackNavigationController.startBackNavigation` → `IOnBackInvokedCallback` → `onBackNavigationDone`（未走 `KEYCODE_BACK` 注入）。
- **已落地三层拦截闭环**（无需把 `com.miui.home` 加入 `scope.list`）：
  1. **输入抢占撤回**：悬停激活时调用 `MiuiGestureMonitor.pilferPointers()`，向 `GestureStubLeft`/`Right` 派发 `ACTION_CANCEL` 收回原生箭头。
  2. **预测返回会话代理**：Hook `BackNavigationController.startBackNavigation(RemoteCallback, BackAnimationAdapter)` 与 `onBackNavigationDone(Bundle, int)`：
     - 锁存期间直接阻止新会话；
     - 对激活前 7ms 已创建的会话，通过本地 `BackCallbackSuppressionBinder` 代理 `BackNavigationInfo.mOnBackInvokedCallback`，将 `TRANSACTION_onBackInvoked` 拦截并改发 `TRANSACTION_onBackCancelled`，同时在 `WMS.mH` 下通过 `NavigationMonitor.cancelBackNavigating` 清理会话并将 `NavigationFinished` 置为 `false`。
  3. **按键注入兜底**：Hook `InputManagerService.injectInputEventToTarget(InputEvent, int, int, boolean, String[])`，锁存期间吸收 `KeyEvent.KEYCODE_BACK`。

### 检查点 C：锁屏 / 控制中心 / 通知栏 / 输入法防误触（已实机验证）
- `SystemUiOverlayHooks` Hook `com.android.systemui.shade.NotificationShadeWindowControllerImpl.apply(NotificationShadeWindowState)`，合并 `controlCenterExpanded || qsExpanded || shadeOrQsExpanded || bouncerShowing || (keyguardShowing && !keyguardOccluded) || dozing || dreaming`，仅在状态跳变时经由 `SystemServerControlClient`（`FLAG_ONEWAY`）同步给 `system_server`（同时保留 `KeyguardManager.isKeyguardLocked` 与 `StatusBarManagerService.onPanelRevealed/Hidden` 兜底）。
- 通过 `WindowManagerService.mPolicy`（`BaseMiuiPhoneWindowManager`）实机字段 `mInputMethodWindowVisibleHeight` 动态裁剪底部触发区，输入法弹出时自动屏蔽键盘区域误触。

### 检查点 D：SystemUI 悬浮窗层级与原生毛玻璃（已实机验证）
- 实机 `dumpsys window windows` 显示 `TYPE_APPLICATION_OVERLAY (2038)` 基准层级为 `111000`，低于 `InputMethod (2011, 131000)`。`SystemUiOverlayHooks` 已升级为 SystemUI 特权层级 `TYPE_STATUS_BAR_SUB_PANEL (2017, 181000)`（失败时降级 `2038`），避免输入法遮挡及非系统 `2038` 透明度限制。
- `OppositeEdgeCapsuleView` 接入 `MiuiSystemUI.apk` 原厂材质链路（`MaterialToken$Builder` + `MaterialDayNightConfig` + `MiuiBlurUtils.setPassWindowBlurEnabled` / `setBackgroundOnlyBlur` / `setViewBlurMode(1)` / `setBackgroundBlendConfig` + `onViewAttachedToWindow` 内调用 `setMiBlurWinType`），默认提供 **柔光玻璃**（`ColorBlendToken.Pured_Thick_Glass_*` + `BloomStrokeToken.Glass_Stroke_Small_*` 柔光亮边 + `ShadowToken.Regular`）与 **高级磨砂**（`ColorBlendToken.Heavy_*` + `ShadowToken.Regular`）两档切换，并支持外观主题（跟随系统 / 始终浅色 / 始终深色）及极简纯图标高亮环样式。

---

## 5. 迭代开发顺序建议

1. **Phase 1（本地逆向对齐）**：运行 `scripts/dump_hyperos4_readonly.ps1`，必要时只读 `adb pull` 对应 `services.jar` / `miui-services.jar` / `MiuiSystemUI.apk`（或 `oat/arm64/*.vdex`），确认检查点 A–D 的精确类与方法签名。
2. **Phase 2（`system_server` 零穿透闭环）**：接入验证后的输入通道与多层返回取消/屏蔽链路，开启「详细手势诊断日志」实机测试：
   - 快速侧滑 → 100% 原生返回；
   - 侧滑悬停切应用 → 成功切换，且切回原应用确认**未触发返回**；
   - 侧滑悬停后滑回边缘取消 → 菜单关闭，且当前应用**未触发返回**。
3. **Phase 3（SystemUI 视觉与触感打磨）**：在 `OppositeEdgeCapsuleView` 对齐原厂柔光玻璃模糊、平滑缩放/位置动画与小米线性马达触感。
4. **Phase 4（压力测试与发布）**：连续切换/取消/锁屏/横竖屏/桌面/分屏/小窗回归测试无崩溃、无 ANR、无重启，再通过 GitHub Actions Tag 自动构建签名 Release APK。
