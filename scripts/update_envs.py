#!/usr/bin/env python3
"""Copy LOGIN_NAME to AGENT_TRACE_REMOTE_INSTANCE_ID in user envs.json files.

The script walks the controller-mounted personalData directory, skips the
public template directory, and updates:

    <userId>/working.secret/envs.json

If LOGIN_NAME exists, its JSON value is copied to
AGENT_TRACE_REMOTE_INSTANCE_ID. The target key is inserted immediately after
LOGIN_NAME when it does not exist. Files without LOGIN_NAME are skipped.

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


SOURCE_KEY = "LOGIN_NAME"
TARGET_KEY = "AGENT_TRACE_REMOTE_INSTANCE_ID"
RESULT_CHANGED = "changed"
RESULT_UNCHANGED = "unchanged"
RESULT_SOURCE_MISSING = "source-missing"


@dataclass(frozen=True)
class JsonProperty:
    key: str
    item_start: int
    value_start: int
    value_end: int
    comma_index: int | None
    next_index: int
    key_indent: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Copy LOGIN_NAME to AGENT_TRACE_REMOTE_INSTANCE_ID in user "
            "working.secret/envs.json files."
        )
    )
    parser.add_argument(
        "--personal-data-root",
        default="/qwenpaw_nas/personalData",
        help="controller-mounted personalData root",
    )
    parser.add_argument(
        "--file-relative-path",
        default="working.secret/envs.json",
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
    parser.add_argument(
        "--apply",
        action="store_true",
        help="actually update files; default is dry-run",
    )
    parser.add_argument(
        "--backup",
        action="store_true",
        help="create envs.json.bak-YYYYmmddHHMMSS before writing",
    )
    parser.add_argument(
        "--show-unchanged",
        action="store_true",
        help="print users whose target value already matches LOGIN_NAME",
    )
    parser.add_argument(
        "--restart-pods",
        action="store_true",
        help="after a successful --apply run, delete matching QwenPaw pods so Deployments recreate them",
    )
    parser.add_argument(
        "--namespace",
        default="ai",
        help="Kubernetes namespace used when --restart-pods is set",
    )
    parser.add_argument(
        "--kubectl",
        default="kubectl",
        help="kubectl executable path",
    )
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
    template_name = validate_user_id(args.public_template_sub_path)
    if args.user:
        users = [validate_user_id(user) for user in args.user]
        return sorted(user for user in dict.fromkeys(users) if user != template_name)

    root = Path(args.personal_data_root)
    if not root.is_dir():
        raise FileNotFoundError(f"personalData root not found: {root}")

    return sorted(
        path.name
        for path in root.iterdir()
        if path.is_dir() and path.name != template_name
    )


def target_file(args: argparse.Namespace, user_id: str) -> Path:
    root = Path(args.personal_data_root).resolve()
    user_root = (root / user_id).resolve()
    if not is_relative_to(user_root, root):
        raise ValueError(f"user directory escapes personalData root: {user_root}")

    file_path = (user_root / args.file_relative_path).resolve()
    if not is_relative_to(file_path, user_root):
        raise ValueError(f"target path escapes user directory: {file_path}")
    return file_path


def is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def patch_envs_text(text: str) -> tuple[str, str]:
    original_json = json.loads(text)
    if not isinstance(original_json, dict):
        raise ValueError("root JSON value must be an object")

    props = parse_top_level_properties(text)
    source = require_unique_top_level_key(props, SOURCE_KEY, required=False)
    target = require_unique_top_level_key(props, TARGET_KEY, required=False)
    if source is None:
        return text, RESULT_SOURCE_MISSING

    source_value = original_json[SOURCE_KEY]
    if target is not None and original_json[TARGET_KEY] == source_value:
        return text, RESULT_UNCHANGED

    source_value_text = text[source.value_start : source.value_end]
    if target is not None:
        updated_text = (
            text[: target.value_start]
            + source_value_text
            + text[target.value_end :]
        )
    else:
        updated_text = insert_property_after(
            text,
            source,
            TARGET_KEY,
            source_value_text,
        )

    updated_json = json.loads(updated_text)
    expected_json = copy.deepcopy(original_json)
    expected_json[TARGET_KEY] = copy.deepcopy(source_value)
    if updated_json != expected_json:
        raise RuntimeError(f"safety check failed: values other than {TARGET_KEY} changed")
    if updated_json[TARGET_KEY] != updated_json[SOURCE_KEY]:
        raise RuntimeError(f"safety check failed: {TARGET_KEY} does not match {SOURCE_KEY}")

    return updated_text, RESULT_CHANGED


def insert_property_after(
    text: str,
    previous: JsonProperty,
    key: str,
    value_text: str,
) -> str:
    newline = "\r\n" if "\r\n" in text else "\n"
    property_text = f"{json.dumps(key, ensure_ascii=False)}: {value_text}"

    if previous.comma_index is None:
        insertion = f",{newline}{previous.key_indent}{property_text}"
        return text[: previous.value_end] + insertion + text[previous.value_end :]

    insertion_indent = line_indent(text, previous.next_index) or previous.key_indent
    insertion = f"{property_text},{newline}{insertion_indent}"
    return text[: previous.next_index] + insertion + text[previous.next_index :]


def parse_top_level_properties(text: str) -> list[JsonProperty]:
    decoder = json.JSONDecoder()
    index = skip_ws(text, 0)
    if index >= len(text) or text[index] != "{":
        raise ValueError("root JSON value must be an object")

    index = skip_ws(text, index + 1)
    if index < len(text) and text[index] == "}":
        return []

    props: list[JsonProperty] = []
    while index < len(text):
        key_start = index
        parsed_key, key_end = decoder.raw_decode(text, key_start)
        if not isinstance(parsed_key, str):
            raise ValueError("expected object key string")

        colon = skip_ws(text, key_end)
        if colon >= len(text) or text[colon] != ":":
            raise ValueError(f"expected ':' after key {parsed_key!r}")

        value_start = skip_ws(text, colon + 1)
        _, value_end = decoder.raw_decode(text, value_start)
        after_value = skip_ws(text, value_end)
        comma_index: int | None = None
        next_index = after_value

        if after_value < len(text) and text[after_value] == ",":
            comma_index = after_value
            next_index = skip_ws(text, after_value + 1)
            if next_index < len(text) and text[next_index] == "}":
                raise ValueError("trailing comma is not supported")
        elif after_value < len(text) and text[after_value] == "}":
            next_index = after_value
        else:
            raise ValueError(f"expected ',' or '}}' after key {parsed_key!r}")

        props.append(
            JsonProperty(
                key=parsed_key,
                item_start=key_start,
                value_start=value_start,
                value_end=value_end,
                comma_index=comma_index,
                next_index=next_index,
                key_indent=line_indent(text, key_start),
            )
        )

        if comma_index is None:
            break
        index = next_index

    return props


def require_unique_top_level_key(
    props: list[JsonProperty],
    key: str,
    required: bool = True,
) -> JsonProperty | None:
    matches = [prop for prop in props if prop.key == key]
    if len(matches) > 1:
        raise ValueError(f"duplicate top-level key: {key}")
    if not matches:
        if required:
            raise ValueError(f"top-level key not found: {key}")
        return None
    return matches[0]


def skip_ws(text: str, index: int) -> int:
    while index < len(text) and text[index] in " \t\r\n":
        index += 1
    return index


def line_indent(text: str, index: int) -> str:
    line_start = text.rfind("\n", 0, index) + 1
    indent = text[line_start:index]
    return indent if indent.strip() == "" else ""


def read_text_preserving_newlines(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read()


def write_text_atomic(path: Path, text: str) -> None:
    original_stat = path.stat()
    tmp_path = path.with_name(path.name + ".tmp")
    try:
        with tmp_path.open("w", encoding="utf-8", newline="") as stream:
            stream.write(text)
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
    print(
        f"[RESTART] deleting pods with selector {args.pod_label_selector!r} "
        f"in namespace {args.namespace!r}"
    )
    result = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
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
    source_missing = 0
    missing_files = 0
    errors = 0

    for user_id in users:
        try:
            path = target_file(args, user_id)
            if not path.is_file():
                print(f"[MISSING] {user_id}: {path}")
                missing_files += 1
                continue

            text = read_text_preserving_newlines(path)
            updated_text, result = patch_envs_text(text)
            if result == RESULT_SOURCE_MISSING:
                source_missing += 1
                print(f"[SKIP] {user_id}: {SOURCE_KEY} not found: {path}")
                continue
            if result == RESULT_UNCHANGED:
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
            print(f"[ERROR] {user_id}: {exc}", file=sys.stderr)

    mode = "apply" if args.apply else "dry-run"
    print(
        f"[SUMMARY] mode={mode} changed={changed} unchanged={unchanged} "
        f"source_missing={source_missing} missing_files={missing_files} errors={errors}"
    )

    if args.restart_pods:
        if not args.apply:
            print("[DRY-RUN] would restart pods after applying file updates")
        elif errors:
            print("[SKIP] not restarting pods because file update errors occurred", file=sys.stderr)
        elif changed == 0:
            print("[SKIP] no files updated; not restarting pods")
        elif not restart_matching_pods(args):
            return 1

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
