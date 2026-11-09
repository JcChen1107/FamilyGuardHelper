# FamilyGuardHelper

在 HyperOS（小米/Redmi）上让 ColorOS 版「家人守护」的**远程协助**功能正常工作的 LSPosed 模块。

适用于：自己在小米/Redmi 手机上安装 OPPO 家人守护，作为**控制端**远程协助家里使用 OPPO 手机的长辈。

## 背景

OPPO 家人守护 App 在非 ColorOS 设备上点「协助家人」会直接闪回主界面。抓 logcat 分析后发现问题远不止一层：

```
点「协助家人」
 → sharescreen SDK 网络层未初始化（NetBuild lateinit 崩溃）
 → 缺 rxjava2（ColorOS 将其作为系统库提供，APK 内只有 RxJava3）
 → 缺 131 个 com.oplus.* 框架类
 → AccountManager 拒绝创建 com.heytap 类型帐号（日历组件）
 → OplusFeatureCache 在 HyperOS 上 <clinit> 必然失败且缓存错误状态
 → 悬浮窗/电话监听依赖 OPPO 私有系统服务（tingle IPC）
```

## 工作原理

模块在目标进程内做三层处理：

1. **补库** —— 内置 rxjava2.2.21 + rxandroid + adapter-rxjava2 + reactive-streams dex，
   通过 `makeDexElements` / `InMemoryDexClassLoader` 注入目标进程的 ClassLoader
   （Android 16 已移除 `DexPathList.addDexPath`）。`oplus-framework.dex` 从
   ColorOS 固件提取（构建时自行放入，见下文），同样注入。
2. **短路** —— OPPO 兼容层中直接引用缺失 wrapper 类的代码
   （`SystemPropertiesCompat`、Trace 初始化等），在方法入口直接返回调用方
   提供的默认值，方法体永不执行。
3. **托管** —— 依赖缺失系统服务的组件
   （悬浮窗管理、OplusFeatureConfigManager、Tingle IPC）整类替换为空实现，
   使用 `Unsafe.allocateInstance` 构造跳过 `<clinit>` 的假单例。

另有：设备信息伪装（Build 字段 / SystemProperties / hasSystemFeature）、
`AccountManager.addAccountExplicitly(com.heytap)` 拦截、`hasSystemFeature` 特性放行。

## 安装（使用预编译包）

1. 安装四个 APK：
   - 家人守护（com.coloros.familyguard，从 ColorOS 设备提取或 OPPO 渠道获取）
   - 屏幕共享（com.coloros.sharescreen，固件提取）
   - 远程守护服务（com.coloros.remoteguardservice，固件提取）
   - FamilyGuardHelper（本模块）
2. 授予运行时权限：
   ```bash
   adb shell pm grant com.coloros.sharescreen android.permission.READ_PHONE_STATE
   adb shell pm grant com.coloros.sharescreen android.permission.READ_PHONE_NUMBERS
   adb shell pm grant com.coloros.sharescreen android.permission.RECORD_AUDIO
   adb shell pm grant com.coloros.sharescreen android.permission.CAMERA
   adb shell pm grant com.coloros.sharescreen android.permission.READ_CONTACTS
   adb shell pm grant com.coloros.familyguard android.permission.READ_PHONE_STATE
   adb shell pm grant com.coloros.familyguard android.permission.CAMERA
   ```
3. LSPosed → 模块 → FamilyGuard Helper → 启用，作用域勾选：
   - `com.coloros.familyguard`
   - `com.heytap.htms`（移动服务）
   - `com.coloros.sharescreen`
   - `com.coloros.remoteguardservice`
4. 强停家人守护后重新打开。

## 从源码构建

需要：JDK 11+、Android SDK（build-tools 34+ / platform android-34+）、Python 3。

```bash
# 1. 准备 oplus-framework.dex（专有组件，仓库不含，需自行提取）
#    方法见 docs/extract_oplus_dex.md，提取后放到 app/assets/fg_oplus.dex

# 2. 一键构建（Windows / Git Bash）
bash scripts/build_apk.sh
```

产物在 `build-apk/FamilyGuardHelper-<版本>.apk`。

注意：仓库不包含 OPPO 专有组件（`fg_oplus.dex`、家人守护/屏幕共享等 APK），
请自行从本人拥有的 ColorOS 设备或固件中提取，提取方法见 docs。

## 已知限制

- 悬浮窗、电话状态监听被托管为空实现（功能降级，不影响协助主流程）
- 「等待对方接受邀请」之后的长时间共享会话未经长期验证
- 模块更新请使用覆盖安装（`adb install -r`），卸载重装会丢失 LSPosed 作用域
- 仅在 HyperOS 3 / Android 16（Redmi K90 Max）上验证过，其他版本自行测试

## 目录结构

```
├── app/                  模块源码（HookEntry.java 为核心）
├── stubs/                Xposed API compile-only 签名桩（无需拉 api.xposed.info）
├── scripts/build_apk.sh  手工构建流水线（aapt2 → javac → d8 → zipalign → apksigner）
├── docs/                 oplus-framework.dex 提取教程
```

## 免责声明

本项目仅供学习交流与个人设备功能恢复使用。仓库不含任何 OPPO 专有组件，
相关版权归 OPPO 所有。请勿用于商业用途。使用本项目产生的任何后果由使用者自行承担。

## 许可证

模块源码以 [GPL-3.0](LICENSE) 发布。
`app/assets/fg_rxjava2.dex` 内含的第三方库遵循其各自许可证
（RxJava/RxJava2CallAdapterFactory/RxAndroid: Apache-2.0, reactive-streams: MIT）。
