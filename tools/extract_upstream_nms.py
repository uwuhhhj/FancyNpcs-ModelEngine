#!/usr/bin/env python3
"""Extract unchanged NMS implementations from the verified official 2.12.1 JAR.

Only version-specific FancyNpcs / FancySitula .class files are included. The
result is a build input, not a standalone plugin; core, APIs and resources must
still be built from the matching source tree. No downloads or server actions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

VERSION = "2.12.1"
COMMIT = "d71e17d5aab44218b45c156af6bfa8c9cdd57465"
SHA512 = "a370600889f7c08058fc0a7ead3549382efa6e17d3f95f7224822921e3899260d51ad6fe0fb3fe36fa4f61903698de27d1e4ce9804e9ee716733b55f5251ea07"
NPC_VERSIONS = {
    "1_21_5": "v1_21_5", "1_21_6": "v1_21_6", "1_21_9": "v1_21_9",
    "1_21_11": "v1_21_11", "26_1_2": "v26_1_1", "26_2": "v26_2", "26_3": "v26_3",
}
PACKET_VERSIONS = {
    "1_21_5": "v1_21_5", "1_21_6": "v1_21_6", "1_21_9": "v1_21_9",
    "1_21_11": "v1_21_11", "26_1_2": "v26_1", "26_2": "v26_2", "26_3": "v26_3",
}
CLASS_PATTERN = re.compile(
    r"^(de/oliver/fancynpcs/(v(?:1_21|26)_[0-9_]+)/|"
    r"de/oliver/fancysitula/versions/(v(?:1_21|26)_[0-9_]+)/).+\.class$"
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(path: Path, algorithm: str) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def extract(jar: Path, release_json: Path, output: Path, manifest: Path) -> dict:
    require(output.resolve() not in {jar.resolve(), release_json.resolve()},
            "Output must not replace an upstream input")
    require(manifest.resolve() not in {jar.resolve(), release_json.resolve(), output.resolve()},
            "Manifest must not replace an input or the output JAR")
    release = json.loads(release_json.read_text(encoding="utf-8-sig"))
    require(release.get("version_number") == VERSION and release.get("version_type") == "release",
            "Metadata is not the pinned official stable 2.12.1 release")
    require(release.get("project_id") == "EeyAn23L" and release.get("id") == "LigxTtVw",
            "Unexpected Modrinth project or release id")
    files = [item for item in release["files"] if item.get("primary")
             and item.get("filename") == f"FancyNpcs-{VERSION}.jar"]
    require(len(files) == 1, "Metadata must contain exactly one official primary JAR")
    upstream_file = files[0]
    require(upstream_file["hashes"].get("sha512") == SHA512, "Metadata SHA512 differs from pinned release")
    require(jar.stat().st_size == upstream_file["size"], "Upstream JAR size differs from release metadata")
    require(sha(jar, "sha512") == SHA512, "Upstream JAR SHA512 differs from release metadata")

    groups: dict[str, dict] = {}
    for family, versions, base in (
        ("npc", NPC_VERSIONS, "de/oliver/fancynpcs/"),
        ("packet", PACKET_VERSIONS, "de/oliver/fancysitula/versions/"),
    ):
        for module, package in versions.items():
            prefix = base + package + "/"
            groups[prefix] = {"family": family, "source_module_suffix": module,
                              "package": package, "prefix": prefix,
                              "active_upstream_dispatch": module != "1_21_5", "classes": []}

    with zipfile.ZipFile(jar) as source:
        require(source.testzip() is None, "Upstream ZIP integrity check failed")
        names = source.namelist()
        require(names.count("version.yml") == 1, "Upstream ZIP has duplicate version metadata")
        raw_version = source.read("version.yml").decode("utf-8")
        version_info = {key.strip(): value.strip().strip("\"'")
                        for line in raw_version.splitlines()
                        if ":" in line for key, value in [line.split(":", 1)]}
        require(version_info.get("version") == VERSION and version_info.get("commit_hash") == COMMIT,
                "Internal version.yml version/commit does not match the stable source baseline")
        require(version_info.get("channel") == "release", "Internal version.yml is not a release")
        selected = sorted(name for name in names if CLASS_PATTERN.fullmatch(name))
        require(len(selected) == len(set(selected)), "Upstream ZIP has duplicate selected NMS entries")
        require(bool(selected), "No version-specific implementation classes found")
        class_bytes: dict[str, bytes] = {}
        for name in selected:
            match = CLASS_PATTERN.fullmatch(name)
            prefix = match.group(1)
            require(prefix in groups, f"Unexpected implementation package: {prefix}")
            data = source.read(name)
            require(data[:4] == b"\xca\xfe\xba\xbe", f"Invalid Java class: {name}")
            class_bytes[name] = data
            groups[prefix]["classes"].append({"path": name, "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "class_major_version": int.from_bytes(data[6:8], "big")})
        for prefix, group in groups.items():
            require(bool(group["classes"]), f"Missing expected module: {prefix}")
            if group["family"] == "npc":
                suffix = group["package"][1:]
                require(prefix + f"Npc_{suffix}.class" in class_bytes, f"Missing NPC entry point: {prefix}")
                require(prefix + f"attributes/Attributes_{suffix}.class" in class_bytes,
                        f"Missing attribute entry point: {prefix}")
            else:
                # Upstream removed 1.21.5 dispatch but retains its old module;
                # that module never shipped the newer packet listener class.
                if group["active_upstream_dispatch"]:
                    require(prefix + "utils/PacketListenerImpl.class" in class_bytes,
                            f"Missing packet listener entry point: {prefix}")
                require(prefix + "packets/ClientboundAddEntityPacketImpl.class" in class_bytes,
                        f"Missing packet entry point: {prefix}")

    output.parent.mkdir(parents=True, exist_ok=True)
    # Stable timestamps and ordering make repeated runs byte-identical.
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name, data in class_bytes.items():
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            target.writestr(info, data, compresslevel=9)
    with zipfile.ZipFile(output) as result:
        require(result.testzip() is None, "Extracted ZIP integrity check failed")
        require(result.namelist() == selected, "Extracted archive contains unexpected resources or classes")
        require(all(result.read(name) == data for name, data in class_bytes.items()),
                "Extracted class bytes differ from official input")
    report = {
        "schema_version": 1, "upstream_version": VERSION, "upstream_commit": COMMIT,
        "modrinth_release_id": release["id"], "upstream_jar": str(jar.resolve()),
        "upstream_sha512": SHA512, "upstream_size": jar.stat().st_size,
        "upstream_metadata_sha256": sha(release_json, "sha256"),
        "excluded_duplicate_entries": len(names) - len(set(names)),
        "output_jar": str(output.resolve()), "output_sha256": sha(output, "sha256"),
        "output_sha512": sha(output, "sha512"), "output_size": output.stat().st_size,
        "class_count": len(selected), "npc_module_count": len(NPC_VERSIONS),
        "packet_module_count": len(PACKET_VERSIONS),
        "excluded": ["core", "api", "loader", "plugin metadata", "cloud", "other dependencies", "non-class resources"],
        "modules": [{**group, "class_count": len(group["classes"])} for group in groups.values()],
    }
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    deps = root / "build" / "deps"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-jar", type=Path, default=deps / f"FancyNpcs-{VERSION}-upstream.jar")
    parser.add_argument("--release-json", type=Path, default=deps / "upstream-latest-release.json")
    parser.add_argument("--output", type=Path, default=deps / f"FancyNpcs-{VERSION}-upstream-nms.jar")
    parser.add_argument("--manifest", type=Path, default=deps / f"FancyNpcs-{VERSION}-upstream-nms.manifest.json")
    args = parser.parse_args()
    try:
        report = extract(args.upstream_jar, args.release_json, args.output, args.manifest)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"Extraction refused: {failure}\n")
    print(f"Verified official {VERSION} / {COMMIT}")
    print(f"Extracted {report['class_count']} classes; 7 NPC + 7 packet modules")
    print(f"Output SHA256: {report['output_sha256']}")
    print(f"JAR: {args.output}")
    print(f"Manifest: {args.manifest}")


if __name__ == "__main__":
    main()
