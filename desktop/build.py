#!/usr/bin/env python3
"""Gera desktop/akkos-print.html (arquivo único, offline) a partir de src/app.html + vendor/."""
from pathlib import Path

here = Path(__file__).parent
app = (here / "src" / "app.html").read_text(encoding="utf-8")

def script(name, note):
    code = (here / "vendor" / name).read_text(encoding="utf-8")
    assert "</script" not in code.lower(), name
    return f"<script>/* {note} */\n{code}\n</script>"

vendor = "\n".join([
    script("qrcode.js", "qrcode-generator — MIT — Kazuhiko Arase"),
    script("pdf.min.js", "pdf.js — Apache-2.0 — Mozilla"),
    script("pdf.worker.min.js", "pdf.js worker — Apache-2.0 — Mozilla"),
])
out = app.replace("<!--VENDOR-->", vendor, 1)
(here / "akkos-print.html").write_text(out, encoding="utf-8")
print(f"akkos-print.html: {len(out.encode()) // 1024} KB")
