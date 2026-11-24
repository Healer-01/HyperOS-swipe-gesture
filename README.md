# HyperOS Swipe Gesture（侧滑快切）

适用于 **HyperOS 4（Android 17 / SDK 37）** 的现代 **LSPosed 模块**（基于 `libxposed` API 102），提供「屏幕边缘侧滑悬停」快速呼出多应用悬浮胶囊或一键切换上一个应用的能力，同时保证**零 `system_server` 重启风险**与**零系统侧滑返回穿透**。

> 当前仓库为已完成编译与单元测试验证的 **工程骨架 + 架构蓝图版本**（详见 [DESIGN.md](./DESIGN.md)），等待结合小米 17（HyperOS 4.0.6.0）实机只读逆向对齐最终输入通道与返回屏蔽方法签名。

## 核心架构亮点

- **极简 `system_server` + `com.android.systemui` 进程隔离**：
  - `system`（系统框架）仅保留极薄的输入事件监听、纯状态机、返回链路取消与 `moveTaskToFront`，**彻底禁止在 `system_server` 内创建 UI、同步查询 ContentProvider、写入 `Settings.Global` 或调用旧版 Xposed 82 API**。
  - `com.android.systemui` 作为常驻悬浮菜单宿主，通过异步 `IBinder.FLAG_ONEWAY` 通道接收更新（永不阻塞 `system_server`、免疫小米墓碑冻结、免授予悬浮窗权限、可复用 HyperOS 原生毛玻璃，并同步承接 WM Shell 预测返回拦截）。
- **彻底杜绝返回穿透（含取消场景）**：
  - 正常快速侧滑完全交由系统原生返回，零延迟、零干扰。
  - 侧滑悬停激活后锁存返回屏蔽态（带滑回取消回滞防抖），无论最终**松手切换应用**还是**滑回边缘取消**，都保证不向当前应用漏发返回事件。
- **Miuix 原生质感设置界面**：
  - 基于 [`miuix-kotlin-multiplatform`](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）构建，内置 LSPosed API 102 状态与一键作用域申请、左右边缘与垂直高度区间调节、对侧毛玻璃胶囊实时预览、应用黑名单选择器与诊断工具。
  - 通过 `libxposed` API 102 `RemotePreferences` 实时热同步配置，无需重启即可生效。

## 本地开发与实机逆向起步

1. **克隆仓库到本地**：
   ```bash
   git clone https://github.com/Healer-01/HyperOS-swipe-gesture.git
   cd HyperOS-swipe-gesture
   ```
2. **本地运行只读 ADB 链路采集（小米 17 / HyperOS 4.0.6.0）**：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\dump_hyperos4_readonly.ps1
   ```
3. **按照 [DESIGN.md](./DESIGN.md) 完成 Hook 签名对齐**，并在本地构建与执行单元测试：
   ```bash
   ./gradlew testDebugUnitTest assembleDebug assembleRelease
   ```

## 构建与 CI

- **工具链**：JDK 17+、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.20、`compileSdk = 37`（`platforms;android-37.0`）、`minSdk = 33`、`targetSdk = 35`。
- **GitHub Actions**：工作流位于 `.github/workflows/build.yml`，推送至 `main` 分支或 `v*` 标签后自动执行 JVM 单元测试并构建 Debug / Release APK。

## 开源许可与致谢

- **[miuix-kotlin-multiplatform](https://github.com/compose-miuix-ui/miuix)**（`top.yukonga.miuix.kmp`）— Apache License 2.0
- **[libxposed API & Service](https://github.com/libxposed/api)**（`io.github.libxposed` API 102）— Apache License 2.0
- 功能灵感参考自 `hyper-gesture` 项目
