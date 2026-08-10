#!/usr/bin/env python3
"""Batch-upgrade existing per-user QwenPaw Deployment pod templates.

The script discovers Deployments through kubectl, selects resources named
``qwenpaw-<user_id>`` whose ``qwenpaw`` container currently uses the expected
old image tag, and runs ``kubectl set image`` for each selected Deployment.

The default mode is dry-run. Add --apply to make changes.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Any, Optional, Sequence


DEFAULT_NAMESPACE = "ai"
DEFAULT_CONTAINER = "qwenpaw"
DEFAULT_OLD_TAG = "v1.1.10"
DEFAULT_NEW_IMAGE = "docker.io/library/qwenpaw-custom:v2.0.0"
DEPLOYMENT_NAME_PATTERN = re.compile(r"^qwenpaw-(?P<user_id>.+)$")


@dataclass(frozen=True)
class Target:
    deployment: str
    user_id: str
    current_image: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upgrade all existing per-user QwenPaw Deployment images."
    )
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE)
    parser.add_argument("--container", default=DEFAULT_CONTAINER)
    parser.add_argument("--old-tag", default=DEFAULT_OLD_TAG)
    parser.add_argument("--new-image", default=DEFAULT_NEW_IMAGE)
    parser.add_argument(
        "--selector",
        default="app=qwenpaw",
        help="Deployment label selector; use an empty string to disable it",
    )
    parser.add_argument(
        "--user",
        action="append",
        default=[],
        help="only upgrade this userId; repeat for multiple users",
    )
    parser.add_argument("--kubectl", default="kubectl", help="kubectl executable path")
    parser.add_argument("--kubeconfig", help="kubeconfig path passed to kubectl")
    parser.add_argument("--context", help="kubectl context name")
    parser.add_argument(
        "--apply", action="store_true", help="perform upgrades; default is dry-run"
    )
    parser.add_argument(
        "--wait",
        action="store_true",
        help="after applying, wait for each successful Deployment rollout",
    )
    parser.add_argument(
        "--timeout", default="10m", help="kubectl rollout status timeout"
    )
    return parser.parse_args()


def kubectl_base(args: argparse.Namespace) -> list[str]:
    command = [args.kubectl]
    if args.kubeconfig:
        command.extend(["--kubeconfig", args.kubeconfig])
    if args.context:
        command.extend(["--context", args.context])
    command.extend(["--namespace", args.namespace])
    return command


def run(command: Sequence[str], *, capture_output: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        text=True,
        capture_output=capture_output,
    )


def image_tag(image: str) -> Optional[str]:
    """Return a container image tag, ignoring registry port colons."""
    last_segment = image.rsplit("/", 1)[-1]
    if "@" in last_segment or ":" not in last_segment:
        return None
    return last_segment.rsplit(":", 1)[1]


def find_targets(
    deployment_list: dict[str, Any],
    *,
    container_name: str,
    old_tag: str,
    users: set[str],
) -> tuple[list[Target], list[str]]:
    targets: list[Target] = []
    skipped: list[str] = []

    for item in deployment_list.get("items", []):
        name = item.get("metadata", {}).get("name", "")
        match = DEPLOYMENT_NAME_PATTERN.fullmatch(name)
        if not match:
            skipped.append(f"{name or '<unknown>'}: name does not match qwenpaw-<user_id>")
            continue

        user_id = match.group("user_id")
        if users and user_id not in users:
            continue

        containers = item.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
        container = next((entry for entry in containers if entry.get("name") == container_name), None)
        if container is None:
            skipped.append(f"{name}: container {container_name!r} not found")
            continue

        current_image = container.get("image", "")
        if image_tag(current_image) != old_tag:
            skipped.append(f"{name}: current image is {current_image or '<empty>'}")
            continue

        targets.append(Target(name, user_id, current_image))

    return sorted(targets, key=lambda target: target.deployment), skipped


def load_deployments(args: argparse.Namespace) -> dict[str, Any]:
    command = kubectl_base(args) + ["get", "deployments"]
    if args.selector:
        command.extend(["--selector", args.selector])
    command.extend(["--output", "json"])
    result = run(command, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "kubectl failed"
        raise RuntimeError(detail)
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"kubectl returned invalid JSON: {exc}") from exc
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        raise RuntimeError("kubectl response is not a DeploymentList")
    return value


def main() -> int:
    args = parse_args()
    try:
        deployment_list = load_deployments(args)
        targets, skipped = find_targets(
            deployment_list,
            container_name=args.container,
            old_tag=args.old_tag,
            users=set(args.user),
        )
    except (OSError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"[{mode}] namespace={args.namespace} target_image={args.new_image}")
    for target in targets:
        print(
            f"  {target.deployment} (user={target.user_id}): "
            f"{target.current_image} -> {args.new_image}"
        )

    if skipped:
        print(f"Skipped {len(skipped)} Deployment(s):")
        for reason in skipped:
            print(f"  {reason}")

    if not targets:
        print("No matching Deployment needs an upgrade.")
        return 0
    if not args.apply:
        print(f"Would upgrade {len(targets)} Deployment(s). Re-run with --apply to continue.")
        return 0

    succeeded: list[Target] = []
    failed: list[str] = []
    for target in targets:
        command = kubectl_base(args) + [
            "set",
            "image",
            f"deployment/{target.deployment}",
            f"{args.container}={args.new_image}",
        ]
        result = run(command)
        if result.returncode == 0:
            succeeded.append(target)
        else:
            failed.append(target.deployment)

    if args.wait:
        for target in succeeded:
            command = kubectl_base(args) + [
                "rollout",
                "status",
                f"deployment/{target.deployment}",
                f"--timeout={args.timeout}",
            ]
            if run(command).returncode != 0:
                failed.append(f"{target.deployment} (rollout)")

    print(f"Finished: upgraded={len(succeeded)}, failed={len(failed)}")
    if failed:
        print("Failed: " + ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
