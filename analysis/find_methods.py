#!/usr/bin/env python3
"""List DEX methods whose owner or name matches a regular expression."""

import argparse
import importlib.util
import re
from pathlib import Path


def load_dexutil(path):
    spec = importlib.util.spec_from_file_location("apk_reverse_dexutil", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dex_dir", type=Path)
    parser.add_argument("pattern")
    parser.add_argument(
        "--dexutil",
        type=Path,
        default=Path(
            r"C:\Users\Sakiyuri\.codex\skills\apk-reverse\scripts\dexutil.py"
        ),
    )
    args = parser.parse_args()

    dexutil = load_dexutil(args.dexutil)
    pattern = re.compile(args.pattern, re.IGNORECASE)
    count = 0
    for dex_path in sorted(args.dex_dir.glob("classes*.dex")):
        dex = dexutil.Dex(dex_path.read_bytes(), dex_path.name)
        for class_index in range(dex.header["class_defs_size"]):
            class_def_off = dex.header["class_defs_off"] + class_index * 32
            for section, method_idx, owner, name, desc, code_off in dex.methods_at(
                class_def_off
            ):
                text = f"{owner}->{name}{desc}"
                if not pattern.search(text):
                    continue
                count += 1
                print(
                    f"{dex_path.name}\t{section}\t{text}\t"
                    f"method_idx={method_idx}\tcode_off=0x{code_off:x}"
                )
    print(f"MATCHES={count}")


if __name__ == "__main__":
    main()
