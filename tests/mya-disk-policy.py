#!/usr/bin/env python3
from __future__ import annotations

import base64
import importlib.machinery
import importlib.util
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(name: str, path: pathlib.Path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def encoded(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def expect_value_error(action) -> None:
    try:
        action()
    except ValueError:
        return
    raise AssertionError("unsafe remote path was accepted")


def main() -> None:
    scripts = ROOT / "companion" / "termux"
    listing = load("daak_mya_list", scripts / "mya-list")
    preview = load("daak_mya_preview", scripts / "mya-preview")
    fetch = load("daak_mya_fetch", scripts / "mya-fetch")

    root = "sftp://mya-l11/ServerShare"
    assert listing.decode_path(encoded(root)) == root
    assert listing.decode_path(encoded(root + "/Documentation")) == root + "/Documentation"
    assert listing.decode_path(encoded(root + "/../Users")) == root
    assert listing.decode_path(encoded("sftp://attacker/share")) == root

    relative, parent, name = preview.decode_file(encoded(root + "/Documentation/OPERATIONS.md"))
    assert relative == "Documentation/OPERATIONS.md"
    assert parent == "Documentation"
    assert name == "OPERATIONS.md"
    assert fetch.decode_file(encoded(root + "/README-SERVER.txt")) == (
        "README-SERVER.txt", "README-SERVER.txt"
    )
    expect_value_error(lambda: preview.decode_file(encoded(root + "/../secret")))
    expect_value_error(lambda: fetch.decode_file(encoded("sftp://attacker/share/file")))

    command = listing.remote_command("Documentation")
    assert "/Users/mac/ServerShare" in command
    assert "BatchMode=yes" in (scripts / "mya-list").read_text(encoding="utf-8")
    assert "StrictHostKeyChecking=no" not in "\n".join(
        path.read_text(encoding="utf-8") for path in
        (scripts / "mya-list", scripts / "mya-preview", scripts / "mya-fetch")
    )
    assert "smb-credentials" not in "\n".join(
        path.read_text(encoding="utf-8") for path in
        (scripts / "mya-list", scripts / "mya-preview", scripts / "mya-fetch")
    )


if __name__ == "__main__":
    main()
