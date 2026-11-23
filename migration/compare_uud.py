#!/usr/bin/env python3
"""Compare Sogou UUD text exports without emitting user dictionary content."""

import argparse
import hashlib
import json
from pathlib import Path


def value_kind(value):
    if value and all(ord(ch) < 128 for ch in value):
        return "ascii"
    if value and all("\u4e00" <= ch <= "\u9fff" for ch in value):
        return "cjk"
    return "mixed"


def kind_counts(keys):
    counts = {}
    for _pinyin, word in keys:
        kind = value_kind(word)
        counts[kind] = counts.get(kind, 0) + 1
    return counts


def parse_uud(path):
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe"):
        text = raw[2:].decode("utf-16-le", "strict")
    else:
        text = raw.decode("utf-16-le", "strict")

    records = []
    malformed = 0
    for line_number, line in enumerate(text.splitlines(), 1):
        line = line.rstrip("\r")
        if not line.strip():
            continue
        if line_number == 1 and line.startswith("FormatVersion="):
            continue
        fields = line.split("\t")
        if len(fields) < 2:
            malformed += 1
            continue
        pinyin = fields[0]
        word = fields[1]
        attributes = tuple(fields[2:])
        records.append(
            {
                "line": line_number,
                "key": (pinyin, word),
                "pinyin": pinyin,
                "word": word,
                "attributes": attributes,
            }
        )

    keys = [record["key"] for record in records]
    return {
        "path": str(path),
        "bytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "records": len(records),
        "unique_keys": len(set(keys)),
        "duplicate_keys": len(keys) - len(set(keys)),
        "malformed_lines": malformed,
        "keys": set(keys),
        "word_kind_counts": kind_counts(keys),
        "records_list": records,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination_baseline", type=Path)
    parser.add_argument("destination_merged", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    source = parse_uud(args.source)
    baseline = parse_uud(args.destination_baseline)
    merged = parse_uud(args.destination_merged)

    source_only = source["keys"] - baseline["keys"]
    baseline_only = baseline["keys"] - source["keys"]
    expected_union = source["keys"] | baseline["keys"]
    missing_from_merged = expected_union - merged["keys"]
    extra_in_merged = merged["keys"] - expected_union
    merged_by_word = {}
    merged_by_pinyin = {}
    for pinyin, word in merged["keys"]:
        merged_by_word.setdefault(word, set()).add(pinyin)
        merged_by_pinyin.setdefault(pinyin, set()).add(word)

    missing_same_word = 0
    missing_same_pinyin = 0
    missing_neither = 0
    for pinyin, word in missing_from_merged:
        if word in merged_by_word:
            missing_same_word += 1
        elif pinyin in merged_by_pinyin:
            missing_same_pinyin += 1
        else:
            missing_neither += 1
    missing_key_kinds = kind_counts(missing_from_merged)
    unexpected_key_kinds = kind_counts(extra_in_merged)
    missing_non_ascii = sum(
        count for kind, count in missing_key_kinds.items() if kind != "ascii"
    )
    semantic_union_match_non_ascii = (
        missing_non_ascii == 0 and not extra_in_merged
    )

    report = {
        "source": {k: source[k] for k in source if k not in ("keys", "records_list")},
        "destination_baseline": {
            k: baseline[k] for k in baseline if k not in ("keys", "records_list")
        },
        "destination_merged": {
            k: merged[k] for k in merged if k not in ("keys", "records_list")
        },
        "source_only_keys": len(source_only),
        "destination_only_keys": len(baseline_only),
        "expected_union_keys": len(expected_union),
        "missing_from_merged": len(missing_from_merged),
        "extra_in_merged": len(extra_in_merged),
        "missing_same_word": missing_same_word,
        "missing_same_pinyin": missing_same_pinyin,
        "missing_neither": missing_neither,
        "missing_key_kinds": missing_key_kinds,
        "unexpected_key_kinds": unexpected_key_kinds,
        "source_word_kinds": source["word_kind_counts"],
        "destination_baseline_word_kinds": baseline["word_kind_counts"],
        "destination_merged_word_kinds": merged["word_kind_counts"],
        "union_preserved": not missing_from_merged,
        "no_unexpected_keys": not extra_in_merged,
        "semantic_union_match": not missing_from_merged and not extra_in_merged,
        "semantic_union_match_non_ascii": semantic_union_match_non_ascii,
    }

    if args.json_out:
        args.json_out.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    for name in ("source", "destination_baseline", "destination_merged"):
        item = report[name]
        print(
            f"{name}: records={item['records']} unique={item['unique_keys']} "
            f"duplicates={item['duplicate_keys']} malformed={item['malformed_lines']} "
            f"bytes={item['bytes']} sha256={item['sha256']}"
        )
    print(
        f"source_only={len(source_only)} destination_only={len(baseline_only)} "
        f"expected_union={len(expected_union)} "
        f"missing={len(missing_from_merged)} extra={len(extra_in_merged)} "
        f"missing_same_word={missing_same_word} "
        f"missing_same_pinyin={missing_same_pinyin} "
        f"missing_neither={missing_neither} "
        f"semantic_union_match={report['semantic_union_match']} "
        f"semantic_union_match_non_ascii={report['semantic_union_match_non_ascii']}"
    )
    print(
        f"source_kinds={source['word_kind_counts']} "
        f"baseline_kinds={baseline['word_kind_counts']} "
        f"merged_kinds={merged['word_kind_counts']}"
    )
    print(
        f"missing_kinds={missing_key_kinds} "
        f"unexpected_kinds={unexpected_key_kinds}"
    )
    return 0 if report["semantic_union_match_non_ascii"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
