# 提取 oplus-framework.dex

模块构建需要 `app/assets/fg_oplus.dex`，它来自 ColorOS 固件的系统框架 jar。
这是 OPPO 专有组件，仓库不分发，请从**本人拥有的 ColorOS 设备**提取。

## 方法一：从真机提取（推荐，版本最匹配）

在一台 ColorOS 手机上开启 USB 调试后：

```bash
# 系统框架 jar 位置
adb shell ls /system/framework/oplus-framework.jar

# 拉出来解出 classes.dex
adb pull /system/framework/oplus-framework.jar
unzip oplus-framework.jar classes.dex -d .
cp classes.dex fg_oplus.dex
```

验证 dex 里包含 sharescreen SDK 依赖的 wrapper 类：

```bash
strings fg_oplus.dex | grep "com/oplus/wrapper/os/SystemProperties"
```

## 方法二：从固件包提取

1. 下载对应机型的 ColorOS 完整 OTA 包（.zip）
2. 解出 `payload.bin`，用 [payload-dumper-go](https://github.com/xxf098/payload-dumper-go) 抽取 system 分区：
   ```bash
   payload-dumper-go -p system -o parts payload.bin
   ```
3. ColorOS 15 的 system 分区是 **EROFS** 格式，普通工具解不开，
   用 [erofs-utils](https://github.com/ColdWindScholar/erofs-utils) 的 extract.erofs：
   ```bash
   extract.erofs.exe -i system.img -x -o system_out
   ```
4. 在 `system_out/system/system/framework/oplus-framework.jar` 里解出 classes.dex，
   重命名为 `fg_oplus.dex`

## 放置位置

```
FamilyGuardHelper/app/assets/fg_oplus.dex
```

放好后即可执行 `scripts/build_apk.sh`。

> 注意：该文件为 OPPO 专有代码，仅限本地构建使用，**请勿再分发**（包括你构建出的
> 含此 dex 的 APK）。
