#!/usr/bin/env python3
"""Validate and package the original JK model test configuration (requires PyYAML).

python tools/package_jk_test_npcs.py --blueprints C:/server/plugins/ModelEngine/blueprints/npc
Model files are validation inputs only and are never copied into the package.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import yaml

from package_release import ADDON_VERSION, CORE_VERSION, validate_zip, write_zip


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def leaves(value: dict, prefix: str = "") -> set[str]:
    result = set()
    for key, child in value.items():
        name = f"{prefix}.{key}" if prefix else key
        result.update(leaves(child, name) if isinstance(child, dict) else {name})
    return result


def main() -> None:
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--blueprints", type=Path, required=True)
    parser.add_argument("--bone-report", type=Path)
    args = parser.parse_args()
    example = repo / "examples/jk-test-npcs"
    default = yaml.safe_load((repo / "plugins/fancynpcs-model/src/main/resources/config.yml").read_text(encoding="utf-8-sig"))
    config = yaml.safe_load((example / "plugins/FancyNpcsModel/config.yml").read_text(encoding="utf-8-sig"))
    if leaves(default) != leaves(config):
        raise ValueError(f"Config keys differ: {leaves(default) ^ leaves(config)}")
    me = config["settings"]["modelengine"]
    required = (set(me["idle"]["default-states"].values()) | set(me["idle"]["loop-animations"])
                | set(me["idle"]["random-gestures"]["animations"])
                | set(me["animations"]["pose-animations"])
                | set(me["animations"]["head-tracking-pause-animations"]))
    checks = []
    for number in (1, 2):
        model_id = f"ysm_{number:02d}_jk"
        npc_id = f"test_jk_{number:02d}"
        source = args.blueprints / f"{model_id}.bbmodel"
        model = json.loads(source.read_text(encoding="utf-8-sig"))
        animations = {clip["name"]: clip for clip in model["animations"]}
        if missing := required - animations.keys():
            raise ValueError(f"{model_id}: missing configured animations: {missing}")
        for loop in me["idle"]["loop-animations"]:
            if animations[loop].get("loop") != "loop":
                raise ValueError(f"{model_id}/{loop}: not an authored loop")
        commands = []
        for category in ("create", "debug"):
            commands.extend((example / f"commands/{category}_{npc_id}.txt").read_text(encoding="utf-8-sig").splitlines())
        for command in commands:
            parts = command.split()
            if not parts or parts[0].startswith("#"):
                continue
            if parts[:2] == ["/npc", "play_animation"] and parts[3] not in animations:
                raise ValueError(f"Unknown animation in {command}")
            if parts[:2] == ["/npc", "interaction_cooldown"] and parts[3] != "1s":
                raise ValueError(f"Expected a duration with unit: {command}")
        if f"/npc custom_model {npc_id} me:{model_id}" not in commands:
            raise ValueError(f"Missing original model binding for {npc_id}")
        checks.append({"npc": npc_id, "model": model_id, "animations": len(animations),
                       "original_model_sha256": sha256(source), "configured_animations_valid": True,
                       "ambient_loops_valid": True, "command_animation_names_valid": True})
    name = f"FancyNpcs-JK-TestNPCs-{ADDON_VERSION.split('-')[0]}-config-v2"
    files = sorted(path for path in example.rglob("*") if path.is_file() and path.name != "validation.json")
    if any(path.suffix.lower() in (".bbmodel", ".jar") for path in files):
        raise ValueError("The test configuration package must contain no blueprints or JARs")
    report = {"package": name, "fancynpcs_version": CORE_VERSION, "fancynpcsmodel_version": ADDON_VERSION,
              "yaml_keys_validated": len(leaves(default)), "npc_checks": checks,
              "contains_blueprints": False, "resource_pack_update_required": False,
              "interaction_cooldown_unit_validated": "1s", "in_game_executed": False,
              "files": {path.relative_to(example).as_posix(): sha256(path) for path in files}}
    if args.bone_report:
        bone_report = yaml.safe_load(args.bone_report.read_text(encoding="utf-8-sig"))
        if not bone_report.get("passed") or bone_report.get("addon_version") != ADDON_VERSION:
            raise ValueError("Bone probe is not a passing report for the current addon")
        report["localhost_bone_probe"] = bone_report
    manifest = example / "validation.json"
    manifest.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    entries = [(path, path.relative_to(example).as_posix()) for path in [*files, manifest]]
    output = repo / "dist" / f"{name}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    write_zip(output, entries)
    validate_zip(output, entries)
    print(json.dumps({"output": str(output), "files": len(entries), "yaml_keys": len(leaves(default)),
                      "blueprints": 0, "sha256": sha256(output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
