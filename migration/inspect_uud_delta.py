#!/usr/bin/env python3
"""Describe UUD merge deltas without printing dictionary words."""

import argparse
import hashlib
import importlib.util
from pathlib import Path


def load_compare_module(path):
    spec = importlib.util.spec_from_file_location("sogou_compare_uud", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def shape(value):
    if all(ord(ch) < 128 for ch in value):
        return "ascii"
    if all("\u4e00" <= ch <= "\u9fff" for ch in value):
        return "cjk"
    return "mixed"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination_baseline", type=Path)
    parser.add_argument("destination_merged", type=Path)
    parser.add_argument(
        "--compare-script",
        type=Path,
        default=Path(__file__).with_name("compare_uud.py"),
    )
    args = parser.parse_args()

    compare = load_compare_module(args.compare_script)
    source = compare.parse_uud(args.source)
    baseline = compare.parse_uud(args.destination_baseline)
    merged = compare.parse_uud(args.destination_merged)

    source_by_key = {record["key"]: record for record in source["records_list"]}
    baseline_by_key = {record["key"]: record for record in baseline["records_list"]}
    missing = (source["keys"] | baseline["keys"]) - merged["keys"]

    for key in sorted(missing):
        record = source_by_key.get(key) or baseline_by_key[key]
        origin = (
            "both"
            if key in source_by_key and key in baseline_by_key
            else "source"
            if key in source_by_key
            else "baseline"
        )
        pinyin, word = key
        attributes = "\x1f".join(record["attributes"])
        print(
            f"origin={origin}\t"
            f"pinyin_kind={'bracket' if pinyin.startswith('[') else shape(pinyin)}\t"
            f"pinyin_len={len(pinyin)}\tword_kind={shape(word)}\t"
            f"word_len={len(word)}\tfields={len(record['attributes']) + 2}\t"
            f"pinyin_equals_word={str(pinyin == word).lower()}\t"
            f"attrs={attributes!r}\t"
            f"record_sha256={hashlib.sha256((pinyin + chr(9) + word).encode('utf-8')).hexdigest()[:16]}"
        )

    print(f"MISSING={len(missing)}")


if __name__ == "__main__":
    main()
