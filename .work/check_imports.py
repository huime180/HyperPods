#!/usr/bin/env python3
"""
模块内引用一致性检查（本机无 JDK/Android SDK，用它替代一部分编译期检查）。

检查项：
  1. import com.chenyc.hyperpods.X.Y  —— 目标包 X 里是否真的声明了 Y
  2. 包声明与目录是否一致
  3. 顶层声明重名（同一包内两个文件声明同名类型 → Kotlin 编译报错）
  4. 已知被移除的架构类是否仍被引用

用法：python3 .work/check_imports.py
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"
BASE_PKG = "com.chenyc.hyperpods"

DECL_RE = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|annotation\s+|enum\s+|fun\s+)?'
    r'(class|interface|object|typealias)\s+([A-Za-z_]\w*)',
    re.MULTILINE,
)
# 顶层（顶格）声明的 val/fun/property，Kotlin 里也可被 import
TOPLEVEL_VAL = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+|const\s+|lateinit\s+)*'
    r'val\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_]\w*)',
    re.MULTILINE,
)
TOPLEVEL_FUN = re.compile(
    r'^(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+|inline\s+|suspend\s+|operator\s+|tailrec\s+)*'
    r'fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?, ]*\.)?([A-Za-z_]\w*)',
    re.MULTILINE,
)
# AGP 构建期生成，源码树里没有
GENERATED = {BASE_PKG + ".R", BASE_PKG + ".BuildConfig", BASE_PKG + ".Manifest"}
PKG_RE = re.compile(r'^package\s+([\w.]+)', re.MULTILINE)
IMPORT_RE = re.compile(r'^import\s+([\w.]+)', re.MULTILINE)

REMOVED = ["ControlBridge", "PodSnapshot", "PodListener", "PodEvent", "XposedEntry"]


def is_private_decl(path: Path, name: str) -> bool:
    """该文件里这个顶层声明是否为 private（private = 文件级作用域，跨文件同名不冲突）。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    for pat in (rf'^private\s+(?:inline\s+|suspend\s+)?(?:val|fun|class|object|interface)\s+{name}\b',
                rf'^private\s+(?:inline\s+|suspend\s+)?fun\s+[\w.<>?, ]*\.{name}\b',
                rf'^private\s+val\s+[\w.<>?]*\.{name}\b'):
        if re.search(pat, text, re.MULTILINE):
            return True
    return False


def main() -> int:
    files = sorted(SRC.rglob("*.kt")) + sorted(SRC.rglob("*.java"))
    # 包 -> {简单名: [文件]}
    symbols = defaultdict(lambda: defaultdict(list))
    pkg_by_file = {}
    errors = []

    for f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        m = PKG_RE.search(text) if f.suffix == ".kt" else re.search(r'^package\s+([\w.]+)', text, re.MULTILINE)
        if not m:
            errors.append(f"{f.relative_to(ROOT)}: 缺少 package 声明")
            continue
        pkg = m.group(1)
        pkg_by_file[f] = pkg

        # 包目录一致性
        expect_dir = SRC / Path(*pkg.split("."))
        if f.parent != expect_dir:
            errors.append(f"{f.relative_to(ROOT)}: 包名 {pkg} 与目录 {f.parent.relative_to(SRC)} 不一致")

        for kind, name in DECL_RE.findall(text):
            symbols[pkg][name].append(f)
        for name in TOPLEVEL_VAL.findall(text):
            symbols[pkg][name].append(f)
        for name in TOPLEVEL_FUN.findall(text):
            symbols[pkg][name].append(f)

    # 1) import 可解析性
    for f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        for imp in IMPORT_RE.findall(text):
            if not imp.startswith(BASE_PKG) or imp in GENERATED:
                continue
            parts = imp.split(".")
            # 逐级回退：import 可能是「包.类型」也可能是「包.类型.成员」或「包.顶层函数」
            for cut in range(len(parts), 1, -1):
                pkg = ".".join(parts[: cut - 1])
                name = parts[cut - 1]
                if pkg in symbols and name in symbols[pkg]:
                    break
            else:
                errors.append(f"{f.relative_to(ROOT)}: 无法解析 import {imp}")

    # 3) 同包内顶层声明重名
    for pkg, names in symbols.items():
        for name, where in names.items():
            uniq = sorted(set(where))
            if len(uniq) > 1 and not all(is_private_decl(w, name) for w in uniq):
                rel = ", ".join(str(w.relative_to(ROOT)) for w in uniq)
                errors.append(f"包 {pkg} 内重复声明 {name}：{rel}")

    # 4) 已移除的架构类
    for f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        for gone in REMOVED:
            for lineno, line in enumerate(text.splitlines(), 1):
                if re.search(rf'\b{gone}\b', line) and not line.lstrip().startswith(("*", "//")):
                    errors.append(f"{f.relative_to(ROOT)}:{lineno}: 仍引用已移除的 {gone}")

    print(f"扫描 {len(files)} 个源文件，包 {len(symbols)} 个，符号 {sum(len(v) for v in symbols.values())} 个")
    if errors:
        print(f"\n发现 {len(errors)} 个问题：")
        for e in errors:
            print("  -", e)
        return 1
    print("通过：模块内引用一致、无重复声明、无已移除架构残留")
    return 0


if __name__ == "__main__":
    sys.exit(main())
