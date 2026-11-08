# -*- coding: utf-8 -*-
"""修改 dsh-Go 反编译目录里的 AndroidManifest.xml。

用法: python patch_manifest.py <反编译目录>
"""
import io
import re
import sys

PKG = "io.github.ccch1mneyyy.dsh_app"
PERM = '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>'


def main(decoded_dir):
    path = decoded_dir + "/AndroidManifest.xml"
    src = io.open(path, encoding="utf-8").read()
    changed = []

    # 1) 麦克风前台服务权限
    if "FOREGROUND_SERVICE_MICROPHONE" not in src:
        anchor = '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>'
        if anchor in src:
            src = src.replace(anchor, anchor + "\n    " + PERM)
        else:
            anchor = '<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>'
            src = src.replace(anchor, anchor + "\n    " + PERM)
        changed.append("+ FOREGROUND_SERVICE_MICROPHONE")

    # 2) DshService 加 microphone 类型（只动 runtime.DshService 这一个元素）
    def add_mic(m):
        tag = m.group(0)
        if "microphone" in tag:
            return tag
        if 'android:foregroundServiceType="specialUse"' in tag:
            return tag.replace('android:foregroundServiceType="specialUse"',
                               'android:foregroundServiceType="specialUse|microphone"')
        return tag

    new_src = re.sub(r"<service\b[^>]*runtime\.DshService[^>]*>", add_mic, src)
    if new_src != src:
        changed.append("+ DshService foregroundServiceType=microphone")
        src = new_src

    # 3) MainActivity 支持画中画
    def add_pip(m):
        tag = m.group(0)
        if "supportsPictureInPicture" in tag:
            return tag
        return tag.replace("android:name=\"%s.MainActivity\"" % PKG,
                           "android:name=\"%s.MainActivity\" android:supportsPictureInPicture=\"true\""
                           % PKG)

    new_src = re.sub(r"<activity\b[^>]*%s\.MainActivity[^>]*>" % re.escape(PKG), add_pip, src)
    if new_src != src:
        changed.append("+ MainActivity supportsPictureInPicture")
        src = new_src

    io.open(path, "w", encoding="utf-8").write(src)
    print("manifest: " + (", ".join(changed) if changed else "already patched"))


if __name__ == "__main__":
    main(sys.argv[1])
