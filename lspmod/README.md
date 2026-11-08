# DSH 工具箱（DSH 语音键）

一个 APK，两个身份：

- **LSPosed 模块**（注入 `io.github.ccch1mneyyy.dsh_app` 进程）
- **普通 App**（root 前台服务 + MIUIX 风格设置页）

作用：让一加 15 的 **Plus 键** 变成「按住说话、松手发送」，全程复用 dsh-Go 网页版自己的语音功能，不改 dsh-Go 安装包。

---

## 一、为什么这么设计

| 环节 | 现状 | 结论 |
| --- | --- | --- |
| Plus 键事件 | framework 映射成 `KEYCODE_UNKNOWN`，普通 App 收不到；内核 `event0` 上有 `BTN_TRIGGER_HAPPY32`（码 `0x02df`） | 必须 root 读 `/dev/input/event0` |
| 网页录音 | dsh-Go 清单缺 `MODIFY_AUDIO_SETTINGS`，且 `WebChromeClient` 拒绝 `AUDIO_CAPTURE` | 模块补权限检查 + 放行授权 |
| 触发录音 | 网页里点麦克风 = JS `click()` | 模块 `evaluateJavascript`，不用坐标 |
| 新建会话 | `EntryActivity` 把 `VOICE_INPUT` 翻成 `kind=voice`，会新开语音会话 | 改用 `OPEN_APP` / `OPEN_CHAT --es session_id` |
| 后台录音 | `DshService.foregroundServiceType=specialUse`，无 `microphone` 类型 | **做不到**，只能把 App 拉前台（或用窗口小窗） |

---

## 二、链路

```
Plus 键长按（内核 BTN_TRIGGER_HAPPY32 DOWN）
      │  PttService：cat /dev/input/event0 解析 24 字节结构
      ▼
  350ms 判定长按
      │  已在前台？→ 跳过 am start（避免闪）
      ▼
  am start EntryActivity -a OPEN_APP  （或 OPEN_CHAT + session_id）
      │
      ├──► broadcast com.dshfix.ptt.START ─► 模块：显示胶囊 + JS 点麦克风
      │
松开（BTN_TRIGGER_HAPPY32 UP）
      ▼
  broadcast com.dshfix.ptt.STOP ─► 模块：胶囊变「识别中…」+ JS 停录 + 2s 后点发送
```

---

## 三、目录

```
lspmod/
├── AndroidManifest.xml          模块 + App 清单（xposedmodule/xposedscope/$xposedminversion=82）
├── build.sh                     手工打包：javac → d8 → aapt2 → zipalign → apksigner
├── res/values/arrays.xml        xposedscope
├── assets/xposed_init           入口类
└── src/com/dshfix/modaudio/
    ├── HookMain.java            模块本体：权限/授权/JS/覆盖层/广播
    ├── MainActivity.java        设置页（MIUIX 风格）
    ├── PttService.java          root 前台服务：读按键、发广播、跳会话
    └── BootReceiver.java        开机自启
```

---

## 四、构建与安装

```bash
bash lspmod/build.sh                       # 产出 lspmod/build/dshfix.apk
adb install -r lspmod/build/dshfix.apk
```

要求：JDK 17、Android SDK build-tools 37、platforms/android-37.0。
`build.sh` 在 javac 失败时会直接退出并打印错误（GBK 已转码），不会再产出旧包。

装完在 LSPosed 里启用模块、作用域勾 `DSH Go`；FolkPatch 里给本 App 授 root；打开 App 一次（服务自动启动）。

---

## 五、排障

日志统一看 `logcat -s DSH-MOD`：

| 日志 | 含义 |
| --- | --- |
| `injected -> ...` / `hooked app client er0` | 模块已注入，WebChromeClient 已接管 |
| `ptt: raw reader started` | 按键读取已启动 |
| `ptt: start` / `ptt: stop` | 读到按下 / 抬起 |
| `ptt: heartbeat` | 30s 心跳，服务活着 |
| `ptt: watchdog reset keyDown` | UP 丢失，看门狗复位（不会再有“只能按一次”） |
| `ptt: reader EOF` + `reader restart in ...` | `cat` 挂了，按退避重启 |
| `broadcast ptt.START -> click mic` | 广播到达模块 |
| `tryVoice#n js sent` | JS 已注入网页 |
| `CHCALL ...` | （探针）Flutter channel 调用记录 |

常见问题：

- **按了没反应**：看有没有 `ptt: raw reader started`；没有就是服务没起（打开 App 一次）或没给 root。
- **录音起来了但没发送**：看是否 `DSH-JS no send`，网页版发送按钮的选择器要按实际改。
- **提示“需要停在网页版”**：模块在原生界面找不到 WebView，切回网页版。
- **模块不生效**：确认 `xposedminversion` 是 `82`（写 93 会被 LSPosed 静默跳过）。

---

## 六、已知限制

- 后台（App 不在前台）无法录音，这是 `foregroundServiceType` 决定的；要彻底解决必须改 dsh-Go 清单并重签。
- 原生界面没有 WebView，本方案不覆盖（只能注入坐标，会很脆）。
- 网页版改版后，`aria-label`（麦克风/语音/voice/mic、发送/send）可能要跟着调。
