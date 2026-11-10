"""Operator packaging only; does NOT grant deployment trust or change OS ACLs."""
import argparse
import json
from pathlib import Path
import shutil
from trusted_runtime import FILES, asset_hashes


def package(source, destination):
    destination.mkdir(parents=True, exist_ok=False)
    for name in FILES:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    (destination / "runtime-seal.json").write_text(
        json.dumps({"version": 1, "files": asset_hashes(destination)}, sort_keys=True), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    package(Path(__file__).absolute().parents[1], args.destination.absolute())
    print("PACKAGED, NOT TRUSTED: separate owner and read-only agent permissions required")


if __name__ == "__main__":
    main()
