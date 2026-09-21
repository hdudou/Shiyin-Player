#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开源版**脱敏闸门**：推送前扫描仓库内容，禁止任何私有信息进入公开仓库。

为什么需要它
------------
本仓库是从作者自用版脱敏出来的开源副本。自用版里有大量**只应留在私有环境**的东西：
内网地址、家庭主机口令、真机序列号、私有同步协议字段、PC 版仓库与计划文档引用……
这些东西一旦被误推上来，**git 历史就永久带着它们**，即便事后改文件也删不掉
（只能重写历史 + 强推，代价极高且可能已被 fork/缓存）。

所以把「严格限制」做成**可执行的检查**，而不是口头约定：
  python scripts/check-desensitize.py            # 人工检查（推送前跑一次）
  python scripts/check-desensitize.py --install-hook   # 装成 pre-push 钩子，自动拦

判定口径
--------
- 只扫**被 git 跟踪**的文件（`git ls-files`），忽略二进制；
- 命中即 `exit 1` 并打印 `文件:行号` 与命中的规则名；
- 误报可在该行加注释 `desensitize-allow` 显式豁免（要求写明理由，便于复核）；
- **README 里的示例地址（如 192.168.1.10）不算违规** —— 规则只针对作者真实的私有值。

退出码：0 = 干净，1 = 有命中。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (规则名, 正则, 说明)  —— 正则全部针对**作者真实私有值**，不是泛泛的「内网 IP」
RULES: list[tuple[str, re.Pattern[str], str]] = [
    ("LAN_SUBNET", re.compile(r"192\.168\.124\.\d+"),
     "作者局域网段（Gitea / WebDAV / Linux 主机都在这段）"),
    ("ZT_SUBNET", re.compile(r"192\.168\.196\.\d+"),
     "作者 ZeroTier 虚拟网段（WebDAV-ZT）"),
    ("HOST_CRED", re.compile(r"rongjun", re.I),
     "家庭主机口令片段"),
    ("DEVICE_SERIAL", re.compile(r"(344cd2ff|25204523020073|7fd729829f692984)"),
     "真机序列号"),
    # 注意：**不要**为 `com.shiyinplayer` / `com.shiyinplayer.debug` 加规则 ——
    # 开源版**有意沿用同一个 applicationId**（见 app/build.gradle.kts 与 README 的构建章节），
    # 它是公开的构建信息，不是私有信息（首次加这条规则时在 README 上误报过 2 处）。
    ("LOCAL_PATH", re.compile(r"[A-Za-z]:[\\/]{1,2}MYCODE"),
     "作者本机绝对路径"),
    ("PLAN_DOC", re.compile(r"plan-1\.0\.(14|16)"),
     "私有计划/决策文档（含内网地址与未公开设计）"),
    ("SYNC_PROTO", re.compile(r"(deviceToken|pinnedCert|TokenSecret|lan_sync)"),
     "私有局域网同步协议字段"),
    ("SYNC_PORT", re.compile(r"23541"),
     "私有同步服务端口"),
    ("PC_REPO", re.compile(r"(shiyin-pc|Shiyin\.Desktop|nas://)"),
     "PC 主控端仓库 / 私有网络源定位符"),
    ("INTERNAL_HOST", re.compile(r"192\.168\.[0-9]+\.23(?::\d+)?"),
     "作者内网服务器（NAS / Gitea 常见落点）"),
]

ALLOW_MARKER = "desensitize-allow"
SKIP_EXT = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".so", ".jar", ".aar",
    ".apk", ".aab", ".zip", ".gz", ".7z", ".mp3", ".mp4", ".ttf", ".otf", ".woff", ".woff2",
}


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                         capture_output=True, text=True, errors="replace").stdout
    return [l for l in out.splitlines() if l.strip()]


def scan() -> list[tuple[str, int, str, str, str]]:
    findings: list[tuple[str, int, str, str, str]] = []
    for rel in tracked_files():
        if os.path.splitext(rel)[1].lower() in SKIP_EXT:
            continue
        path = os.path.join(REPO_ROOT, rel)
        try:
            if os.path.getsize(path) > 2_000_000:
                continue
            text = open(path, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if ALLOW_MARKER in line:
                continue
            for name, rx, desc in RULES:
                m = rx.search(line)
                if m:
                    findings.append((rel, lineno, name, m.group(0), desc))
    return findings


HOOK_BODY = """#!/bin/sh
# 由 scripts/check-desensitize.py --install-hook 生成：开源版推送前的脱敏闸门。
# 命中私有信息就中止推送（详见脚本头部说明）。
for PY in python3 python py; do
    if command -v "$PY" >/dev/null 2>&1; then
        "$PY" scripts/check-desensitize.py || {
            echo ""
            echo "[脱敏闸门] 发现私有信息，已中止推送。修掉上面的命中项再推。"
            echo "           （确属误报可在该行加注释 desensitize-allow 并写明理由）"
            exit 1
        }
        exit 0
    fi
done
echo "[脱敏闸门] 警告：本机找不到 python，跳过检查（请手动运行 scripts/check-desensitize.py）"
exit 0
"""


def install_hook() -> int:
    hooks_dir = os.path.join(REPO_ROOT, ".git", "hooks")
    if not os.path.isdir(hooks_dir):
        print(f"[FATAL] 找不到 {hooks_dir}（脚本要在仓库内运行）")
        return 2
    target = os.path.join(hooks_dir, "pre-push")
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(HOOK_BODY)
    try:
        os.chmod(target, 0o755)
    except OSError:
        pass
    print(f"已安装 pre-push 钩子：{target}")
    print("之后每次 git push 都会先跑脱敏检查。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="开源版脱敏闸门")
    ap.add_argument("--install-hook", action="store_true", help="安装为 pre-push 钩子")
    ap.add_argument("--quiet", action="store_true", help="只在有命中时输出")
    args = ap.parse_args()

    if args.install_hook:
        return install_hook()

    findings = scan()
    if not findings:
        if not args.quiet:
            print(f"[脱敏闸门] 通过：{len(tracked_files())} 个跟踪文件，"
                  f"{len(RULES)} 条规则，未发现私有信息。")
        return 0

    print(f"[脱敏闸门] 发现 {len(findings)} 处私有信息：\n")
    for rel, lineno, name, matched, desc in findings:
        print(f"  {rel}:{lineno}")
        print(f"      规则 {name}（{desc}）命中：{matched}")
    print("\n处理：删掉/替换成示例值，或在该行加注释 desensitize-allow 并写明理由。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
