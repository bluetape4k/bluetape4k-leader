#!/usr/bin/env python3
"""Verify Issue #994 facade source and Kotlin consumer bytecode; observe JVM ABI differences."""

from __future__ import annotations

import argparse
import contextlib
import csv
import difflib
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BASELINE_HEAD = "ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f"
LEGACY_HEAD = "a6e17f71cdfde18b6d7e8575c7db26385902e9b4"
DECLARATION_SHA256 = "a6e5d530ab2c39f3b46f04360cf4844396db60e0ccd4462ca296bb1e1ec88a5b"
CALLSITE_SHA256 = "15c43af9dae781a37a419ff1c3b624e0a5909fcf0eb828ca52ef0f1125e04ae6"
DYNAMODB_ABI_SHA256 = "bb8f880f7f74a3867ddca612b256651cad37ee7d68c91957c94eac920c4dd7a9"
DYNAMODB_HIDDEN_SIGNATURE_DELTA = {"suspendRunIfLeader", "suspendRunIfLeaderGroup"}
DECLARATIONS = Path("verification/issue-994/facade-declarations-baseline.tsv")
CALLSITES = Path("verification/issue-994/facade-call-sites-baseline.tsv")
LEGACY_FIXTURE = Path("verification/issue-994/DynamoDbLegacySourceConsumerTest.kt")
DYNAMODB_ABI_BASELINE = Path("verification/issue-994/dynamodb-hidden-shim-abi-baseline.json")

