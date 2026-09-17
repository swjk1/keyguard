#!/usr/bin/env python3
"""Parse runpodctl JSON from stdin. Used by remote_run.sh.

`runpodctl ssh info` exits 0 even when the pod is not connectable, returning an
{"error": ...} object instead of {"ip","port"} -- and it is snake_case while the rest
of the CLI is camelCase. Both facts are easy to get wrong in shell, so the parsing
lives here and fails loudly rather than silently yielding an empty host.
"""
import json
import sys


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "id"
    raw = sys.stdin.read().strip()
    if not raw:
        print("EMPTY: no JSON on stdin", file=sys.stderr)
        return 2
    try:
        d = json.loads(raw)
    except json.JSONDecodeError as exc:
        print("UNPARSEABLE: %s" % exc, file=sys.stderr)
        print(raw[:500], file=sys.stderr)
        return 2

    if mode == "id":
        pod_id = d.get("id")
        if not pod_id:
            print("NO_ID: %s" % raw[:300], file=sys.stderr)
            return 1
        print(pod_id)
        return 0

    if mode == "ssh":
        if d.get("error"):
            print("NOT_READY: %s" % d["error"], file=sys.stderr)
            return 1
        ip, port = d.get("ip"), d.get("port")
        if not ip or not port:
            print("NO_ENDPOINT: %s" % raw[:300], file=sys.stderr)
            return 1
        print("%s %s" % (ip, port))
        return 0

    print("unknown mode %r" % mode, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
