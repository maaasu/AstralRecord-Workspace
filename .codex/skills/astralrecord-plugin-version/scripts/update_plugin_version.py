from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


VERSION_RE = re.compile(
    r"^(?P<major>\d+)\.(?P<minor>\d+)(?:\.(?P<patch>\d+))?"
    r"(?:-(?P<prerelease>[0-9A-Za-z.-]+))?"
    r"(?:\+(?P<build>[0-9A-Za-z.-]+))?$"
)
ROOT_VERSION_RE = re.compile(r"(<version>)([^<]+)(</version>)")


@dataclass(frozen=True)
class CoreVersion:
    major: int
    minor: int
    patch: int

    def bump(self, level: str) -> "CoreVersion":
        if level == "major":
            return CoreVersion(self.major + 1, 0, 0)
        if level == "minor":
            return CoreVersion(self.major, self.minor + 1, 0)
        if level == "patch":
            return CoreVersion(self.major, self.minor, self.patch + 1)
        if level == "none":
            return self
        raise ValueError(f"Unsupported bump level: {level}")

    def text(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Update AstralRecord plugin pom.xml version with a SemVer-based scheme."
    )
    parser.add_argument("--pom", required=True, help="Absolute path to pom.xml")
    parser.add_argument(
        "--kind",
        choices=["release", "alpha", "beta", "rc"],
        default="release",
        help="Target version kind",
    )
    parser.add_argument(
        "--bump",
        choices=["major", "minor", "patch", "none"],
        default="patch",
        help="How to bump the core version before applying kind suffix",
    )
    parser.add_argument("--set-version", help="Explicit version to write as-is")
    parser.add_argument("--seq", type=int, help="Explicit sequence number for alpha/beta/rc")
    return parser.parse_args()


def parse_version_text(version_text: str) -> tuple[CoreVersion, str | None]:
    normalized = version_text.removesuffix("-SNAPSHOT")
    match = VERSION_RE.fullmatch(normalized)
    if not match:
        raise ValueError(f"Unsupported version format: {version_text}")
    core = CoreVersion(
        int(match.group("major")),
        int(match.group("minor")),
        int(match.group("patch") or 0),
    )
    return core, match.group("prerelease")


def infer_next_seq(current_version: str, kind: str) -> int:
    prerelease_match = re.fullmatch(
        rf"\d+\.\d+\.\d+-{kind}\.(?P<seq>\d+)",
        current_version,
    )
    if prerelease_match:
        return int(prerelease_match.group("seq")) + 1
    return 1


def build_version(current_version: str, kind: str, bump: str, seq: int | None) -> str:
    core, _ = parse_version_text(current_version)
    bumped_core = core.bump(bump)
    base = bumped_core.text()

    if kind == "release":
        return base

    resolved_seq = seq if seq is not None else infer_next_seq(current_version, kind)
    if resolved_seq < 1:
        raise ValueError("Sequence number must be 1 or greater")

    return f"{base}-{kind}.{resolved_seq}"


def read_root_version(pom_path: Path) -> str:
    text = pom_path.read_text(encoding="utf-8")
    tree = ET.fromstring(text)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    version_node = tree.find("m:version", ns)
    if version_node is None or not version_node.text:
        raise ValueError("Could not locate root project <version> in pom.xml")
    return version_node.text.strip()


def write_root_version(pom_path: Path, new_version: str) -> None:
    text = pom_path.read_text(encoding="utf-8")
    replaced = ROOT_VERSION_RE.sub(rf"\g<1>{new_version}\g<3>", text, count=1)
    if replaced == text:
        raise ValueError("Failed to replace root project <version> in pom.xml")
    pom_path.write_text(replaced, encoding="utf-8")


def main() -> int:
    args = parse_args()
    pom_path = Path(args.pom)
    if not pom_path.is_file():
        print(f"pom.xml not found: {pom_path}", file=sys.stderr)
        return 1

    current_version = read_root_version(pom_path)

    if args.set_version:
        parse_version_text(args.set_version)
        new_version = args.set_version
    else:
        new_version = build_version(
            current_version=current_version,
            kind=args.kind,
            bump=args.bump,
            seq=args.seq,
        )

    write_root_version(pom_path, new_version)
    print(f"old_version={current_version}")
    print(f"new_version={new_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
