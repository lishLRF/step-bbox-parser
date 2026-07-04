# Security Policy

## Supported Versions

Only the latest release line receives security fixes.

| Version | Supported |
|---------|-----------|
| 0.x     | ✅        |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security problems.

Instead, email **lishLRF@users.noreply.github.com** with:
- a description of the issue,
- steps to reproduce,
- the affected version,
- any known impact.

We will acknowledge within 72 hours and aim to publish a fix within 30 days.

## Upload handling

The service accepts user-uploaded `.stp`/`.step` files. Mitigations:

- File-type allow-list (magic-byte + extension check).
- Size cap (default 500 MB, configurable).
- Parsing runs on a sandboxed thread pool with a hard timeout.
- Uploaded files are stored outside the web root and deleted after parsing.
