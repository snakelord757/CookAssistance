"""Probe entry for THIS controller's local boundary; not an OpenCode adapter."""
import argparse
from pathlib import Path

from context_gate import ContextGate
from context_release import ReleasePolicy
from loop_security import Audit, parse, post, endpoint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    args = parser.parse_args()
    try:
        probe = parse(args.probe.read_bytes())
        gate = ContextGate(Audit(probe["audit"]), ReleasePolicy(probe["policy"]))
        payload = probe["request"]
        public = gate.authorize(payload["stage"], payload["system"], payload["context"])
        post(endpoint(probe["destination"], local=True), public, probe["token"])
        return 0
    except Exception:
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
