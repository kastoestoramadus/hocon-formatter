#!/usr/bin/env python3
"""Packs the Scala Native binary into a platform wheel, the way ruff ships its binary.

pip installs a wheel's `<name>.data/scripts/` onto PATH as-is, so the wheel needs no Python code:
installing it is installing the binary. That is what lets the pre-commit hooks use
`language: python`, which pre-commit supports everywhere it runs, instead of downloading a binary
at hook run time. Standard library only, so building a wheel needs nothing installed.
"""

import argparse
import base64
import hashlib
import platform
import re
import stat
import sys
import zipfile
from pathlib import Path

NAME = "hocon-formatter"
DISTRIBUTION = NAME.replace("-", "_")
README = Path(__file__).with_name("README.md")


def pep440(version: str) -> str:
    """sbt's `0.1.0-SNAPSHOT` is not a valid Python version; `0.1.0.dev0` sorts the same way."""
    return re.sub(r"-SNAPSHOT$", ".dev0", version)


def platform_tag(binary: bytes) -> str:
    """The tag pip checks before installing: what the binary actually needs, not the build host.

    On Linux the newest glibc symbol version the binary references is the oldest glibc it runs on.
    """
    machine = platform.machine().lower()
    if sys.platform == "darwin":
        return f"macosx_11_0_{'arm64' if machine in ('arm64', 'aarch64') else 'x86_64'}"
    if sys.platform.startswith("linux"):
        glibc = max(int(minor) for minor in re.findall(rb"GLIBC_2\.(\d+)", binary))
        return f"manylinux_2_{glibc}_{machine}"
    raise SystemExit(f"no wheel platform tag for {sys.platform}; pass --platform-tag")


def record_line(path: str, content: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode()
    return f"{path},sha256={digest},{len(content)}"


def build(binary_path: Path, version: str, tag: str, out: Path) -> Path:
    binary = binary_path.read_bytes()
    dist_info = f"{DISTRIBUTION}-{version}.dist-info"
    files = {
        f"{DISTRIBUTION}-{version}.data/scripts/{NAME}": binary,
        f"{dist_info}/METADATA": (
            "Metadata-Version: 2.1\n"
            f"Name: {NAME}\n"
            f"Version: {version}\n"
            "Summary: Formats HOCON configuration files, or checks that they are formatted.\n"
            "Project-URL: Homepage, https://github.com/kastoestoramadus/hocon-formatter\n"
            "License: GPL-3.0-only\n"
            "Requires-Python: >=3.8\n"
            "Description-Content-Type: text/markdown\n"
            "\n" + README.read_text()
        ).encode(),
        f"{dist_info}/WHEEL": (
            "Wheel-Version: 1.0\n"
            "Generator: hocon-formatter build_wheel.py\n"
            "Root-Is-Purelib: false\n"
            f"Tag: py3-none-{tag}\n"
        ).encode(),
    }
    record_path = f"{dist_info}/RECORD"
    record = "\n".join([record_line(p, c) for p, c in files.items()] + [f"{record_path},,"]) + "\n"
    files[record_path] = record.encode()

    wheel = out / f"{DISTRIBUTION}-{version}-py3-none-{tag}.whl"
    with zipfile.ZipFile(wheel, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, content in files.items():
            entry = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            # Unix mode in the top half. pip keeps the executable bit only on what the mode says
            # is a regular file, so the type bits have to be there too.
            permissions = 0o755 if path.endswith(f"/{NAME}") else 0o644
            entry.external_attr = (stat.S_IFREG | permissions) << 16
            archive.writestr(entry, content)
    return wheel


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--binary", type=Path, required=True, help="the linked Scala Native CLI")
    parser.add_argument("--version", help="defaults to ThisBuild / version in build.sbt")
    parser.add_argument("--platform-tag", help="defaults to what the binary requires")
    parser.add_argument("--out", type=Path, default=Path("."))
    args = parser.parse_args()

    version = args.version or re.search(
        r'ThisBuild / version\s*:=\s*"([^"]+)"',
        (Path(__file__).parent.parent / "build.sbt").read_text(),
    ).group(1)
    tag = args.platform_tag or platform_tag(args.binary.read_bytes())
    print(build(args.binary, pep440(version), tag, args.out))


if __name__ == "__main__":
    main()
