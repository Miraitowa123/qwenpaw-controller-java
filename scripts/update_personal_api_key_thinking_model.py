#!/usr/bin/env python3
"""Enable thinking for Thinking-Model in personal-api-key.json files.

The script updates both the public template and user workspaces under the
controller-mounted personalData directory:

    public-secret/working.secret/providers/custom/personal-api-key.json
    <userId>/working.secret/providers/custom/personal-api-key.json

Only these fields in the unique extra_models item whose id is Thinking-Model
are changed:

    config_overrides
    generate_kwargs

All other JSON values, including user-specific API keys and base URLs, are
preserved. Default mode is dry-run. Add --apply to write files.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import shutil
import stat
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


TARGET_MODEL_ID = "Thinking-Model"
TARGET_CONFIG_OVERRIDES = ["generate_kwargs"]
TARGET_GENERATE_KWARGS = {
    "extra_body": {
        "chat_template_kwargs": {
            "enable_thinking": True,
        }
    }
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Enable thinking for Thinking-Model in the public template and "
            "user personal-api-key.json files."
        )
    )
    parser.add_argument(
        "--personal-data-root",
        default="/qwenpaw_nas/personalData",
        help="controller-mounted personalData root",
    )
    parser.add_argument(
        "--file-relative-path",
        default="working.secret/providers/custom/personal-api-key.json",
        help="personal-api-key.json path below the template and each user directory",
    )
    parser.add_argument(
        "--public-template-sub-path",
        default="public-secret",
        help="public template directory name",
    )
    parser.add_argument(
        "--user",
        action="append",
        default=[],
        help=(
            "specific userId to update; repeat for multiple users. "
            "Defaults to all user directories; the public template is always included."
        ),
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="actually update files; default is dry-run",
    )
    parser.add_argument(
        "--backup",
        action="store_true",
        help="create personal-api-key.json.bak-YYYYmmddHHMMSS before writing",
    )
    parser.add_argument(
        "--show-unchanged",
        action="store_true",
        help="print files that are already up to date",
    )
    return parser.parse_args()


def validate_path_segment(value: str, description: str) -> str:
    normalized = value.strip()
    segment = Path(normalized)
    if not normalized or segment.is_absolute() or len(segment.parts) != 1:
        raise ValueError(f"invalid {description}: {value!r}")
    if "/" in normalized or "\\" in normalized or normalized in {".", ".."}:
        raise ValueError(f"invalid {description}: {value!r}")
    return normalized


def user_ids(args: argparse.Namespace, template_name: str) -> list[str]:
    if args.user:
        users = [validate_path_segment(user, "userId") for user in args.user]
        return sorted(user for user in dict.fromkeys(users) if user != template_name)

    root = Path(args.personal_data_root)
    return sorted(
        path.name
        for path in root.iterdir()
        if path.is_dir() and path.name != template_name
    )


def target_file(root: Path, owner: str, relative_path: str) -> Path:
    owner_root = (root / owner).resolve()
    if not is_relative_to(owner_root, root):
        raise ValueError(f"target directory escapes personalData root: {owner_root}")

    file_path = (owner_root / relative_path).resolve()
    if not is_relative_to(file_path, owner_root):
        raise ValueError(f"target path escapes owner directory: {file_path}")
    return file_path


def target_files(args: argparse.Namespace) -> list[tuple[str, Path]]:
    root = Path(args.personal_data_root).resolve()
    if not root.is_dir():
        raise FileNotFoundError(f"personalData root not found: {root}")

    template_name = validate_path_segment(
        args.public_template_sub_path,
        "public template directory",
    )
    targets = [
        (
            f"template:{template_name}",
            target_file(root, template_name, args.file_relative_path),
        )
    ]
    targets.extend(
        (
            f"user:{user_id}",
            target_file(root, user_id, args.file_relative_path),
        )
        for user_id in user_ids(args, template_name)
    )
    return targets


def is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def patch_thinking_model_text(text: str) -> tuple[str, bool]:
    original_json = json.loads(text)
    if not isinstance(original_json, dict):
        raise ValueError("root JSON value must be an object")

    extra_models = original_json.get("extra_models")
    if not isinstance(extra_models, list):
        raise ValueError("extra_models must be an array")

    matching_indexes = [
        index
        for index, model in enumerate(extra_models)
        if isinstance(model, dict) and model.get("id") == TARGET_MODEL_ID
    ]
    if len(matching_indexes) != 1:
        raise ValueError(
            f"expected exactly one extra_models item with id={TARGET_MODEL_ID!r}, "
            f"found {len(matching_indexes)}"
        )

    model_index = matching_indexes[0]
    original_model = extra_models[model_index]
    if (
        original_model.get("config_overrides") == TARGET_CONFIG_OVERRIDES
        and original_model.get("generate_kwargs") == TARGET_GENERATE_KWARGS
    ):
        return text, False

    updated_model: dict[str, Any] = {}
    inserted_target_fields = False
    for key, value in original_model.items():
        if key == "config_overrides":
            continue
        if key == "generate_kwargs":
            updated_model["config_overrides"] = copy.deepcopy(TARGET_CONFIG_OVERRIDES)
            updated_model["generate_kwargs"] = copy.deepcopy(TARGET_GENERATE_KWARGS)
            inserted_target_fields = True
        else:
            updated_model[key] = value

    if not inserted_target_fields:
        updated_model["config_overrides"] = copy.deepcopy(TARGET_CONFIG_OVERRIDES)
        updated_model["generate_kwargs"] = copy.deepcopy(TARGET_GENERATE_KWARGS)

    updated_extra_models = copy.deepcopy(extra_models)
    updated_extra_models[model_index] = updated_model

    start, end, key_indent = find_unique_top_level_value_span(text, "extra_models")
    newline = "\r\n" if "\r\n" in text else "\n"
    replacement = format_json_value(updated_extra_models, key_indent, newline)
    updated_text = text[:start] + replacement + text[end:]

    updated_json = json.loads(updated_text)
    expected_json = copy.deepcopy(original_json)
    expected_json["extra_models"] = updated_extra_models
    if updated_json != expected_json:
        raise RuntimeError("safety check failed: values other than Thinking-Model changed")

    return updated_text, True


def find_unique_top_level_value_span(text: str, key: str) -> tuple[int, int, str]:
    decoder = json.JSONDecoder()
    index = skip_ws(text, 0)
    if index >= len(text) or text[index] != "{":
        raise ValueError("root JSON value must be an object")

    index = skip_ws(text, index + 1)
    match: tuple[int, int, str] | None = None
    while index < len(text) and text[index] != "}":
        key_index = index
        parsed_key, key_end = decoder.raw_decode(text, key_index)
        if not isinstance(parsed_key, str):
            raise ValueError("expected object key string")

        colon = skip_ws(text, key_end)
        if colon >= len(text) or text[colon] != ":":
            raise ValueError(f"expected ':' after key {parsed_key!r}")

        value_start = skip_ws(text, colon + 1)
        _, value_end = decoder.raw_decode(text, value_start)
        if parsed_key == key:
            if match is not None:
                raise ValueError(f"duplicate top-level key: {key}")
            match = (value_start, value_end, line_indent(text, key_index))

        index = skip_ws(text, value_end)
        if index < len(text) and text[index] == ",":
            index = skip_ws(text, index + 1)
            continue
        if index < len(text) and text[index] == "}":
            break
        raise ValueError(f"expected ',' or '}}' after key {parsed_key!r}")

    if match is None:
        raise ValueError(f"top-level key not found: {key}")
    return match


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


def main() -> int:
    args = parse_args()
    try:
        targets = target_files(args)
    except Exception as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 2

    changed = 0
    unchanged = 0
    missing = 0
    errors = 0

    for label, path in targets:
        if not path.is_file():
            print(f"[MISSING] {label}: {path}")
            missing += 1
            continue

        try:
            text = read_text_preserving_newlines(path)
            updated_text, should_update = patch_thinking_model_text(text)
            if not should_update:
                unchanged += 1
                if args.show_unchanged:
                    print(f"[UNCHANGED] {label}: {path}")
                continue

            changed += 1
            if args.apply:
                if args.backup:
                    backup_path = backup_file(path)
                    print(f"[BACKUP] {label}: {backup_path}")
                write_text_atomic(path, updated_text)
                print(f"[UPDATED] {label}: {path}")
            else:
                print(f"[DRY-RUN] would update {label}: {path}")
        except Exception as exc:
            errors += 1
            print(f"[ERROR] {label}: {path}: {exc}", file=sys.stderr)

    mode = "apply" if args.apply else "dry-run"
    print(
        f"[SUMMARY] mode={mode} changed={changed} unchanged={unchanged} "
        f"missing={missing} errors={errors}"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
