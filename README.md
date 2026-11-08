# DSH 工具箱 · dsh-Go 语音键

把一加 15 的 **Plus 键** 变成「按住说话、松手自动发送」，全程复用 dsh-Go 自己的语音输入，不改它的安装包逻辑。

```
Plus 键(内核事件) ──► root 守护脚本 ──► 广播 ──► LSPosed 模块
                                                    │
                              ┌─────────────────────┴─────────────────────┐
                              ▼                                           ▼
                     网页版：注入 JS 点麦克风                    原生界面：无障碍按节点点击
                              │                                           │
                              └────────────► 悬浮窗（识别文本 + 会话气泡）◄┘
```

## 组成

| 目录 | 内容 |
| --- | --- |
| `lspmod/` | Android 工程：LSPosed 模块 + 常驻 App（按键服务、悬浮窗、无障碍点击） |
| `scripts/` | 一键给 dsh-Go 打补丁（改清单 + 重签）；清单修改器 |
| `docs/apk-patch.md` | 打补丁的逐步说明与坑位 |

## 快速开始

1. 构建工具箱：

```bash
bash lspmod/build.sh          # 产出 lspmod/build/dshfix.apk
adb install -r lspmod/build/dshfix.apk
```

2. 在 LSPosed 里启用模块，作用域勾选 **DSH Go**；FolkPatch 给「DSH 工具箱」授 root；打开一次 App（服务会自动启动、无障碍授权会自动写回）。

3. （可选）给 dsh-Go 打补丁，解锁后台麦克风与画中画：

```bash
bash scripts/patch-dshgo.sh
```

> 需要设备已启用 CorePatch（免签名校验），否则重签包无法覆盖安装。详见 [docs/apk-patch.md](docs/apk-patch.md)。

## 关键设计

- **按键监听必须跑在 root 进程**：ColorOS 的 `virtualFreeze` 会冻结普通 App 进程（连前台服务也挡不住），但 root 子进程不受影响。守护逻辑由 App 用 `setsid` 拉起，不写死脚本。
- **按键读取直读 `/dev/input/event0`**：`getevent` 走管道是块缓冲，事件会卡在子进程里。
- **悬浮窗底边固定**：高度变化时自己算 `y = 底边 − 高度`，避免系统按 gravity 重排导致的底边跳动。
- **消息区分靠 `data-slot`**：网页版的类名是 CSS Modules 哈希，但 `data-slot="conversation.chat.user.before"` 这类标记稳定。

## 已知限制

- 原生界面必须可见才能被点击，所以会弹出小窗；网页版才能真正"无感"。
- dsh-Go 官方更新后需要重跑补丁脚本（签名不同，不能直接覆盖）。
- 网页版改版可能影响 JS 选择器与 `data-slot` 名称。

## 许可

[MIT](LICENSE) © 2026 ESxyzbil

注意：本仓库**不包含** dsh-Go 本体（AGPL-3.0）的任何代码，补丁脚本只修改清单并由你在本地重签；
请勿发行基于 dsh-Go 的修改版 APK，除非遵守其 AGPL-3.0 条款。
