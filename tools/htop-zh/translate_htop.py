#!/usr/bin/env python3
"""构建时把 htop 源码里的英文 UI 字符串替换成中文。

用法: translate_htop.py <翻译表> <源码目录>

翻译表是 "英文<TAB>中文"。替换按完整 C 字符串字面量进行，且保留两侧的
对齐空格：源码里 "Help  "、" VIRT " 这类带填充的标题都能命中，词条本身
只需写单词/短语本体。短词、单字母、过于通用的词条不要进表（会被全局误伤）。
未命中的词条在结束时列出（源码措辞变了就复查翻译表）。
"""
import pathlib
import re
import sys


def main():
    mapping_file = pathlib.Path(sys.argv[1])
    src_dir = pathlib.Path(sys.argv[2])

    pairs = []
    for line in mapping_file.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.strip().startswith("#"):
            continue
        en, zh = line.split("\t", 1)
        pairs.append((en.strip(), zh.strip()))

    total = 0
    replaced = set()
    for f in sorted(list(src_dir.rglob("*.c")) + list(src_dir.rglob("*.h"))):
        text = f.read_text(encoding="utf-8")
        orig = text
        # CategoriesPanel.c names are display-only setup page labels (never used
        # for htoprc lookups), so they may be translated; everywhere else .name
        # values are config lookup keys and must stay English.
        guard = "" if f.name == "CategoriesPanel.c" else r"(?<!\.name = )"
        for en, zh in pairs:
            # "(\s*)EN(\s*)" keeps any alignment padding around the literal.
            # The .name guard skips internal identifiers: htop resolves
            # columns/meters/settings from htoprc BY .name, so translating those
            # silently drops the entry from the user's layout (a missing meter
            # or column looks exactly like "one item not displayed"). Only
            # visible text (titles, labels, dialogs) may be translated.
            pat = re.compile(guard + r'"(\s*)' + re.escape(en) + r'(\s*)"')

            def rep(m, zh=zh):
                return '"' + m.group(1) + zh + m.group(2) + '"'

            text, n = pat.subn(rep, text)
            if n:
                total += n
                replaced.add(en)
        if text != orig:
            f.write_text(text, encoding="utf-8")

    # 覆盖率报告：一条都没命中的词条说明源码措辞变了，值得复查
    missed = [en for en, _ in pairs if en not in replaced]
    print(f"[htop-zh] replaced {total} string occurrences")
    if missed:
        print(f"[htop-zh] WARNING, {len(missed)} entries never matched (source wording?):")
        for en in missed:
            print(f"[htop-zh]   - {en}")


if __name__ == "__main__":
    main()
