#!/usr/bin/env python3
"""Set compact_threshold_ratio in every user's default agent.json.

The script walks the controller-mounted personalData directory, skips the
public template directory, and updates:

    <userId>/working/workspaces/default/agent.json

Only this nested value is changed:

    running.light_context_config.context_compact_config.compact_threshold_ratio

Default mode is dry-run. Add --apply to write files.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import shutil
import stat
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


TARGET_COMPACT_THRESHOLD_RATIO = 0.5
TARGET_KEY_PATH = (
    "running",
    "light_context_config",
    "context_compact_config",
    "compact_threshold_ratio",
)


@dataclass(frozen=True)
class JsonProperty:
    key: str
    value_start: int
    value_end: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Update compact_threshold_ratio in user agent.json files."
    )
    parser.add_argument(
        "--personal-data-root",
        default="/qwenpaw_nas/personalData",
        help="controller-mounted personalData root",
    )
    parser.add_argument(
        "--file-relative-path",
        default="working/workspaces/default/agent.json",
        help="path below each user directory",
    )
    parser.add_argument(
        "--public-template-sub-path",
        default="public-secret",
        help="template directory name to skip",
    )
    parser.add_argument(
        "--user",
        action="append",
        default=[],
        help="specific userId to update; repeat for multiple users. Defaults to all user dirs.",
    )
    parser.add_argument("--apply", action="store_true", help="actually update files; default is dry-run")
    parser.add_argument(
        "--backup",
        action="store_true",
        help="create agent.json.bak-YYYYmmddHHMMSS before writing",
    )
    parser.add_argument("--show-unchanged", action="store_true", help="print users that are already up to date")
    parser.add_argument(
        "--restart-pods",
        action="store_true",
        help="after a successful --apply run, delete matching QwenPaw pods so Deployments recreate them",
    )
    parser.add_argument("--namespace", default="ai", help="Kubernetes namespace used when --restart-pods is set")
    parser.add_argument("--kubectl", default="kubectl", help="kubectl executable path")
    parser.add_argument("--kubeconfig", help="kubeconfig path passed to kubectl")
    parser.add_argument("--context", help="kubectl context name")
    parser.add_argument(
        "--pod-label-selector",
        default="app=qwenpaw",
        help="pod label selector used when --restart-pods is set",
    )
    return parser.parse_args()


def validate_user_id(user_id: str) -> str:
    normalized = user_id.strip()
    user_segment = Path(normalized)
    if not normalized or user_segment.is_absolute() or len(user_segment.parts) != 1:
        raise ValueError(f"invalid userId: {user_id!r}")
    if "/" in normalized or "\\" in normalized or normalized in {".", ".."}:
        raise ValueError(f"invalid userId: {user_id!r}")
    return normalized


def user_ids(args: argparse.Namespace) -> list[str]:
    if args.user:
        return sorted(dict.fromkeys(validate_user_id(user) for user in args.user))

    root = Path(args.personal_data_root)
    if not root.is_dir():
        raise FileNotFoundError(f"personalData root not found: {root}")

    return sorted(
        path.name
        for path in root.iterdir()
        if path.is_dir() and path.name != args.public_template_sub_path
    )


def target_file(args: argparse.Namespace, user_id: str) -> Path:
    root = Path(args.personal_data_root).resolve()
    file_path = (root / user_id / args.file_relative_path).resolve()
    if not is_relative_to(file_path, root):
        raise ValueError(f"target path escapes personalData root: {file_path}")
    return file_path


def is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def patch_agent_text(text: str) -> tuple[str, bool]:
    original_json = json.loads(text)
    if not isinstance(original_json, dict):
        raise ValueError("root JSON value must be an object")

    target = find_nested_property(text, TARGET_KEY_PATH)
    current_value = json.loads(text[target.value_start : target.value_end])
    if isinstance(current_value, bool) or not isinstance(current_value, (int, float)):
        raise ValueError(
            f"{'.'.join(TARGET_KEY_PATH)} must be a number, got {type(current_value).__name__}"
        )
    if current_value == TARGET_COMPACT_THRESHOLD_RATIO:
        return text, False

    updated_text = (
        text[: target.value_start]
        + str(TARGET_COMPACT_THRESHOLD_RATIO)
        + text[target.value_end :]
    )
    updated_json = json.loads(updated_text)

    expected_json = copy.deepcopy(original_json)
    current = expected_json
    for key in TARGET_KEY_PATH[:-1]:
        current = current[key]
    current[TARGET_KEY_PATH[-1]] = TARGET_COMPACT_THRESHOLD_RATIO

    if updated_json != expected_json:
        raise RuntimeError("safety check failed: fields other than compact_threshold_ratio changed")
    return updated_text, True


def find_nested_property(text: str, key_path: tuple[str, ...]) -> JsonProperty:
    object_text = text
    absolute_offset = 0

    for depth, key in enumerate(key_path):
        props = parse_object_properties(object_text)
        prop = require_unique_key(props, key, key_path[:depth])
        absolute_prop = JsonProperty(
            key=prop.key,
            value_start=absolute_offset + prop.value_start,
            value_end=absolute_offset + prop.value_end,
        )
        if depth == len(key_path) - 1:
            return absolute_prop

        nested_value = json.loads(object_text[prop.value_start : prop.value_end])
        if not isinstance(nested_value, dict):
            location = ".".join(key_path[: depth + 1])
            raise ValueError(f"{location} must be an object")
        absolute_offset += prop.value_start
        object_text = object_text[prop.value_start : prop.value_end]

    raise AssertionError("key path must not be empty")


def parse_object_properties(text: str) -> list[JsonProperty]:
    decoder = json.JSONDecoder()
    index = skip_ws(text, 0)
    if index >= len(text) or text[index] != "{":
        raise ValueError("expected a JSON object")

    index = skip_ws(text, index + 1)
    if index < len(text) and text[index] == "}":
        return []

    props: list[JsonProperty] = []
    while index < len(text):
        parsed_key, key_end = decoder.raw_decode(text, index)
        if not isinstance(parsed_key, str):
            raise ValueError("expected object key string")

        colon = skip_ws(text, key_end)
        if colon >= len(text) or text[colon] != ":":
            raise ValueError(f"expected ':' after key {parsed_key!r}")

        value_start = skip_ws(text, colon + 1)
        _, value_end = decoder.raw_decode(text, value_start)
        props.append(JsonProperty(parsed_key, value_start, value_end))

        after_value = skip_ws(text, value_end)
        if after_value < len(text) and text[after_value] == ",":
            index = skip_ws(text, after_value + 1)
            if index < len(text) and text[index] == "}":
                raise ValueError("trailing comma is not supported")
        elif after_value < len(text) and text[after_value] == "}":
            return props
        else:
            raise ValueError(f"expected ',' or '}}' after key {parsed_key!r}")

    raise ValueError("unterminated JSON object")


def require_unique_key(
    props: list[JsonProperty],
    key: str,
    parent_path: tuple[str, ...],
) -> JsonProperty:
    matches = [prop for prop in props if prop.key == key]
    location = ".".join((*parent_path, key))
    if len(matches) > 1:
        raise ValueError(f"duplicate key: {location}")
    if not matches:
        raise ValueError(f"key not found: {location}")
    return matches[0]


def skip_ws(text: str, index: int) -> int:
    while index < len(text) and text[index] in " \t\r\n":
        index += 1
    return index


def write_text_atomic(path: Path, text: str) -> None:
    original_stat = path.stat()
    tmp_path = path.with_name(path.name + ".tmp")
    try:
        tmp_path.write_text(text, encoding="utf-8")
        os.chmod(tmp_path, stat.S_IMODE(original_stat.st_mode))
        os.replace(tmp_path, path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


def backup_file(path: Path) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    backup_path = path.with_name(f"{path.name}.bak-{timestamp}")
    shutil.copy2(path, backup_path)
    return backup_path


def kubectl_base_command(args: argparse.Namespace) -> list[str]:
    command = [args.kubectl]
    if args.kubeconfig:
        command.extend(["--kubeconfig", args.kubeconfig])
    if args.context:
        command.extend(["--context", args.context])
    return command


def restart_matching_pods(args: argparse.Namespace) -> bool:
    command = kubectl_base_command(args)
    command.extend(
        [
            "-n",
            args.namespace,
            "delete",
            "pod",
            "-l",
            args.pod_label_selector,
            "--wait=false",
        ]
    )
    print(f"[RESTART] deleting pods with selector {args.pod_label_selector!r} in namespace {args.namespace!r}")
    result = subprocess.run(command, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.stdout.strip():
        print(result.stdout.strip())
    if result.returncode != 0:
        if result.stderr.strip():
            print(result.stderr.strip(), file=sys.stderr)
        print(f"[ERROR] failed to restart pods, exit_code={result.returncode}", file=sys.stderr)
        return False
    return True


def main() -> int:
    args = parse_args()
    try:
        users = user_ids(args)
    except Exception as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 2

    changed = 0
    unchanged = 0
    missing = 0
    errors = 0

    for user_id in users:
        path = target_file(args, user_id)
        if not path.is_file():
            print(f"[MISSING] {user_id}: {path}")
            missing += 1
            continue

        try:
            text = path.read_text(encoding="utf-8")
            updated_text, should_update = patch_agent_text(text)
            if not should_update:
                unchanged += 1
                if args.show_unchanged:
                    print(f"[UNCHANGED] {user_id}: {path}")
                continue

            changed += 1
            if args.apply:
                if args.backup:
                    backup_path = backup_file(path)
                    print(f"[BACKUP] {user_id}: {backup_path}")
                write_text_atomic(path, updated_text)
                print(f"[UPDATED] {user_id}: {path}")
            else:
                print(f"[DRY-RUN] would update {user_id}: {path}")
        except Exception as exc:
            errors += 1
            print(f"[ERROR] {user_id}: {path}: {exc}", file=sys.stderr)

    mode = "apply" if args.apply else "dry-run"
    print(
        f"[SUMMARY] mode={mode} changed={changed} unchanged={unchanged} "
        f"missing={missing} errors={errors}"
    )

    if args.restart_pods:
        if not args.apply:
            print("[DRY-RUN] would restart pods; add --apply to actually restart")
        elif errors:
            print("[SKIP] not restarting pods because file update errors occurred", file=sys.stderr)
        elif not restart_matching_pods(args):
            return 1

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
