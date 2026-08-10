#!/usr/bin/env python3
"""Set active_model in every user's default agent.json.

The script walks the controller-mounted personalData directory, skips the
public template directory, and updates:

    <userId>/working/workspaces/default/agent.json

It inserts or replaces the top-level active_model value immediately after the
top-level llm_routing value. Other JSON values are preserved.

Default mode is dry-run. Add --apply to write files.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


TARGET_ACTIVE_MODEL: dict[str, str] = {
    "provider_id": "personal-api-key",
    "model": "Fast-Model",
}


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
        description="Insert or replace active_model in user agent.json files."
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

    users: list[str] = []
    for path in root.iterdir():
        if path.is_dir() and path.name != args.public_template_sub_path:
            users.append(path.name)
    return sorted(users)


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

    props = parse_top_level_properties(text)
    require_unique_top_level_key(props, "llm_routing")
    require_unique_top_level_key(props, "active_model", required=False)

    if (
        original_json.get("active_model") == TARGET_ACTIVE_MODEL
        and is_active_model_after_llm_routing(props)
    ):
        return text, False

    text_without_active_model = remove_top_level_property(text, props, "active_model")
    props_without_active_model = parse_top_level_properties(text_without_active_model)
    llm_routing = require_unique_top_level_key(props_without_active_model, "llm_routing")

    newline = "\r\n" if "\r\n" in text_without_active_model else "\n"
    active_model_value = format_json_value(TARGET_ACTIVE_MODEL, llm_routing.key_indent, newline)
    active_model_property = f'"active_model": {active_model_value}'

    if llm_routing.comma_index is None:
        insertion = f",{newline}{llm_routing.key_indent}{active_model_property}"
        updated_text = (
            text_without_active_model[: llm_routing.value_end]
            + insertion
            + text_without_active_model[llm_routing.value_end :]
        )
    else:
        insertion_indent = line_indent(text_without_active_model, llm_routing.next_index) or llm_routing.key_indent
        insertion = f"{active_model_property},{newline}{insertion_indent}"
        updated_text = (
            text_without_active_model[: llm_routing.next_index]
            + insertion
            + text_without_active_model[llm_routing.next_index :]
        )

    updated_json = json.loads(updated_text)
    original_without_active_model = dict(original_json)
    updated_without_active_model = dict(updated_json)
    original_without_active_model.pop("active_model", None)
    updated_without_active_model.pop("active_model", None)
    if original_without_active_model != updated_without_active_model:
        raise RuntimeError("safety check failed: fields other than active_model changed")
    if updated_json.get("active_model") != TARGET_ACTIVE_MODEL:
        raise RuntimeError("safety check failed: active_model was not updated")
    if not is_active_model_after_llm_routing(parse_top_level_properties(updated_text)):
        raise RuntimeError("safety check failed: active_model was not inserted after llm_routing")

    return updated_text, True


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


def is_active_model_after_llm_routing(props: list[JsonProperty]) -> bool:
    keys = [prop.key for prop in props]
    try:
        return keys.index("active_model") == keys.index("llm_routing") + 1
    except ValueError:
        return False


def remove_top_level_property(text: str, props: list[JsonProperty], key: str) -> str:
    target = require_unique_top_level_key(props, key, required=False)
    if target is None:
        return text

    target_index = props.index(target)
    if target.comma_index is not None:
        return text[: target.item_start] + text[target.next_index :]

    if target_index > 0:
        previous = props[target_index - 1]
        if previous.comma_index is None:
            raise ValueError(f"cannot remove key {key!r}; previous comma not found")
        return text[: previous.comma_index] + text[target.value_end :]

    return text[: target.item_start] + text[target.value_end :]


def skip_ws(text: str, index: int) -> int:
    while index < len(text) and text[index] in " \t\r\n":
        index += 1
    return index


def line_indent(text: str, index: int) -> str:
    line_start = text.rfind("\n", 0, index) + 1
    indent = text[line_start:index]
    return indent if indent.strip() == "" else ""


def format_json_value(value: Any, key_indent: str, newline: str) -> str:
    dumped = json.dumps(value, ensure_ascii=False, indent=2)
    lines = dumped.splitlines()
    if len(lines) == 1:
        return dumped
    return lines[0] + newline + newline.join(key_indent + line for line in lines[1:])


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
