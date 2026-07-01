#!/usr/bin/env python3
"""Patch existing QwenPaw user Deployments with PERSONAL_API_KEY env.

The script reads each user's existing personal-api-key.json from the NAS
personalData tree, extracts custom_headers.api-key, and patches the matching
Deployment Pod template. It does not call createPersonalApiKey.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


ELLM_BASE_URLS = {
    "SIT": "http://12.234.162.238",
    "UAT": "http://12.244.66.225",
    "UAT2": "http://12.244.130.39",
    "UATC": "http://12.244.249.159",
    "PRD": "http://eaip-chn-slb-7006.bocomm.com",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Patch existing qwenpaw user Deployment templates so their env "
            "matches new Deployments."
        )
    )
    parser.add_argument("--apply", action="store_true", help="actually patch Kubernetes; default is dry-run")
    parser.add_argument("--namespace", default="ai", help="Kubernetes namespace")
    parser.add_argument("--kubectl", default="kubectl", help="kubectl executable path")
    parser.add_argument("--kubeconfig", help="kubeconfig path passed to kubectl")
    parser.add_argument("--context", help="kubectl context name")
    parser.add_argument(
        "--personal-data-root",
        default="/qwenpaw_nas/personalData",
        help="controller-mounted personalData root",
    )
    parser.add_argument(
        "--personal-api-key-file-relative-path",
        default="providers/custom/personal-api-key.json",
        help="path below each user's working.secret directory",
    )
    parser.add_argument("--public-template-sub-path", default="public-secret", help="directory to skip")
    parser.add_argument("--deployment-prefix", default="qwenpaw-", help="user Deployment name prefix")
    parser.add_argument("--app-container", default="qwenpaw", help="main business container name")
    parser.add_argument("--init-container", default="init-config", help="initContainer name")
    parser.add_argument(
        "--volume-mode",
        choices=("subpath", "single-mount"),
        default="subpath",
        help="current QWENPAW_VOLUME_MODE",
    )
    parser.add_argument("--nas-mount-path", default="/qwenpaw_nas", help="NAS mount path used in single-mount mode")
    parser.add_argument(
        "--runtime-configmap",
        default="qwenpaw-runtime-config",
        help="ConfigMap used to read RUN_ENV when --run-env is omitted",
    )
    parser.add_argument("--run-env", help="RUN_ENV value, for example PRD/UAT/SIT")
    parser.add_argument(
        "--ellm-adapter-base-url",
        help="override ELLM adapter base_url injected into initContainer",
    )
    parser.add_argument(
        "--skip-init-container-env",
        action="store_true",
        help="only patch the qwenpaw business container env",
    )
    parser.add_argument(
        "--skip-enable-service-links",
        action="store_true",
        help="do not patch spec.template.spec.enableServiceLinks=false",
    )
    parser.add_argument(
        "--user",
        action="append",
        default=[],
        help="specific userId to patch; repeat for multiple users. Defaults to all user dirs.",
    )
    parser.add_argument("--show-secrets", action="store_true", help="print full API keys in dry-run output")
    return parser.parse_args()


def kubectl(args: argparse.Namespace, command: list[str]) -> str:
    kubectl_command = [args.kubectl]
    if args.kubeconfig:
        kubectl_command.extend(["--kubeconfig", args.kubeconfig])
    if args.context:
        kubectl_command.extend(["--context", args.context])
    kubectl_command.extend(command)

    result = subprocess.run(
        kubectl_command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def user_ids(args: argparse.Namespace) -> list[str]:
    if args.user:
        return sorted(dict.fromkeys(user.strip() for user in args.user if user.strip()))

    root = Path(args.personal_data_root)
    if not root.is_dir():
        raise FileNotFoundError(f"personalData root not found: {root}")

    users = []
    for path in root.iterdir():
        if path.is_dir() and path.name != args.public_template_sub_path:
            users.append(path.name)
    return sorted(users)


def read_personal_api_key(args: argparse.Namespace, user_id: str) -> tuple[Path, str]:
    file_path = (
        Path(args.personal_data_root)
        / user_id
        / "working.secret"
        / args.personal_api_key_file_relative_path
    )
    with file_path.open("r", encoding="utf-8") as stream:
        data = json.load(stream)

    custom_headers = data.get("custom_headers")
    if not isinstance(custom_headers, dict):
        raise ValueError("missing custom_headers object")

    api_key = custom_headers.get("api-key")
    if not isinstance(api_key, str) or not api_key.strip():
        raise ValueError("missing custom_headers.api-key")

    return file_path, api_key


def resolve_adapter_base_url(args: argparse.Namespace) -> str:
    if args.ellm_adapter_base_url:
        return normalize_adapter_base_url(args.ellm_adapter_base_url)

    run_env = args.run_env
    if not run_env:
        run_env = kubectl(
            args,
            [
                "-n",
                args.namespace,
                "get",
                "configmap",
                args.runtime_configmap,
                "-o",
                "jsonpath={.data.RUN_ENV}",
            ],
        )

    normalized_env = run_env.strip().upper()
    base_url = ELLM_BASE_URLS.get(normalized_env)
    if not base_url:
        supported = ", ".join(sorted(ELLM_BASE_URLS))
        raise ValueError(f"unsupported RUN_ENV {normalized_env!r}; supported: {supported}")

    return normalize_adapter_base_url(f"{base_url}/ELLM.ELLM-ADAPTER.V-1.0/v1/")


def normalize_adapter_base_url(value: str) -> str:
    stripped = value.strip()
    return stripped if stripped.endswith("/") else f"{stripped}/"


def get_deployment(args: argparse.Namespace, deployment_name: str) -> dict[str, Any]:
    output = kubectl(
        args,
        ["-n", args.namespace, "get", "deployment", deployment_name, "-o", "json"],
    )
    return json.loads(output)


def has_named_item(items: list[dict[str, Any]], name: str) -> bool:
    return any(item.get("name") == name for item in items)


def build_patch(
    args: argparse.Namespace,
    deployment: dict[str, Any],
    user_id: str,
    api_key: str,
    adapter_base_url: str | None,
) -> tuple[dict[str, Any], list[str]]:
    template_spec = deployment.get("spec", {}).get("template", {}).get("spec", {})
    warnings = []

    containers = template_spec.get("containers") or []
    if not has_named_item(containers, args.app_container):
        raise ValueError(f"container {args.app_container!r} not found")

    main_env = []
    if args.volume_mode == "single-mount":
        main_env.extend(
            [
                {
                    "name": "QWENPAW_WORKING_DIR",
                    "value": f"{args.nas_mount_path.rstrip('/')}/{user_id}/working",
                },
                {
                    "name": "QWENPAW_SECRET_DIR",
                    "value": f"{args.nas_mount_path.rstrip('/')}/{user_id}/working.secret",
                },
            ]
        )
    main_env.extend(
        [
            {"name": "USER_ID", "value": user_id},
            {"name": "QWENPAW_USER", "value": user_id},
            {"name": "PERSONAL_API_KEY", "value": api_key},
        ]
    )

    pod_spec_patch: dict[str, Any] = {
        "containers": [{"name": args.app_container, "env": main_env}],
    }
    if not args.skip_enable_service_links:
        pod_spec_patch["enableServiceLinks"] = False

    if not args.skip_init_container_env:
        init_containers = template_spec.get("initContainers") or []
        if has_named_item(init_containers, args.init_container):
            if not adapter_base_url:
                raise ValueError("adapter_base_url is required when patching initContainer env")
            pod_spec_patch["initContainers"] = [
                {
                    "name": args.init_container,
                    "env": [
                        {"name": "PERSONAL_API_KEY", "value": api_key},
                        {"name": "ELLM_ADAPTER_BASE_URL", "value": adapter_base_url},
                    ],
                }
            ]
        else:
            warnings.append(f"initContainer {args.init_container!r} not found; skipped init env")

    return {"spec": {"template": {"spec": pod_spec_patch}}}, warnings


def patch_deployment(args: argparse.Namespace, deployment_name: str, patch: dict[str, Any]) -> str:
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as stream:
            temp_path = stream.name
            json.dump(patch, stream, ensure_ascii=True, separators=(",", ":"))
            stream.write("\n")

        return kubectl(
            args,
            [
                "-n",
                args.namespace,
                "patch",
                "deployment",
                deployment_name,
                "--type",
                "strategic",
                "--patch-file",
                temp_path,
            ],
        ).strip()
    finally:
        if temp_path:
            try:
                Path(temp_path).unlink()
            except FileNotFoundError:
                pass


def mask_secret(value: str) -> str:
    if len(value) <= 8:
        return "*" * len(value)
    return f"{value[:4]}...{value[-4:]}"


def print_plan(
    args: argparse.Namespace,
    deployment_name: str,
    file_path: Path,
    api_key: str,
    patch: dict[str, Any],
    warnings: list[str],
) -> None:
    shown_key = api_key if args.show_secrets else mask_secret(api_key)
    init_note = "yes" if "initContainers" in patch["spec"]["template"]["spec"] else "no"
    service_links_note = (
        "yes"
        if patch["spec"]["template"]["spec"].get("enableServiceLinks") is False
        else "no"
    )
    mode = "APPLY" if args.apply else "DRY-RUN"
    print(
        f"[{mode}] {deployment_name}: PERSONAL_API_KEY={shown_key}, "
        f"source={file_path}, patch_init_env={init_note}, "
        f"patch_enableServiceLinks={service_links_note}"
    )
    for warning in warnings:
        print(f"  warning: {warning}")


def main() -> int:
    args = parse_args()
    adapter_base_url = None
    if not args.skip_init_container_env:
        adapter_base_url = resolve_adapter_base_url(args)

    total = 0
    patched = 0
    skipped = 0

    for user_id in user_ids(args):
        total += 1
        deployment_name = f"{args.deployment_prefix}{user_id}"
        try:
            file_path, api_key = read_personal_api_key(args, user_id)
            deployment = get_deployment(args, deployment_name)
            patch, warnings = build_patch(args, deployment, user_id, api_key, adapter_base_url)
            print_plan(args, deployment_name, file_path, api_key, patch, warnings)
            if args.apply:
                output = patch_deployment(args, deployment_name, patch)
                print(f"  {output}")
            patched += 1
        except Exception as exc:
            skipped += 1
            print(f"[SKIP] {deployment_name}: {exc}", file=sys.stderr)

    mode = "applied" if args.apply else "planned"
    print(f"Done: total={total}, {mode}={patched}, skipped={skipped}")
    return 1 if skipped else 0


if __name__ == "__main__":
    raise SystemExit(main())
