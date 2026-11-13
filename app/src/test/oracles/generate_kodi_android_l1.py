#!/usr/bin/env python3
"""Generate a synthetic oracle by executing the pinned Kodi functions, not a second port.

Usage: python generate_kodi_android_l1.py /path/to/pinned/plugin.video.netflix output.json
No imports from Kodi, network requests, CDM calls, account data, or device secrets are used.
"""
import ast
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
from types import SimpleNamespace
from urllib.parse import urlencode


PIN = "bf91b55089b06bd892eb6cd1457b3a87341fb5bf"
ROOT = "https://github.com/atrHusK/plugin.video.netflix/blob/" + PIN + "/"


def main():
    checkout = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    files = {
        "handler": "resources/lib/services/nfsession/msl/msl_handler.py",
        "builder": "resources/lib/services/nfsession/msl/msl_request_builder.py",
        "utils": "resources/lib/services/nfsession/msl/msl_utils.py",
        "profiles": "resources/lib/services/nfsession/msl/profiles.py",
        "identity": "resources/lib/utils/esn.py",
        "device": "resources/lib/common/device_utils.py",
    }
    sources = {key: (checkout / value).read_text() for key, value in files.items()}
    trees = {key: ast.parse(value) for key, value in sources.items()}
    now = 1700000000
    metadata = {
        "locale_id": "pl-PL", "ui_version": "ui-fixture-version",
        "client_version": "6.0-fixture", "build_identifier": "build-fixture-id",
        "browser_info_version": "159.0.FIXTURE", "browser_info_os_name": "Linux",
        "browser_info_os_version": "6.1",
    }
    common = SimpleNamespace(
        get_system_platform=lambda: "android",
        make_list=lambda value: value if isinstance(value, list) else [value],
        get_hdcp_level=lambda: 1.4,
    )
    globals_stub = SimpleNamespace(
        LOCAL_DB=SimpleNamespace(
            get_value=lambda key, default="", **_: metadata.get(key, default),
            get_profile_config=lambda _: "pl-PL",
        ),
        ADDON=SimpleNamespace(
            getSettingBool=lambda key: key == "enable_vp9_profiles",
            getSettingString=lambda _: "--",
        ),
        KODI_VERSION=21,
    )
    namespace = {
        "G": globals_stub, "common": common, "TABLE_SESSION": "synthetic",
        "time": SimpleNamespace(time=lambda: now), "urlencode": urlencode, "re": re,
        "LOG": SimpleNamespace(error=lambda *_: None),
        "get_system_platform": lambda: "android", "get_machine": lambda: "aarch64",
        "is_native_linux_widevine": lambda: False,
        "_get_drm_info": lambda _: ("L1", "12345"),
        "_create_id64chars": lambda: "A" * 64,
    }

    def execute_function(source, name, body=None):
        node = copy.deepcopy(next(node for node in ast.walk(trees[source])
                                  if isinstance(node, ast.FunctionDef) and node.name == name))
        node.decorator_list = []
        if body is not None:
            node.body = body(node.body)
        module = ast.fix_missing_locations(ast.Module(body=[node], type_ignores=[]))
        exec(compile(module, files[source], "exec"), namespace)

    # The source profile selection runs with only VP9 profile 0 enabled; all optional
    # settings (Dolby, HEVC, HDR, AV1, alternate subtitles) remain disabled.
    profile_module = copy.deepcopy(trees["profiles"])
    profile_module.body = [node for node in profile_module.body
                           if not isinstance(node, (ast.Import, ast.ImportFrom))]
    exec(compile(ast.fix_missing_locations(profile_module), files["profiles"], "exec"), namespace)
    profiles = namespace["enabled_profiles"]()
    execute_function("builder", "build_request_data")
    execute_function("utils", "create_req_params")
    execute_function("handler", "_build_manifest_v2")
    execute_function("identity", "_generate_esn_android")
    execute_function("device", "get_user_agent")
    namespace["ENDPOINTS"] = {
        "manifest": "https://www.netflix.com/msl/playapi/cadmium/licensedmanifest/1"
    }

    # Execute the actual default HDCP-selection statements from _get_manifest, stopping
    # before any request. This avoids duplicating its 1.4/2.2 policy in the Kotlin oracle.
    def hdcp_prefix(body):
        end = next(index for index, node in enumerate(body)
                   if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "hdcp_version"
                           for target in node.targets))
        return body[:end + 1] + [ast.Return(value=ast.Name(id="hdcp_version", ctx=ast.Load()))]

    execute_function("handler", "_get_manifest", hdcp_prefix)
    receiver = SimpleNamespace(msl_requests=SimpleNamespace(build_request_data=namespace["build_request_data"]))
    device = {"manufacturer": "Google", "model": "Pixel 9 Pro XL", "systemId": "12345",
              "securityLevel": "L1", "suffix": "A" * 64}
    identity = namespace["_generate_esn_android"]({"ro.product.manufacturer": device["manufacturer"],
                                                   "ro.product.model": device["model"]}, None)
    context = {
        "locale": metadata["locale_id"], "uiVersion": metadata["ui_version"],
        "clientVersion": metadata["client_version"], "buildIdentifier": metadata["build_identifier"],
        "browserVersion": metadata["browser_info_version"], "osName": metadata["browser_info_os_name"],
        "osVersion": metadata["browser_info_os_version"],
    }
    cases = []
    for label, hdcp, level in [("disconnected", "Disconnected", 1.4),
                               ("hdcp14", "HDCP-1.4", 1.4), ("hdcp22", "HDCP-2.2", 2.2)]:
        common.get_hdcp_level = lambda level=level: level
        hdcp_versions = namespace["_get_manifest"](None, 80100172, identity, "", "")
        endpoint, request = namespace["_build_manifest_v2"](
            receiver, viewable_id=80100172, profiles=profiles, hdcp_version=hdcp_versions,
            challenge="", sid="", xid="170000000000000000",
        )
        cases.append({"name": label, "input": {"videoId": "80100172", "xid": "170000000000000000",
                      "hdcp": hdcp, "clientTimeSeconds": now},
                      "endpoint": endpoint.replace("reqAttempt=", "reqAttempt=1"), "request": request})
    saved_metadata = metadata.copy()
    metadata.clear()
    empty_endpoint = namespace["ENDPOINTS"]["manifest"] + namespace["create_req_params"]("prefetch/licensedManifest")
    metadata.update(saved_metadata)
    fixture = {
        "provenance": {
            "referenceCommit": PIN,
            "generation": "Unmodified AST-extracted Kodi functions; Android/aarch64; no HDCP override; synthetic inputs only.",
            "sources": {key: {"url": ROOT + files[key], "sha256": hashlib.sha256(sources[key].encode()).hexdigest()}
                        for key in files},
        },
        "device": device, "nativeIdentity": identity, "userAgent": namespace["get_user_agent"](),
        "context": context, "profiles": profiles, "cases": cases,
        "emptyMetadataEndpoint": empty_endpoint.replace("reqAttempt=", "reqAttempt=1"),
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(fixture, indent=2, ensure_ascii=False) + "\n")
    print("Generated", len(cases), "cases from Kodi source;", len(profiles), "profiles.")


if __name__ == "__main__":
    main()
