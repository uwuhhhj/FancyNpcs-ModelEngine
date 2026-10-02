#!/usr/bin/env python3
"""Validate and package the local FancyNpcs/ModelEngine release (Python 3.10+, stdlib only).

Run after the final build and validation, from any directory:
    python tools/package_release.py

Required local inputs: README.md, NOTICE, validation.json, upstream LICENSE,
and the two finished plugin JARs under their module's build/libs directory.
No build, download, server modification or commercial dependency copying is
performed. --help lists optional input and output overrides.

ZIP entries have stable ordering, timestamps and permissions. The same input
bytes and Python/zlib implementation produce the same package bytes. Existing
artifacts with these exact release names are replaced only after validation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import time
import zipfile
from pathlib import Path


CORE_VERSION = "2.12.2-simmc-me.1"
ADDON_VERSION = "1.3.1-simmc-me.1"
API_VERSIONS = {"FancyNpcs": "1.19", "FancyNpcsModel": "1.21.11"}
SOURCE_BASE_COMMIT = "d71e17d5aab44218b45c156af6bfa8c9cdd57465"
UPSTREAM_VERSION = "2.12.1"
BASELINE_NMS_VERSIONS = ("1_21_5", "1_21_6", "1_21_9", "1_21_11", "26_1_2", "26_2", "26_3")
RELEASE_NAME = f"FancyNpcs-ModelEngine-{ADDON_VERSION}"
EXCLUDED_DIRS = frozenset({
    ".git", ".gradle", ".idea", ".vscode", ".cache", ".next", ".nuxt",
    ".venv", "venv", "node_modules", "build", "target", "dist", "out", "deps", "run", "logs",
    "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "coverage",
    ".codex", ".agents", ".aws",
})
EXCLUDED_FILES = frozenset({".git", ".DS_Store", "Thumbs.db", ".env", ".env.local", ".env.production"})
DESCRIPTORS = ("paper-plugin.yml", "plugin.yml")
FORBIDDEN_ADDON_PREFIXES = ("com/ticxo/", "kr/toxicity/")
SOURCE_MODULES = (
    "plugins/fancynpcs-v2/", "plugins/fancynpcs-model/",
    "libraries/common/", "libraries/jdb/", "libraries/config/", "libraries/plugin-tests/",
    "libraries/packets/", "gradle/", "tools/", ".github/",
)
SOURCE_BUILD_FILES = frozenset({
    "plugins/build.gradle.kts", "libraries/build.gradle.kts",
})


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def top_level_scalars(text: str) -> dict[str, str]:
    """Read only descriptor identity scalars, not general YAML or nested data."""
    fields: dict[str, str] = {}
    for line in text.splitlines():
        match = re.match(r"^([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$", line)
        if not match or match[1] not in {"name", "version", "api-version", "main"}:
            continue
        key, raw = match.groups()
        if key in fields:
            raise ValueError(f"Duplicate descriptor field: {key}")
        if raw.startswith('"'):
            decoder = json.JSONDecoder()
            value, end = decoder.raw_decode(raw)
            trailing = raw[end:].strip()
            if not isinstance(value, str) or (trailing and not trailing.startswith("#")):
                raise ValueError(f"Invalid descriptor scalar: {key}")
        elif raw.startswith("'"):
            quoted = re.fullmatch(r"'((?:[^']|'')*)'\s*(?:#.*)?", raw)
            if not quoted:
                raise ValueError(f"Invalid descriptor scalar: {key}")
            value = quoted[1].replace("''", "'")
        else:
            value = re.split(r"\s+#", raw, maxsplit=1)[0].strip()
            if not value or value[0] in "[{&*!|>":
                raise ValueError(f"Unsupported descriptor scalar: {key}")
        fields[key] = value
    return fields


def descriptor_authors(text: str) -> list[str]:
    lines = text.splitlines()
    definitions = [(index, re.match(r"^authors:\s*(.*?)\s*$", line))
                   for index, line in enumerate(lines) if line.startswith("authors:")]
    if len(definitions) != 1:
        raise ValueError("Descriptor must contain exactly one authors list")
    index, definition = definitions[0]
    raw = definition[1]
    values = []
    if raw:
        inline = re.fullmatch(r"\[(.*)\]\s*(?:#.*)?", raw)
        if not inline:
            raise ValueError("Descriptor authors must be a YAML list")
        values = [value.strip() for value in inline[1].split(",") if value.strip()]
    else:
        for line in lines[index + 1:]:
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            entry = re.match(r"^\s*-\s*(.*?)\s*$", line)
            if not entry:
                break
            values.append(entry[1])
    return [top_level_scalars("name: " + value)["name"] for value in values]


def inspect_jar(path: Path, name: str, version: str, *, addon: bool = False) -> dict:
    api_version = API_VERSIONS[name]
    with zipfile.ZipFile(path) as jar:
        bad = jar.testzip()
        if bad:
            raise ValueError(f"JAR CRC failed: {path} -> {bad}")
        entries = jar.namelist()
        if len(entries) != len(set(entries)):
            raise ValueError(f"Duplicate entries in JAR: {path}")
        descriptor_names = [entry for entry in DESCRIPTORS if entry in entries]
        if not descriptor_names:
            raise ValueError(f"No root plugin descriptor in {path}")
        descriptions = {}
        for entry in descriptor_names:
            text = jar.read(entry).decode("utf-8-sig", errors="strict")
            fields = top_level_scalars(text)
            for key, expected in {"name": name, "version": version, "api-version": api_version}.items():
                if fields.get(key) != expected:
                    raise ValueError(f"{path.name}/{entry}: expected {key}={expected!r}, got {fields.get(key)!r}")
            main = fields.get("main", "").replace(".", "/") + ".class"
            if main not in entries:
                raise ValueError(f"Descriptor main class is missing from {path}: {main}")
            authors = descriptor_authors(text)
            if not {"OliverSchlueter", "Loliiiico"}.issubset(authors):
                raise ValueError(f"{path.name}/{entry}: authors must retain OliverSchlueter and include Loliiiico, got {authors!r}")
            descriptions[entry] = {**fields, "authors": authors}
        if addon:
            forbidden = [entry for entry in entries if entry.endswith(".class") and
                         re.sub(r"^META-INF/versions/[0-9]+/", "", entry).startswith(FORBIDDEN_ADDON_PREFIXES)]
            if forbidden:
                raise ValueError(f"Addon contains external model-plugin classes: {forbidden[:10]}")
    return {"name": name, "version": version, "api_version": api_version,
            "sha256": sha256(path), "bytes": path.stat().st_size,
            "descriptors": descriptions, "crc": "passed",
            "external_model_classes": "absent" if addon else "not_checked"}


def find_jar(repo: Path, module: str, name: str, version: str, override: Path | None) -> tuple[Path, dict]:
    if override is not None:
        path = override.resolve(strict=True)
        return path, inspect_jar(path, name, version, addon=name == "FancyNpcsModel")
    directory = repo / "plugins" / module / "build" / "libs"
    canonical = directory / f"{name}-{version}.jar"
    if canonical.is_file():
        return canonical, inspect_jar(canonical, name, version, addon=name == "FancyNpcsModel")
    candidates = []
    rejected = []
    for path in sorted(directory.glob("*.jar")):
        try:
            report = inspect_jar(path, name, version, addon=name == "FancyNpcsModel")
            candidates.append((path, report))
        except (ValueError, zipfile.BadZipFile, UnicodeError) as error:
            rejected.append(f"{path.name}: {error}")
    if len(candidates) != 1:
        details = "; ".join(rejected) or "no finished plugin JAR found"
        raise ValueError(f"Expected exactly one valid {name} {version} JAR in {directory}; "
                         f"found {len(candidates)}. {details}. Use an explicit --core-jar/--addon-jar if needed.")
    return candidates[0]


def source_files(repo: Path) -> list[tuple[Path, str]]:
    result = []
    for current, directories, files in os.walk(repo, followlinks=False):
        parent = Path(current)
        directories[:] = sorted(directory for directory in directories
                                if directory not in EXCLUDED_DIRS and not (parent / directory).is_symlink())
        for filename in sorted(files):
            path = parent / filename
            relative = path.relative_to(repo).as_posix()
            # Preserve the entire FancyNpcs v2/API/addon and packet trees,
            # including every NMS branch. Only unrelated projects/media are omitted.
            if "/" in relative and relative not in SOURCE_BUILD_FILES and not relative.startswith(SOURCE_MODULES):
                continue
            if path.is_symlink() or filename in EXCLUDED_FILES:
                continue
            # The Gradle wrapper is build tooling. All other binary JAR inputs,
            # including ModelEngine/BetterModel, are deliberately excluded.
            if path.suffix.lower() == ".jar" and relative != "gradle/wrapper/gradle-wrapper.jar":
                continue
            if filename.endswith((".class", ".pyc", ".pyo", ".log", ".tmp", ".bak", ".hprof")):
                continue
            result.append((path, relative))
    required = {"gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties", "build.gradle.kts", "settings.gradle.kts", "SOURCE_COMMIT", "LICENSE"}
    for version in BASELINE_NMS_VERSIONS:
        required.add(f"plugins/fancynpcs-v2/implementation_{version}/build.gradle.kts")
        required.add(f"libraries/packets/implementations/{version}/build.gradle.kts")
    missing = required - {relative for _, relative in result}
    if missing:
        raise ValueError(f"Source tree is missing build/license inputs: {sorted(missing)}")
    for version in BASELINE_NMS_VERSIONS:
        for prefix in (f"plugins/fancynpcs-v2/implementation_{version}/src/",
                       f"libraries/packets/implementations/{version}/src/"):
            if not any(relative.startswith(prefix) and relative.endswith(".java") for _, relative in result):
                raise ValueError(f"NMS branch source is missing from package scope: {prefix}")
    return result


def zip_timestamp() -> tuple[int, ...]:
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "946684800"))  # 2000-01-01 UTC
    value = time.gmtime(epoch)
    if not 1980 <= value.tm_year <= 2107:
        raise ValueError("SOURCE_DATE_EPOCH must fit the ZIP timestamp range 1980..2107")
    return (*value[:5], value.tm_sec - value.tm_sec % 2)


def write_zip(output: Path, entries: list[tuple[Path, str]]) -> None:
    timestamp = zip_timestamp()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, name in sorted(entries, key=lambda item: item[1]):
            info = zipfile.ZipInfo(name, date_time=timestamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            executable = path.name == "gradlew" or path.suffix == ".sh"
            info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
            with path.open("rb") as source, archive.open(info, "w") as destination:
                shutil.copyfileobj(source, destination)
    validate_zip(output, entries)


def validate_zip(path: Path, expected: list[tuple[Path, str]]) -> None:
    with zipfile.ZipFile(path) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP CRC failed: {path} -> {bad}")
        names = archive.namelist()
        wanted = [name for _, name in expected]
        if len(names) != len(set(names)) or set(names) != set(wanted):
            raise ValueError(f"ZIP contents differ from the expected manifest: {path}")
        for source, name in expected:
            digest = hashlib.sha256()
            with archive.open(name) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != sha256(source):
                raise ValueError(f"ZIP entry differs from current input: {name}")


def main() -> int:
    here = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", type=Path, default=here)
    parser.add_argument("--dist", type=Path, default=here / "dist")
    parser.add_argument("--core-jar", type=Path)
    parser.add_argument("--addon-jar", type=Path)
    parser.add_argument("--readme", type=Path, default=here / "README.md")
    parser.add_argument("--notice", type=Path, default=here / "NOTICE")
    parser.add_argument("--validation", type=Path, default=here / "validation.json")
    metadata_default = here / "upstream-release.json"
    if not metadata_default.is_file():
        metadata_default = here / "build/deps/upstream-latest-release.json"
    parser.add_argument("--upstream-release", type=Path, default=metadata_default)
    args = parser.parse_args()
    repo = args.repo.resolve(strict=True)
    documents = [(args.readme, "README.md"), (repo / "LICENSE", "LICENSE"),
                 (args.notice, "NOTICE"), (args.validation, "validation.json")]
    source_tools = [(here / "tools/extract_upstream_nms.py", "tools/extract_upstream_nms.py")]
    for path, _ in [*documents, *source_tools]:
        if not path.is_file() or not path.stat().st_size:
            raise ValueError(f"Required release input is missing or empty: {path}")
    validation = json.loads(args.validation.read_text(encoding="utf-8-sig"))
    if not isinstance(validation, dict):
        raise ValueError("validation.json must contain a JSON object")
    if validation.get("source_base_commit") != SOURCE_BASE_COMMIT:
        raise ValueError("validation.json does not describe the current stable source baseline")
    upstream = json.loads(args.upstream_release.read_text(encoding="utf-8-sig"))
    if upstream.get("version_number") != UPSTREAM_VERSION or upstream.get("version_type") != "release":
        raise ValueError("Upstream metadata must describe the official stable FancyNpcs 2.12.1 release")
    if "MIT License" not in (repo / "LICENSE").read_text(encoding="utf-8-sig"):
        raise ValueError("Upstream LICENSE does not contain the expected MIT license")
    if (repo / "SOURCE_COMMIT").read_text(encoding="utf-8-sig").strip() != SOURCE_BASE_COMMIT:
        raise ValueError("Source archive fallback commit differs from the verified stable baseline")
    for module, expected in (("fancynpcs-v2", CORE_VERSION), ("fancynpcs-model", ADDON_VERSION)):
        actual = (repo / "plugins" / module / "VERSION").read_text(encoding="utf-8-sig").strip()
        if actual != expected:
            raise ValueError(f"Source VERSION mismatch for {module}: {actual!r} != {expected!r}")
    core, core_report = find_jar(repo, "fancynpcs-v2", "FancyNpcs", CORE_VERSION, args.core_jar)
    addon, addon_report = find_jar(repo, "fancynpcs-model", "FancyNpcsModel", ADDON_VERSION, args.addon_jar)
    validated_jars = {item["filename"]: item for item in validation.get("artifacts", [])}
    for report in (core_report, addon_report):
        filename = f"{report['name']}-{report['version']}.jar"
        if validated_jars.get(filename, {}).get("sha256") != report["sha256"]:
            raise ValueError(f"validation.json has no matching final JAR hash: {filename}; collect fresh evidence first")
    sources = source_files(repo)
    dist = args.dist.resolve()
    if dist == repo or (dist.is_relative_to(repo) and dist != repo / "dist"):
        raise ValueError("Output inside the repository must use its dist/ directory")
    dist.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix=".fancynpcs-release-", dir=dist) as temporary:
        stage = Path(temporary)
        core_name = f"FancyNpcs-{CORE_VERSION}.jar"
        addon_name = f"FancyNpcsModel-{ADDON_VERSION}.jar"
        shutil.copyfile(core, stage / core_name)
        shutil.copyfile(addon, stage / addon_name)
        for filename, expected in ((core_name, core_report), (addon_name, addon_report)):
            if sha256(stage / filename) != expected["sha256"]:
                raise ValueError(f"JAR changed while packaging: {filename}; finish the build before packaging")
        install = stage / f"{RELEASE_NAME}-install.zip"
        install_entries = [(stage / core_name, f"plugins/{core_name}"),
                           (stage / addon_name, f"plugins/{addon_name}"), *documents]
        write_zip(install, install_entries)
        source = stage / f"{RELEASE_NAME}-source.zip"
        prefix = "FancyNpcs-ModelEngine/"
        source_entries = [(path, prefix + relative) for path, relative in sources]
        write_zip(source, source_entries)

        artifact_paths = [stage / core_name, stage / addon_name, install, source]
        artifacts = {path.name: {"bytes": path.stat().st_size, "sha256": sha256(path)}
                     for path in artifact_paths}
        checksums = stage / f"{RELEASE_NAME}-checksums.sha256"
        checksums.write_text("".join(f"{value['sha256']}  {name}\n"
                                     for name, value in sorted(artifacts.items())), encoding="utf-8", newline="\n")
        report = {"schema": 1, "release": RELEASE_NAME, "jar_checks": [core_report, addon_report],
                  "zip_crc": "passed", "zip_entry_sha256": "passed", "source_files": len(sources),
                  "source_excludes": sorted(EXCLUDED_DIRS),
                  "source_scope": "Complete FancyNpcs v2/API/addon and packet sources, all NMS branches and shared build dependencies; unrelated plugins and website media excluded",
                  "source_base_commit": SOURCE_BASE_COMMIT, "upstream_version": UPSTREAM_VERSION,
                  "baseline_nms_source_versions": list(BASELINE_NMS_VERSIONS),
                  "upstream_metadata_sha256": sha256(args.upstream_release),
                  "commercial_model_jars_in_packages": False,
                  "validation_sha256": sha256(args.validation), "artifacts": artifacts}
        report_path = stage / f"{RELEASE_NAME}-package-report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        # Every byte and descriptor check above passes before publishing artifacts.
        for path in [*artifact_paths, checksums, report_path]:
            os.replace(path, dist / path.name)
    print(json.dumps({"output": str(dist), **report}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Release packaging failed: {error}") from error
