"""Validate profile files and simple inline Markdown links without network access.

Supported links: [label](path), [label](<path with spaces>), optional titles.
Reference links, nested Markdown syntax and heading existence are out of scope.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


PROFILES = (
    "bugfix", "planer", "tech", "unit-test", "smoke_scenario",
    "smoke", "review", "dev", "product",
)
LINK = re.compile(r"\[[^\]\n]*\]\(\s*(?:<([^>\n]*)>|([^\s)]+))(?:\s+[\"'][^\n]*?[\"'])?\s*\)")
FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
INLINE_CODE = re.compile(r"(`+)(?!`)(.*?)(?<!`)\1(?!`)")


def markdown_prose(source: str) -> str:
    """Remove fenced blocks and inline code before looking for active links."""
    output: list[str] = []
    fence_char = ""
    fence_length = 0
    for line in source.splitlines():
        match = FENCE.match(line)
        if fence_char:
            if (match and match[1][0] == fence_char
                    and len(match[1]) >= fence_length and not match[2].strip()):
                fence_char = ""
            continue
        # Backtick fences cannot have backticks in their info string. A line
        # such as ```example``` is an inline code span, not an opening fence.
        if match and not (match[1][0] == "`" and "`" in match[2]):
            fence_char, fence_length = match[1][0], len(match[1])
            continue
        output.append(INLINE_CODE.sub("", line))
    return "\n".join(output)


def validate(root: Path) -> list[str]:
    """Return deterministic diagnostics; never fetch external URLs."""
    errors: list[str] = []
    try:
        if not root.is_dir():
            return [f"Root is not a directory: {root}"]
        required = [root / "AGENTS.md"] + [root / "profiles" / f"{name}.md" for name in PROFILES]
        for path in required:
            if not path.is_file():
                errors.append(f"Missing required file: {path.relative_to(root).as_posix()}")
        sources = {path for path in required if path.is_file()}
        profiles = root / "profiles"
        if profiles.is_dir():
            sources.update(path for path in profiles.glob("*.md") if path.is_file())
    except (OSError, ValueError) as exc:
        return [f"Cannot inspect root {root}: {exc}"]

    for source in sorted(sources):
        label = source.relative_to(root).as_posix()
        try:
            content = source.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeError) as exc:
            errors.append(f"Cannot read {label}: {exc}")
            continue
        for match in LINK.finditer(markdown_prose(content)):
            target = match[1] if match[1] is not None else match[2]
            if target.startswith("//") or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target):
                continue
            try:
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc or target.startswith("//") or not parsed.path:
                    continue
                local = source.parent / unquote(parsed.path)
                if not local.exists():
                    errors.append(f"Broken link in {label}: {target}")
            except (OSError, ValueError) as exc:
                errors.append(f"Invalid link in {label}: {target} ({exc})")
    return sorted(set(errors))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="Project directory to validate")
    args = parser.parse_args()
    errors = validate(args.root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        print(f"FAIL: {len(errors)} problem(s)")
        return 1
    print("PASS: AGENTS.md, 9 required profiles and local inline links are valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