FUNCTION_START = re.compile(
    r"(?m)^(?P<modifiers>(?:(?:public|private|protected|internal|suspend|inline|"
    r"tailrec|operator|infix|external|override|actual|expect)\s+)*)fun\s+"
    r"(?:<[^\n>]+>\s*)?"
    r"(?P<receiver>[A-Za-z_][A-Za-z0-9_.]*(?:<[^\n>]+>)?)\."
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\("
)
METHOD_HEADER = re.compile(r"^\s+(?:public|protected|private)\b.*\)\s*;$")
METHOD_REF = re.compile(r"// Method ([^./\s]+(?:/[^./\s]+)*)\.([^:]+):(\(.*)$")


class VerificationError(RuntimeError):
    pass


class AllowedAbiDifference(VerificationError):
    pass


class CleanupVerificationError(VerificationError):
    pass


ABI_LINKAGE_EXCEPTION = re.compile(
    r'(?m)^\s*(?:Caused by:\s*)?(?:Exception in thread "[^"]+"\s+)?'
    r"java\.lang\.NoSuchMethodError(?::|\s*$)"
)


def is_historical_abi_linkage_failure(error: VerificationError) -> bool:
    return ABI_LINKAGE_EXCEPTION.search(str(error)) is not None


@dataclass(frozen=True)
class Declaration:
    source: str
    start: int
    end: int
    modifiers: str
    receiver: str
    name: str
    parameters: tuple[str, ...]
    header_end: int

    @property
    def key(self) -> tuple[str, str, str, tuple[str, ...]]:
        return self.source, self.receiver, self.name, self.parameters

    @property
    def is_inline(self) -> bool:
        return bool(re.search(r"\binline\b", self.modifiers))

    @property
    def is_crossinline(self) -> bool:
        return bool(re.search(r"\bcrossinline\s+action\s*:", self.header))

    @property
    def header(self) -> str:
        return self._text[self.start : self.header_end]

    # The full source is attached only while parsing; it is excluded from equality.
    _text: str = ""


def run(command: list[str], cwd: Path = ROOT, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and result.returncode != 0:
        excerpt = "\n".join(result.stdout.splitlines()[-80:])
        raise VerificationError(f"command failed ({result.returncode}): {' '.join(command)}\n{excerpt}")
    return result


def parse_tsv_bytes(path: Path, data: bytes, expected_sha: str, expected_rows: int) -> list[dict[str, str]]:
    actual_sha = hashlib.sha256(data).hexdigest()
    if actual_sha != expected_sha:
        raise VerificationError(f"{path}: SHA-256 mismatch: expected {expected_sha}, got {actual_sha}")
    rows = list(csv.DictReader(data.decode("utf-8").splitlines(), delimiter="\t"))
    if len(rows) != expected_rows:
        raise VerificationError(f"{path}: expected {expected_rows} rows, found {len(rows)}")
    key_name = "declaration_key" if path == DECLARATIONS else "callsite_key"
    keys = [row[key_name] for row in rows]
    if len(keys) != len(set(keys)):
        raise VerificationError(f"{path}: duplicate {key_name}")
    return rows


def load_tsv(path: Path, expected_sha: str, expected_rows: int) -> list[dict[str, str]]:
    return parse_tsv_bytes(path, (ROOT / path).read_bytes(), expected_sha, expected_rows)


def require_sha256(path: Path, data: bytes, expected_sha: str) -> None:
    actual_sha = hashlib.sha256(data).hexdigest()
    if actual_sha != expected_sha:
        raise VerificationError(f"{path}: SHA-256 mismatch: expected {expected_sha}, got {actual_sha}")


def load_manifests() -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    declarations = load_tsv(DECLARATIONS, DECLARATION_SHA256, 37)
    callsites = load_tsv(CALLSITES, CALLSITE_SHA256, 72)
    declaration_map = {row["declaration_key"]: row for row in declarations}
    if sum(row["kind"] == "source" for row in declarations) != 34:
        raise VerificationError("declaration manifest must contain 34 source facades")
    if sum(row["kind"] == "shim" for row in declarations) != 2:
        raise VerificationError("declaration manifest must contain two DynamoDB shims")
    helpers = [row for row in declarations if row["kind"] == "helper"]
    if len(helpers) != 1 or helpers[0]["function"] != "etcdClientOf":
        raise VerificationError("declaration manifest must retain exactly the etcdClientOf helper")
    for row in declarations:
        expected_target = ("1", "0") if row["kind"] == "helper" else ("0", "0")
        if (row["target_inline"], row["target_crossinline"]) != expected_target:
            raise VerificationError(f"invalid target modifiers for {row['declaration_key']}")
    for row in callsites:
        declaration = declaration_map.get(row["declaration_key"])
        if declaration is None:
            raise VerificationError(f"callsite references missing declaration: {row['callsite_key']}")
        if row["owner"] != declaration["owner"]:
            raise VerificationError(f"callsite owner mismatch: {row['callsite_key']}")
        if row["call_style"] == "explicit":
            if row["name"] != declaration["jvm_name"] or row["descriptor"] != declaration["descriptor"]:
                raise VerificationError(f"explicit callsite descriptor mismatch: {row['callsite_key']}")
        elif row["call_style"] == "default":
            if row["name"] != declaration["jvm_name"] + "$default":
                raise VerificationError(f"default callsite name mismatch: {row['callsite_key']}")
            base_descriptor = declaration["descriptor"]
            close = base_descriptor.rfind(")")
            expected_descriptor = base_descriptor[:close] + "ILjava/lang/Object;" + base_descriptor[close:]
            if row["descriptor"] != expected_descriptor:
                raise VerificationError(f"default callsite descriptor mismatch: {row['callsite_key']}")
            if row["expected_entry"] != "$default":
                raise VerificationError(f"default callsite entry mismatch: {row['callsite_key']}")
        elif row["expected_entry"] != "main":
            raise VerificationError(f"explicit callsite entry mismatch: {row['callsite_key']}")
        else:
            raise VerificationError(f"unknown call style: {row['callsite_key']}")
    current = [row for row in callsites if row["kind"] == "current"]
    legacy = [row for row in callsites if row["kind"] == "legacy"]
    if len(current) != 68 or len(legacy) != 4:
        raise VerificationError(f"callsite inventory must be current=68, legacy=4; got {len(current)}, {len(legacy)}")
    return declarations, callsites


def matching_close(text: str, opening: int, left: str, right: str) -> int:
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == left:
            depth += 1
        elif text[index] == right:
            depth -= 1
            if depth == 0:
                return index
    raise VerificationError(f"unclosed {left} at byte offset {opening}")


def split_top_level(text: str, delimiter: str) -> list[str]:
    result: list[str] = []
    start = 0
    parens = angles = brackets = braces = 0
    for index, character in enumerate(text):
        if character == "(": parens += 1
        elif character == ")": parens -= 1
        elif character == "<": angles += 1
        elif character == ">": angles -= 1
        elif character == "[": brackets += 1
        elif character == "]": brackets -= 1
        elif character == "{": braces += 1
        elif character == "}": braces -= 1
        elif character == delimiter and not (parens or angles or brackets or braces):
            result.append(text[start:index].strip())
            start = index + 1
    result.append(text[start:].strip())
    return [item for item in result if item]


def top_level_type(segment: str) -> str:
    parts = split_top_level(segment, "=")
    declaration = parts[0]
    if ":" not in declaration:
        raise VerificationError(f"cannot parse Kotlin parameter: {segment}")
    value = declaration.split(":", 1)[1].strip()
    if "->" in value:
        if re.search(r"\bsuspend\b", value):
            return "ContinuationFunction1"
        left = value.split("->", 1)[0].strip()
        return "Function0" if left == "()" else "Function1"
    return re.sub(r"\?\s*$", "", value).strip()


def parse_declarations(source: str, text: str) -> list[Declaration]:
    matches = list(FUNCTION_START.finditer(text))
    declarations: list[Declaration] = []
    for index, match in enumerate(matches):
        opening = match.end() - 1
        closing = matching_close(text, opening, "(", ")")
        raw_params = text[opening + 1 : closing]
        params = tuple(top_level_type(part) for part in split_top_level(raw_params, ","))
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        declarations.append(
            Declaration(
                source=source,
                start=match.start(),
                end=end,
                modifiers=match.group("modifiers"),
                receiver=match.group("receiver"),
                name=match.group("name"),
                parameters=params,
                header_end=closing + 1,
                _text=text,
            )
        )
    return declarations


def target_key(row: dict[str, str]) -> tuple[str, str, str, tuple[str, ...]]:
    return (
        row["source_file"],
        row["receiver"],
        row["function"],
        tuple(row["parameter_types"].split(",")) if row["parameter_types"] else (),
    )


def transform_target_block(block: str) -> str:
    block = re.sub(r"(?m)^((?:suspend\s+)?)inline\s+fun\b", r"\1fun", block, count=1)
    block = re.sub(r"\bcrossinline\s+action(?=\s*:)", "action", block, count=1)
    block = re.sub(r"\)\s*\{\s*action\(\)\s*\}", ", action)", block)
    # These two Kotlin-hidden DynamoDB shims no longer claim JVM ABI support.
    # Keep this wording correction explicit in the source transformation oracle.
    text_rewrites = (
        (
            "/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */",
            "/** 리팩터링 전 suspend facade 이름을 유지하는 숨김 진입점입니다. JVM ABI 호환성은 보장하지 않습니다. */",
        ),
        (
            '@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)',
            '@Deprecated("리팩터링 전 숨김 진입점", level = DeprecationLevel.HIDDEN)',
        ),
        (
            "/** Binary compatibility shim for the pre-refactor JVM facade. */",
            "/** 리팩터링 전 그룹 suspend facade 이름을 유지하는 숨김 진입점입니다. JVM ABI 호환성은 보장하지 않습니다. */",
        ),
        (
            '@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)',
            '@Deprecated("리팩터링 전 숨김 진입점", level = DeprecationLevel.HIDDEN)',
        ),
    )
    for old, new in text_rewrites:
        block = block.replace(old, new)
    return block


def source_at_revision(revision: str, path: str) -> str:
    result = run(["git", "show", f"{revision}:{path}"], check=False)
    if result.returncode != 0:
        raise VerificationError(f"cannot read {path} at {revision}: {result.stdout.strip()}")
    return result.stdout


def validate_source_inventory(declarations: list[dict[str, str]]) -> list[str]:
    errors: list[str] = []
    all_paths = sorted({row["source_file"] for row in declarations})
    target_rows = [row for row in declarations if row["kind"] != "helper"]
    target_map = {target_key(row): row for row in target_rows}
    if len(target_map) != 36:
        raise VerificationError(f"expected 36 unique facade/shim target keys, found {len(target_map)}")

    for path in all_paths:
        baseline = source_at_revision(BASELINE_HEAD, path)
        current_path = ROOT / path
        if not current_path.is_file():
            errors.append(f"missing source file: {path}")
            continue
        current = current_path.read_text(encoding="utf-8")
        base_declarations = parse_declarations(path, baseline)
        current_declarations = parse_declarations(path, current)
        base_by_key = {item.key: item for item in base_declarations}
        current_by_key = {item.key: item for item in current_declarations}
        if len(base_by_key) != len(base_declarations) or len(current_by_key) != len(current_declarations):
            errors.append(f"duplicate extension declaration signature in {path}")
            continue
        if base_by_key.keys() != current_by_key.keys():
            added = sorted(current_by_key.keys() - base_by_key.keys())
            removed = sorted(base_by_key.keys() - current_by_key.keys())
            errors.append(f"extension inventory changed in {path}: added={added}, removed={removed}")
            continue

        expected = baseline
        for declaration in reversed(base_declarations):
            target = target_map.get(declaration.key)
            block = expected[declaration.start : declaration.end]
            if target is None:
                current_block = current[current_by_key[declaration.key].start : current_by_key[declaration.key].end]
                if current_block != block:
                    errors.append(f"non-target declaration changed: {path}#{declaration.name}")
                continue
            if (str(int(declaration.is_inline)), str(int(declaration.is_crossinline))) != (
                target["initial_inline"],
                target["initial_crossinline"],
            ):
                errors.append(f"baseline modifier mismatch: {target['declaration_key']}")
            current_declaration = current_by_key[declaration.key]
            if (str(int(current_declaration.is_inline)), str(int(current_declaration.is_crossinline))) != (
                target["target_inline"],
                target["target_crossinline"],
            ):
                errors.append(f"target modifier mismatch: {target['declaration_key']}")
            transformed = transform_target_block(block)
            expected = expected[: declaration.start] + transformed + expected[declaration.end :]

        if path.endswith("leader/etcd/support/ClientSupport.kt") and current != baseline:
            errors.append("ClientSupport.kt changed; etcdClientOf and its sibling helpers must remain byte-identical")
        if current != expected:
            difference = "\n".join(
                list(difflib.unified_diff(expected.splitlines(), current.splitlines(), fromfile="expected", tofile=path))[:24]
            )
            errors.append(f"source differs from the approved 36-facade transformation in {path}:\n{difference}")
    return errors


def module_root(module: str) -> Path:
    return ROOT / f"leader-{module}" / "build" / "classes" / "kotlin"


def class_name_from_header(header: str) -> str:
    return header.split("(", 1)[0].strip().split()[-1]


def method_blocks(javap_output: str) -> dict[str, list[str]]:
    blocks: dict[str, list[str]] = {}
    active_name: str | None = None
    active: list[str] = []
    for line in javap_output.splitlines():
        if METHOD_HEADER.match(line):
            if active_name is not None:
                blocks.setdefault(active_name, []).append("\n".join(active))
            active_name = class_name_from_header(line)
            active = [line]
        elif active_name is not None:
            active.append(line)
    if active_name is not None:
        blocks.setdefault(active_name, []).append("\n".join(active))
    return blocks


def javap(class_name: str, module: str, kind: str, *, verbose: bool = False) -> str:
    class_dir = module_root(module) / kind
    class_file = class_dir / (class_name.replace(".", "/") + ".class")
    if not class_file.is_file():
        raise VerificationError(f"compiled class missing: {class_file}")
    return run(
        ["javap", "-p", "-c", "-s", *( ["-v"] if verbose else [] ), "-classpath", str(class_dir), class_name]
    ).stdout


def static_invocations(block: str) -> list[tuple[str, str, str]]:
    result: list[tuple[str, str, str]] = []
    for line in block.splitlines():
        if "invokestatic" not in line:
            continue
        match = METHOD_REF.search(line)
        if match:
            result.append((match.group(1), match.group(2), match.group(3)))
    return result


def verify_consumer_bytecode(callsites: list[dict[str, str]], *, kind: str = "test") -> list[str]:
    expected_kind = "current" if kind == "test" else "legacy"
    rows = [row for row in callsites if row["kind"] == expected_kind]
    expected_count = 68 if kind == "test" else 4
    if len(rows) != expected_count:
        raise VerificationError(f"expected {expected_count} {kind} callsites, found {len(rows)}")
    outputs: dict[tuple[str, str], dict[str, list[str]]] = {}
    library_methods: dict[tuple[str, str], set[tuple[str, str]]] = {}
    errors: list[str] = []
    for row in rows:
        module = row["module"]
        class_name = row["consumer_class"]
        cache_key = module, class_name
        if cache_key not in outputs:
            outputs[cache_key] = method_blocks(javap(class_name, module, kind="test"))
        library_key = module, row["owner"]
        if library_key not in library_methods:
            library = parse_library_entries(javap(row["owner"], module, kind="main"))
            library_methods[library_key] = {
                (entry[0], entry[1])
                for entry in library
                if "public" in entry[2] and "static" in entry[2]
            }
        method = row["consumer_method"]
        blocks = outputs[cache_key].get(method, [])
        expected_owner = row["owner"].replace(".", "/")
        expected_name = row["name"]
        actual = [
            invocation
            for block in blocks
            for invocation in static_invocations(block)
            if invocation[0] == expected_owner
            and invocation[1] == expected_name
            and (invocation[1], invocation[2]) in library_methods[library_key]
        ]
        if len(actual) != 1:
            errors.append(
                f"{row['callsite_key']}: expected one INVOKESTATIC to the current public library facade "
                f"{expected_owner}.{expected_name}, found {actual}"
            )
    return errors


def parse_library_entries(javap_output: str) -> set[tuple[str, str, str, str]]:
    entries: set[tuple[str, str, str, str]] = set()
    for name, blocks in method_blocks(javap_output).items():
        for block in blocks:
            descriptor_match = re.search(r"(?m)^\s*descriptor: (\S+)", block)
            header = block.splitlines()[0]
            if descriptor_match:
                entries.add((name, descriptor_match.group(1), header, block))
    return entries


def normalize_javap_attribute(lines: list[str]) -> list[str]:
    normalized: list[str] = []
    for line in lines:
        stripped = line.strip()
        if re.match(r"\d+:\s+#\d+", stripped):
            # Constant-pool indexes vary across recompilations. javap prints the
            # symbolic annotation and argument values on the following lines.
            line = re.sub(r"#\d+", "#", line)
        normalized.append(line.rstrip())
    return normalized


def parse_verbose_method_metadata(javap_output: str) -> dict[tuple[str, str], dict[str, object]]:
    metadata: dict[tuple[str, str], dict[str, object]] = {}
    for name, blocks in method_blocks(javap_output).items():
        for block in blocks:
            lines = block.splitlines()
            descriptor_match = re.search(r"(?m)^\s*descriptor: (\S+)", block)
            flags_match = re.search(r"(?m)^\s*flags: (.+)$", block)
            if descriptor_match is None or flags_match is None:
                continue

            signature_match = re.search(r"(?m)^\s*Signature:.*?(?://\s*(.*))?$", block)
            signature = signature_match.group(1).strip() if signature_match and signature_match.group(1) else None
            attributes: dict[str, list[str]] = {}
            active_attribute: str | None = None
            for line in lines[1:]:
                if line.strip() == "}" or line.startswith("SourceFile:"):
                    break
                attribute_match = re.match(r"^\s{4}([A-Za-z][A-Za-z0-9]*):(?:\s*(.*))?$", line)
                if attribute_match:
                    attribute = attribute_match.group(1)
                    if attribute == "Code":
                        active_attribute = None
                        continue
                    active_attribute = attribute
                    value = attribute_match.group(2)
                    attributes[attribute] = [value] if value else []
                    continue
                if active_attribute is not None:
                    attributes[active_attribute].append(line)

            for attribute, values in attributes.items():
                attributes[attribute] = normalize_javap_attribute(values)
            for attribute in ("descriptor", "flags", "Signature"):
                attributes.pop(attribute, None)

            header = lines[0].strip()
            key = name, descriptor_match.group(1)
            if key in metadata:
                raise VerificationError(f"duplicate verbose JVM method entry: {key[0]}{key[1]}")
            metadata[key] = {
                "header": header,
                "descriptor": descriptor_match.group(1),
                "flags": flags_match.group(1).strip(),
                "signature": signature,
                "attributes": attributes,
            }
    return metadata


def load_dynamodb_abi_baseline() -> dict[str, object]:
    try:
        baseline_bytes = (ROOT / DYNAMODB_ABI_BASELINE).read_bytes()
        baseline = json.loads(baseline_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot read DynamoDB hidden-shim ABI baseline {DYNAMODB_ABI_BASELINE}: {error}") from error
    require_sha256(DYNAMODB_ABI_BASELINE, baseline_bytes, DYNAMODB_ABI_SHA256)
    if baseline.get("owner") != "io.bluetape4k.leader.dynamodb.DynamoDbClientExtensionsKt":
        raise VerificationError("DynamoDB hidden-shim ABI baseline owner is invalid")
    if baseline.get("baseline_commit") != BASELINE_HEAD:
        raise VerificationError("DynamoDB hidden-shim ABI baseline commit does not match the pinned baseline revision")
    methods = baseline.get("methods")
    if not isinstance(methods, list) or len(methods) != 4:
        raise VerificationError("DynamoDB hidden-shim ABI baseline must contain exactly four methods")
    expected_names = {
        "suspendRunIfLeader",
        "suspendRunIfLeader$default",
        "suspendRunIfLeaderGroup",
        "suspendRunIfLeaderGroup$default",
    }
    if {row.get("name") for row in methods if isinstance(row, dict)} != expected_names:
        raise VerificationError("DynamoDB hidden-shim ABI baseline method inventory is incomplete")
    return baseline


def verify_dynamodb_hidden_shim_abi() -> list[str]:
    baseline = load_dynamodb_abi_baseline()
    owner = baseline["owner"]
    try:
        output = javap(owner, "dynamodb", kind="main", verbose=True)
    except VerificationError as error:
        return [f"could not observe DynamoDB hidden-shim JVM metadata: {error}"]
    current = parse_verbose_method_metadata(output)
    differences: list[str] = []
    for expected in baseline["methods"]:
        key = expected["name"], expected["descriptor"]
        actual = current.get(key)
        if actual is None:
            differences.append(f"missing baseline hidden-shim entry {key[0]}{key[1]}")
            continue
        for field in ("descriptor", "flags", "header", "signature", "attributes"):
            if actual[field] != expected[field]:
                if (
                    field == "signature"
                    and key[0] in DYNAMODB_HIDDEN_SIGNATURE_DELTA
                    and expected[field] is not None
                    and actual[field] is None
                ):
                    differences.append(f"{key[0]}: accepted loss of generic JVM Signature metadata")
                else:
                    differences.append(f"{key[0]}: {field} differs from the baseline")
    return differences


def verify_library_abi(declarations: list[dict[str, str]], callsites: list[dict[str, str]]) -> list[str]:
    requirements: set[tuple[str, str, str, str]] = set()
    for row in declarations:
        requirements.add((row["module"], row["owner"], row["jvm_name"], row["descriptor"]))
    for row in callsites:
        requirements.add((row["module"], row["owner"], row["name"], row["descriptor"]))
    cache: dict[tuple[str, str], set[tuple[str, str, str, str]]] = {}
    differences: list[str] = []
    for module, owner, name, descriptor in sorted(requirements):
        key = module, owner
        if key not in cache:
            try:
                output = javap(owner, module, kind="main")
                cache[key] = parse_library_entries(output)
            except VerificationError as error:
                cache[key] = set()
                differences.append(f"{module}: could not observe {owner}: {error}")
        matches = [entry for entry in cache[key] if entry[0] == name and entry[1] == descriptor]
        if len(matches) != 1:
            differences.append(f"{module}: missing or duplicate baseline JVM entry {owner}.{name}{descriptor}")
        elif "public" not in matches[0][2] or "static" not in matches[0][2]:
            differences.append(f"{module}: baseline JVM entry is not public static: {owner}.{name}{descriptor}")
    differences.extend(verify_dynamodb_hidden_shim_abi())
    return differences


def run_self_test(declarations: list[dict[str, str]], callsites: list[dict[str, str]]) -> None:
    source = """package fixture
inline fun <T> Client.runIfLeader(name: String, crossinline action: () -> T): T =
    Elector(this).runIfLeader(name) { action() }
"""
    parsed = parse_declarations("Fixture.kt", source)
    if len(parsed) != 1 or parsed[0].key != ("Fixture.kt", "Client", "runIfLeader", ("String", "Function0")):
        raise VerificationError("self-test failed to parse a synthetic facade declaration")
    baseline = source
    transformed = transform_target_block(baseline)
    target = parse_declarations("Fixture.kt", transformed)[0]
    if target.is_inline or target.is_crossinline or "runIfLeader(name, action)" not in transformed:
        raise VerificationError("self-test failed to transform the synthetic facade")
    shim_wording = '''/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend inline fun <T> DynamoDbAsyncClient.legacySuspendRunIfLeaderGroup(
    lockName: String,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(lockName) { action() }
'''
    transformed_shim = transform_target_block(shim_wording)
    if "Binary compatibility shim" in transformed_shim or "JVM ABI 호환성은 보장하지 않습니다" not in transformed_shim:
        raise VerificationError("self-test failed to apply the approved hidden-shim wording correction")
    altered = transformed.replace("fun <T>", "inline fun <T>", 1)
    if not parse_declarations("Fixture.kt", altered)[0].is_inline:
        raise VerificationError("self-test failed to detect a changed modifier")
    added = transformed + "\nfun <T> Client.runIfLeader(id: Long, action: () -> T): T = action()\n"
    if len(parse_declarations("Fixture.kt", added)) == len(parse_declarations("Fixture.kt", transformed)):
        raise VerificationError("self-test failed to detect an added overload")
    callsite_bytes = (ROOT / CALLSITES).read_bytes()
    original_owner = callsites[0]["owner"].encode("utf-8")
    tampered_bytes = callsite_bytes.replace(original_owner, b"io.bluetape4k.leader.TamperedKt", 1)
    try:
        parse_tsv_bytes(CALLSITES, tampered_bytes, CALLSITE_SHA256, 72)
    except VerificationError:
        pass
    else:
        raise VerificationError("self-test failed to reject a tampered owner byte sequence")
    verbose_fixture = """  public static java.lang.Object legacy(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/Object;
    flags: (0x1009) ACC_PUBLIC, ACC_STATIC, ACC_SYNTHETIC
    Signature: #12 // (Ljava/lang/String;)Ljava/lang/Object;
    RuntimeVisibleAnnotations:
      0: #1(#2=e#3.#4)
        kotlin.Deprecated(
          level=Lkotlin/DeprecationLevel;.HIDDEN
        )
    Code:
      stack=1, locals=1, args_size=1
"""
    verbose_baseline = parse_verbose_method_metadata(verbose_fixture)
    tampered_flags = verbose_fixture.replace("ACC_SYNTHETIC", "ACC_FINAL")
    tampered_annotation = verbose_fixture.replace("DeprecationLevel;.HIDDEN", "DeprecationLevel;.WARNING")
    tampered_signature = verbose_fixture.replace(
        "Signature: #12 // (Ljava/lang/String;)Ljava/lang/Object;",
        "Signature: #12 // (Ljava/lang/Object;)Ljava/lang/Object;",
    )
    if parse_verbose_method_metadata(tampered_flags) == verbose_baseline:
        raise VerificationError("self-test failed to reject a tampered JVM access/synthetic flag")
    if parse_verbose_method_metadata(tampered_annotation) == verbose_baseline:
        raise VerificationError("self-test failed to reject a tampered annotation attribute")
    if parse_verbose_method_metadata(tampered_signature) == verbose_baseline:
        raise VerificationError("self-test failed to reject a tampered generic JVM Signature")
    abi_baseline_bytes = (ROOT / DYNAMODB_ABI_BASELINE).read_bytes()
    try:
        require_sha256(DYNAMODB_ABI_BASELINE, abi_baseline_bytes + b"tampered", DYNAMODB_ABI_SHA256)
    except VerificationError:
        pass
    else:
        raise VerificationError("self-test failed to reject a tampered ABI baseline artifact")
    base_errors = validate_source_inventory(declarations)
    if base_errors:
        raise VerificationError("self-test source inventory failed:\n" + "\n".join(base_errors))

    def run_legacy_observation_test(failure: VerificationError) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            sys,
            "argv",
            [str(ROOT / "verify_issue_994_facades.py"), "--dynamodb-legacy-abi-observation"],
        ):
            with mock.patch(f"{__name__}.run_legacy_consumer", side_effect=failure):
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    exit_code = main()
        return exit_code, stdout.getvalue(), stderr.getvalue()

    linkage_trace = VerificationError("command failed:\nCaused by: java.lang.NoSuchMethodError: expected facade entry")
    if not is_historical_abi_linkage_failure(linkage_trace):
        raise VerificationError("self-test failed to recognize a JVM NoSuchMethodError stack trace")
    unrelated_failure = VerificationError("AssertionError: expected NoSuchMethodError in diagnostic text")
    if is_historical_abi_linkage_failure(unrelated_failure):
        raise VerificationError("self-test misclassified ordinary failure text as an ABI linkage error")
    unqualified_linkage_name = VerificationError("NoSuchMethodError: diagnostic text")
    if is_historical_abi_linkage_failure(unqualified_linkage_name):
        raise VerificationError("self-test misclassified an unqualified diagnostic as an ABI linkage error")

    injected_error = "historical consumer links through NoSuchMethodError"
    exit_code, stdout, stderr = run_legacy_observation_test(AllowedAbiDifference(injected_error))
    if (
        exit_code != 0
        or "INFORMATIONAL:" not in stdout
        or "binary compatibility is unsupported" not in stdout
        or injected_error not in stdout
        or "PASS:" in stdout
        or stderr
    ):
        raise VerificationError("self-test failed to label an allowed historical ABI mismatch as informational")

    for failure in (
        "injected historical Kotlin source compilation failure",
        "injected runtime assertion failure mentioning NoSuchMethodError",
        "NoSuchMethodError: unqualified diagnostic text",
    ):
        exit_code, stdout, stderr = run_legacy_observation_test(VerificationError(failure))
        if exit_code != 1 or failure not in stderr or stdout:
            raise VerificationError(f"self-test failed to propagate historical consumer failure: {failure}")

    cleanup_error = "temporary historical worktree cleanup is unverified"
    exit_code, stdout, stderr = run_legacy_observation_test(CleanupVerificationError(cleanup_error))
    if exit_code != 1 or cleanup_error not in stderr or stdout:
        raise VerificationError("self-test failed to keep historical worktree cleanup failures fatal")

    print("PASS: manifest hashes, source inventory, modifier mutation, overload addition, owner tamper, flags, generic Signature, annotation, ABI baseline checksum, informational ABI linkage mismatch, fatal source/runtime failures, and fatal cleanup failure self-tests")


def run_legacy_consumer(declarations: list[dict[str, str]], callsites: list[dict[str, str]]) -> None:
    del declarations
    fixture_path = ROOT / LEGACY_FIXTURE
    if not fixture_path.is_file():
        raise VerificationError(f"historical consumer fixture missing: {fixture_path}")
    git_dir_check = run(["git", "cat-file", "-e", f"{LEGACY_HEAD}^{{commit}}"], check=False)
    if git_dir_check.returncode != 0:
        raise VerificationError(f"historical revision is unavailable: {LEGACY_HEAD}")

    with tempfile.TemporaryDirectory(prefix="issue-994-legacy-consumer-") as temporary:
        worktree = Path(temporary) / "source"
        run(["git", "worktree", "add", "--detach", str(worktree), LEGACY_HEAD])
        copied: list[Path] = []
        try:
            old_test = worktree / "leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbLegacySourceConsumerTest.kt"
            old_test.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(fixture_path, old_test)
            run(["./gradlew", ":bluetape4k-leader-dynamodb:compileTestKotlin", "--console=plain", "--no-build-cache", "--no-parallel"], cwd=worktree)
            class_name = "io.bluetape4k.leader.dynamodb.DynamoDbLegacySourceConsumerTest"
            old_classes = worktree / "leader-dynamodb/build/classes/kotlin/test/io/bluetape4k/leader/dynamodb"
            compiled = sorted(old_classes.glob("DynamoDbLegacySourceConsumerTest*.class"))
            if not compiled:
                raise VerificationError("historical fixture compilation produced no class files")
            current_class_dir = module_root("dynamodb") / "test/io/bluetape4k/leader/dynamodb"
            current_class_dir.mkdir(parents=True, exist_ok=True)
            for source in compiled:
                destination = current_class_dir / source.name
                if destination.exists():
                    raise VerificationError(f"refusing to overwrite existing consumer class: {destination}")
                shutil.copyfile(source, destination)
                copied.append(destination)

            legacy_errors = verify_consumer_bytecode(callsites, kind="legacy")
            if legacy_errors:
                raise AllowedAbiDifference("historical callsite bytecode mismatch:\n" + "\n".join(legacy_errors))
            try:
                run(
                    ["./gradlew", ":bluetape4k-leader-dynamodb:test", "--tests", class_name,
                     "--console=plain", "--no-build-cache", "--no-parallel", "--rerun-tasks",
                     "-x", ":bluetape4k-leader-dynamodb:compileTestKotlin",
                     "-x", ":bluetape4k-leader-dynamodb:testClasses"],
                    cwd=ROOT,
                )
            except VerificationError as error:
                if is_historical_abi_linkage_failure(error):
                    raise AllowedAbiDifference(f"historical consumer runtime linkage mismatch: {error}") from error
                raise
            print(
                "OBSERVED: the historical Kotlin/JVM consumer compiled at its pinned revision and linked "
                "against the candidate runtime (informational; binary compatibility is unsupported)"
            )
        finally:
            for path in copied:
                path.unlink(missing_ok=True)
            run(["git", "worktree", "remove", "--force", str(worktree)], check=False)
            remaining = run(["git", "worktree", "list", "--porcelain"], check=True).stdout
            if str(worktree) in remaining:
                raise CleanupVerificationError(f"temporary historical worktree remains registered: {worktree}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--self-test", action="store_true")
    modes.add_argument("--source-inventory", action="store_true")
    modes.add_argument("--consumer-bytecode", action="store_true")
    modes.add_argument("--dynamodb-legacy-abi-observation", action="store_true")
    modes.add_argument("--library-abi", action="store_true")
    args = parser.parse_args()

    try:
        declarations, callsites = load_manifests()
        if args.self_test:
            run_self_test(declarations, callsites)
        elif args.source_inventory:
            errors = validate_source_inventory(declarations)
            if errors:
                raise VerificationError("source inventory failed:\n" + "\n".join(errors))
            print("PASS: exactly 36 approved facade/shim transformations; etcdClientOf and non-target declarations unchanged")
        elif args.consumer_bytecode:
            errors = verify_consumer_bytecode(callsites)
            if errors:
                raise VerificationError("consumer bytecode failed:\n" + "\n".join(errors))
            print("PASS: all 68 explicit and default Kotlin consumer callsites invoke the current public facade entries")
        elif args.dynamodb_legacy_abi_observation:
            try:
                run_legacy_consumer(declarations, callsites)
            except AllowedAbiDifference as error:
                worktrees = run(["git", "worktree", "list", "--porcelain"], check=False)
                if worktrees.returncode != 0 or "issue-994-legacy-consumer-" in worktrees.stdout:
                    raise CleanupVerificationError(
                        "historical ABI observation failed and temporary worktree cleanup is unverified"
                    ) from error
                print(
                    "INFORMATIONAL: historical Kotlin/JVM consumer ABI difference was observed; "
                    f"binary compatibility is unsupported: {error}"
                )
        elif args.library_abi:
            differences = verify_library_abi(declarations, callsites)
            if differences:
                print("NOTE: observed JVM ABI differences (outside the supported Kotlin source/runtime contract):")
                for difference in differences:
                    print(f"  - {difference}")
            else:
                print("NOTE: no differences from the recorded JVM ABI baseline were observed")
            print("OBSERVED: ABI comparison is informational; Java API and JVM binary compatibility are unsupported")
        return 0
    except VerificationError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
