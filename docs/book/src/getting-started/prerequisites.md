# Prerequisites

The local network needs Docker and nothing else. No Python, no scripts, no Node: every step is a
`docker compose` command.

| Tool | Why | Check |
|---|---|---|
| **Docker** with **Compose v2.24 or newer** (Docker Desktop on macOS and Windows, Docker Engine on Linux) | Runs every gate (13 long-running containers each) and the tests | `docker compose version` |
| **git** | Gets the source | `git --version` |
| **curl** | The quick checks in this book (optional) | `curl --version` |
| **Postman** desktop app | Tests the endpoints by hand (optional, [chapter 5](./postman.md)) | — |

Compose 2.24 is the oldest version with every feature `local/compose.yaml` uses (inline `configs`
with `content:`, optional dependencies with `required: false`). The book was run with Compose 2.27.

## Machine

- **Memory:** about **4 GB of free RAM** for two gates, and about 2 GB more for each extra gate.
  Per gate, edelivery is capped at 768 MB, xml-mapper at 384 MB, TIM at 256 MB and the developer mock
  at 128 MB; PostgreSQL, Ruuter and ReSql take the rest.
- **Ports:** each gate uses 8 host ports on `127.0.0.1` with its own prefix: G1 27xxx, G2
  28xxx. They avoid the standard Ruuter (8086), ReSql (8090) and PostgreSQL (5432) ports, so the gates
  run next to other local containers. The full list is in
  [Ports and credentials](../reference/ports-and-credentials.md).
- **First start:** the published images are pulled, and three images are built from source
  (edelivery, xml-mapper and the registry). That takes a few minutes. Later starts take seconds.

On Linux, check the ports are free:

```bash
ss -ltn | grep -E ':2(7|8)0(80|81|82|86|87|88|89|90|32)\b' || echo "all free"
```

## Get the source

```bash
git clone https://github.com/kemit-ee/efti-gate-ee.git
cd efti-gate-ee
git checkout dev
cd local
```

The commands in the getting-started chapters run in `local/`. The reference chapters use full
`http://127.0.0.1:…` URLs, so they work from anywhere.

**Shell notes for macOS and Windows.** The book's examples are bash. Two things differ elsewhere:

- `$(cat /proc/sys/kernel/random/uuid)` makes a fresh UUID on Linux. On macOS use `$(uuidgen)`; in
  PowerShell use `$([guid]::NewGuid())`.
- Line continuations (`\`) are bash. In PowerShell put the command on one line or use a backtick.

The `docker compose` commands themselves are the same on every system.

Next: [Run two gates locally](./run-locally.md).
