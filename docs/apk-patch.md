# 给 dsh-Go 打补丁（改清单 + 重签）

目的：让它具备「后台持有麦克风」和「画中画」能力，同时**保留原有数据**（登录、远程配对、本机环境）。

前置条件：设备已启用 **CorePatch**（免签名校验），否则重签包无法覆盖安装。
判断方法：`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`
就是没生效；若报 `INSTALL_FAILED_VERSION_DOWNGRADE`，说明签名这关**已经放过**（校验顺序是先版本、后签名）。

---

## 一、取当前安装包

```bash
P=$(adb shell pm path io.github.ccch1mneyyy.dsh_app | tr -d '\r' | sed 's/package://')
adb pull "$P" dshgo_installed.apk
aapt2 dump badging dshgo_installed.apk | head -2   # 确认 versionCode / versionName
```

## 二、反编译（只解资源，dex 原样保留）

```bash
java -jar apktool.jar d -s -f -o dshgo_out dshgo_installed.apk
```

`-s` 很关键：194 MB 的包，反编译 dex 要几分钟且没必要。

## 三、改 `AndroidManifest.xml`

三处改动：

```xml
<!-- 1) 权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>

<!-- 2) 前台服务类型加 microphone（原来是 specialUse） -->
<service android:name="io.github.ccch1mneyyy.dsh_app.runtime.DshService"
         android:foregroundServiceType="specialUse|microphone" ... />

<!-- 3) 画中画（可选） -->
<activity android:name="io.github.ccch1mneyyy.dsh_app.MainActivity"
          android:supportsPictureInPicture="true" ... />
```

注意：`PetOverlayService` 等其它服务**不要**加 microphone，否则启动即崩
（`foregroundServiceType ... is not a subset of ... in manifest`）。

## 四、重打包 + 对齐 + 签名

```bash
java -jar apktool.jar b dshgo_out -o dshgo_patched_unsigned.apk
zipalign -f 4 dshgo_patched_unsigned.apk dshgo_patched_aligned.apk
apksigner sign --ks ks.jks --ks-pass pass:android --ks-key-pass pass:android \
    --out dshgo_patched.apk dshgo_patched_aligned.apk
```

## 五、覆盖安装

```bash
adb install -r dshgo_patched.apk
```

签名变了但 CorePatch 放过 → 成功覆盖，数据保留。

## 六、配套：模块里补上类型位

清单允许不代表运行时就带上。它代码里是显式传类型的
（`startForeground(id, notif, TYPE_SPECIAL_USE)`），所以模块里钩一下：

```java
XposedHelpers.findAndHookMethod("android.app.Service", cl, "startForeground",
        int.class, Notification.class, int.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!p.thisObject.getClass().getName().contains("DshService")) return;
                int t = (Integer) p.args[2];
                if ((t & 0x80) == 0) p.args[2] = t | 0x80;   // MICROPHONE = 0x80
            }
        });
```

**位值别写错**：`MICROPHONE = 0x80`，`0x4` 是 PHONE_CALL。写错的结果是应用一启动就崩：

```
IllegalArgumentException: foregroundServiceType 0x40000004 is not a subset of 0x40000080
```

验证：

```bash
adb shell dumpsys activity services io.github.ccch1mneyyy.dsh_app | grep types=
# 期望 types=0x40000080（specialUse | microphone）
```

## 七、坑位清单

| 现象 | 原因 |
| --- | --- |
| `UPDATE_INCOMPATIBLE` | CorePatch 没生效（看开机日志 `[CorePatch] Unsupported Version of Android`） |
| `VERSION_DOWNGRADE` | 用的包比已装版本旧；先 `pm path` 拉当前包 |
| 启动即崩 `not a subset` | 服务清单没声明该类型，或模块位值写错 |
| 开机后麦克风仍拿不到 | 代码显式传了类型，模块钩子没生效（确认作用域含 DSH Go） |
| 官方更新装不上 | 签名不同；每次更新都要重打一遍（或卸载重装） |

## 八、回滚

卸载后安装官方包即可（**会丢登录和配对数据**）：

```bash
adb uninstall io.github.ccch1mneyyy.dsh_app
adb install dshgo-官方.apk
```

## 产物位置

| 文件 | 说明 |
| --- | --- |
| `dshgo_patched.apk` | 打好补丁的重签包（1.0.13 基础） |
| `apktool.jar` | 反编译工具 |
| `lspmod/ks.jks` | 签名用的 keystore（密码 `android`） |
