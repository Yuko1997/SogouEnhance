#!/usr/bin/env python3
"""Find DEX methods whose decoded instructions reference selected strings."""

import argparse
import importlib.util
from pathlib import Path


def load_dexutil(path):
    spec = importlib.util.spec_from_file_location("apk_reverse_dexutil", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dex_dir", type=Path)
    parser.add_argument("needles", nargs="+")
    parser.add_argument(
        "--dexutil",
        type=Path,
        default=Path(
            r"C:\Users\Sakiyuri\.codex\skills\apk-reverse\scripts\dexutil.py"
        ),
    )
    args = parser.parse_args()

    dexutil = load_dexutil(args.dexutil)
    needle_set = set(args.needles)
    matches = 0

    for dex_path in sorted(args.dex_dir.glob("classes*.dex")):
        dex = dexutil.Dex(dex_path.read_bytes(), dex_path.name)
        for class_index in range(dex.header["class_defs_size"]):
            class_def_off = dex.header["class_defs_off"] + class_index * 32
            for _section, _idx, owner, name, desc, code_off in dex.methods_at(
                class_def_off
            ):
                if not code_off:
                    continue
                hits = []
                for insn in dex.decode(code_off):
                    if insn["op"] not in (0x1A, 0x1B):
                        continue
                    if insn["op"] == 0x1A:
                        string_idx = dexutil.u16(dex.d, insn["off"] + 2)
                    else:
                        string_idx = dexutil.u32(dex.d, insn["off"] + 2)
                    try:
                        value = dex.string(string_idx)
                    except Exception:
                        continue
                    if value in needle_set:
                        hits.append((insn["off"], value))
                if not hits:
                    continue
                matches += 1
                for insn_off, value in hits:
                    print(
                        f"{dex_path.name}\t{owner}->{name}{desc}\t"
                        f"insn=0x{insn_off:x}\t{value}"
                    )

    print(f"MATCHES={matches}")
    return 0 if matches else 1


if __name__ == "__main__":
    raise SystemExit(main())
