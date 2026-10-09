#!/usr/bin/env python3
"""Append or update the managed personal-space guide in every user's AGENTS.md.

The script walks the controller-mounted personalData directory, skips the
public template directory, and updates:

    <userId>/working/workspaces/default/AGENTS.md

The managed section starts at the level-two heading "个人空间引导". If the
section is absent, it is appended; if it already exists, its content is
replaced up to the next level-one or level-two heading. Other content is
preserved.

Default mode is dry-run. Add --apply to write files.
"""

from __future__ import annotations

import argparse
import os
import shutil
import stat
import subprocess
import sys
from datetime import datetime
from pathlib import Path


TARGET_HEADING = "## 个人空间引导"
TARGET_SECTION = """## 个人空间引导

你在个人空间工作区内遵循以下行为规范：用户导入的本地文件存放于 files/localFiles/，可直接读取；集团云文档缓存位于 files/cloudFiles/，严禁直接读取，所有云文档操作必须先调用 wps_sync 技能进行同步后再使用。网页登录凭据存放在根目录 password.json 中，打开网页时优先据此自动填充登录。同时维护根目录 HEARTBEAT.md 作为主动轮询清单，在收到心跳消息时给出有意义的回复，将定期检查任务合并其中以节省 token；精确时点任务则使用 cron 技能。始终遵循安全红线：绝不泄露私密数据，破坏性命令先确认，trash 优先于 rm，不确定时向用户询问。文件存在性未确认前不臆测路径，本地缺失时引导用户上传或导入。"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Append or update the personal-space guide in user AGENTS.md files."
    )
    parser.add_argument(
        "--personal-data-root",
        default="/qwenpaw_nas/personalData",
        help="controller-mounted personalData root",
    )
    parser.add_argument(
        "--file-relative-path",
        default="working/workspaces/default/AGENTS.md",
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
        help="create AGENTS.md.bak-YYYYmmddHHMMSS before writing",
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


def patch_agents_text(text: str) -> tuple[str, bool]:
    newline = "\r\n" if "\r\n" in text else "\n"
    target_section = TARGET_SECTION.replace("\n", newline)
    section_spans = find_managed_section_spans(text)

    if len(section_spans) > 1:
        raise ValueError(f"duplicate managed heading: {TARGET_HEADING}")

    if section_spans:
        section_start, section_end = section_spans[0]
        suffix = text[section_end:]
        replacement = target_section + (newline * 2 if suffix else newline)
        updated_text = text[:section_start] + replacement + suffix
    else:
        separator = append_separator(text, newline)
        updated_text = text + separator + target_section + newline

    if updated_text == text:
        return text, False

    updated_spans = find_managed_section_spans(updated_text)
    if len(updated_spans) != 1:
        raise RuntimeError("safety check failed: managed section is not unique")
    updated_start, updated_end = updated_spans[0]
    actual_section = updated_text[updated_start:updated_end].rstrip("\r\n")
    if actual_section != target_section:
        raise RuntimeError("safety check failed: managed section was not updated")

    return updated_text, True


def find_managed_section_spans(text: str) -> list[tuple[int, int]]:
    lines = text.splitlines(keepends=True)
    line_starts: list[int] = []
    offset = 0
    for line in lines:
        line_starts.append(offset)
        offset += len(line)

    heading_indexes = [
        index
        for index, line in enumerate(lines)
        if strip_line_ending(line).rstrip(" \t") == TARGET_HEADING
    ]

    spans: list[tuple[int, int]] = []
    for heading_index in heading_indexes:
        section_end = len(text)
        for index in range(heading_index + 1, len(lines)):
            if is_parent_or_peer_heading(strip_line_ending(lines[index])):
                section_end = line_starts[index]
                break
        spans.append((line_starts[heading_index], section_end))
    return spans


def strip_line_ending(line: str) -> str:
    return line.rstrip("\r\n")


def is_parent_or_peer_heading(line: str) -> bool:
    return line.startswith("# ") or line.startswith("## ")


def append_separator(text: str, newline: str) -> str:
    if not text:
        return ""
    if text.endswith(newline * 2):
        return ""
    if text.endswith(newline):
        return newline
    return newline * 2


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
        try:
            path = target_file(args, user_id)
            if not path.is_file():
                print(f"[MISSING] {user_id}: {path}")
                missing += 1
                continue

            text = path.read_text(encoding="utf-8")
            updated_text, should_update = patch_agents_text(text)
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
            print(f"[ERROR] {user_id}: {exc}", file=sys.stderr)

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
