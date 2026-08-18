#!/usr/bin/env python3
"""List every Redis key and its TTL through kubectl exec.

The script is equivalent to entering the Redis pod, running ``KEYS *``, and
then running ``TTL <key>`` for every returned key. It deliberately does not
allocate a TTY because machine-readable output is more reliable without one.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path
from typing import TextIO


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="List all keys and their TTL values in Redis through kubectl exec."
    )
    parser.add_argument("--pod", default="redis-0", help="Redis pod name")
    parser.add_argument("--namespace", default="ai", help="Kubernetes namespace")
    parser.add_argument("--container", help="container name when the pod has multiple containers")
    parser.add_argument("--password", default="pawRedis@2026", help="Redis password")
    parser.add_argument("--database", type=int, default=0, help="Redis database number")
    parser.add_argument("--kubectl", default="kubectl", help="kubectl executable path")
    parser.add_argument("--kubeconfig", help="kubeconfig path passed to kubectl")
    parser.add_argument("--context", help="kubectl context name")
    parser.add_argument(
        "--format",
        choices=("table", "csv", "json"),
        help="output format; inferred from --output extension when omitted",
    )
    parser.add_argument(
        "--output",
        help="write results to this file instead of stdout, for example redis_ttl.csv",
    )
    return parser.parse_args()


def kubectl_prefix(args: argparse.Namespace) -> list[str]:
    command = [args.kubectl]
    if args.kubeconfig:
        command.extend(["--kubeconfig", args.kubeconfig])
    if args.context:
        command.extend(["--context", args.context])
    command.extend(["exec", "-i", args.pod, "-n", args.namespace])
    if args.container:
        command.extend(["-c", args.container])
    command.append("--")
    return command


def redis_cli(args: argparse.Namespace, *redis_command: str) -> str:
    command = kubectl_prefix(args)
    command.extend(
        [
            "redis-cli",
            "--no-auth-warning",
            "--raw",
            "-a",
            args.password,
            "-n",
            str(args.database),
            *redis_command,
        ]
    )
    result = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        message = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(message or f"command failed with exit code {result.returncode}")
    return result.stdout


def list_key_ttls(args: argparse.Namespace) -> list[tuple[str, int]]:
    # Passing "*" as a subprocess argument prevents the local shell from
    # expanding it. --raw returns one key per line for ordinary text keys.
    keys_output = redis_cli(args, "KEYS", "*")
    keys = keys_output.splitlines()

    rows: list[tuple[str, int]] = []
    for key in keys:
        ttl_output = redis_cli(args, "TTL", key).strip()
        try:
            ttl = int(ttl_output)
        except ValueError as error:
            raise RuntimeError(f"unexpected TTL response for key {key!r}: {ttl_output!r}") from error
        rows.append((key, ttl))
    return rows


def write_table(rows: list[tuple[str, int]], stream: TextIO) -> None:
    if not rows:
        print("No keys found.", file=stream)
        return

    key_width = max(len("KEY"), *(len(key) for key, _ in rows))
    print(f"{'KEY':<{key_width}}  TTL_SECONDS", file=stream)
    print(f"{'-' * key_width}  -----------", file=stream)
    for key, ttl in rows:
        ttl_text = "persistent (-1)" if ttl == -1 else "missing (-2)" if ttl == -2 else str(ttl)
        print(f"{key:<{key_width}}  {ttl_text}", file=stream)


def resolve_format(args: argparse.Namespace) -> str:
    if args.format:
        return args.format
    if args.output:
        suffix = Path(args.output).suffix.lower()
        if suffix in {".csv", ".json"}:
            return suffix[1:]
    return "table"


def write_output(
    output_format: str,
    rows: list[tuple[str, int]],
    stream: TextIO,
) -> None:
    if output_format == "json":
        json.dump(
            [{"key": key, "ttl_seconds": ttl} for key, ttl in rows],
            stream,
            ensure_ascii=False,
            indent=2,
        )
        print(file=stream)
        return

    if output_format == "csv":
        writer = csv.writer(stream)
        writer.writerow(["key", "ttl_seconds"])
        writer.writerows(rows)
        return

    write_table(rows, stream)


def save_output(args: argparse.Namespace, rows: list[tuple[str, int]]) -> None:
    output_format = resolve_format(args)
    if not args.output:
        write_output(output_format, rows, sys.stdout)
        return

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    # utf-8-sig adds a BOM for CSV so Chinese key names display correctly in Excel.
    encoding = "utf-8-sig" if output_format == "csv" else "utf-8"
    with output_path.open("w", encoding=encoding, newline="") as stream:
        write_output(output_format, rows, stream)
    print(f"Wrote {len(rows)} keys to {output_path}")


def main() -> int:
    args = parse_args()
    try:
        rows = list_key_ttls(args)
        save_output(args, rows)
    except (OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
