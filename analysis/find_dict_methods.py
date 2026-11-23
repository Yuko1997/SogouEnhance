#!/usr/bin/env python3
"""Locate Sogou user-dictionary JNI methods in extracted DEX files."""

import argparse
import importlib.util
from pathlib import Path


TARGET_METHODS = {
    "buildOtherUsrDict",
    "changeUsrDict2UUD",
    "checkUploadUsrDict",
    "importTraditionalEnUserDict",
    "learnOldVersionWord",
    "mergeUUD2UsrDict",
    "saveUserDict",
}


def load_dexutil(path):
    spec = importlib.util.spec_from_file_location("apk_reverse_dexutil", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dex_dir", type=Path)
    parser.add_argument(
        "--dexutil",
        type=Path,
        default=Path(
            r"C:\Users\Sakiyuri\.codex\skills\apk-reverse\scripts\dexutil.py"
        ),
    )
    args = parser.parse_args()

    dexutil = load_dexutil(args.dexutil)
    matches = 0
    for dex_path in sorted(args.dex_dir.glob("classes*.dex")):
        dex = dexutil.Dex(dex_path.read_bytes(), dex_path.name)
        problems = dex.check()
        if problems:
            raise SystemExit(f"{dex_path}: invalid dex: {'; '.join(problems)}")

        for class_index in range(dex.header["class_defs_size"]):
            class_def_off = dex.header["class_defs_off"] + class_index * 32
            class_name = dex.type_(dexutil.u32(dex.d, class_def_off))
            for section, method_idx, owner, name, desc, code_off in dex.methods_at(
                class_def_off
            ):
                if name not in TARGET_METHODS:
                    continue
                matches += 1
                print(
                    f"{dex_path.name}\t{owner}\t{name}\t{desc}\t"
                    f"section={section}\tmethod_idx={method_idx}\t"
                    f"code_off=0x{code_off:x}"
                )

    print(f"MATCHES={matches}")
    return 0 if matches else 1


if __name__ == "__main__":
    raise SystemExit(main())
