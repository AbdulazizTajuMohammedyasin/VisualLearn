#!/bin/sh
# Smoke test of the toolchain the Anatomy skill needs.
set -u
fail=0
check() {
    name=$1; shift
    if out=$("$@" 2>&1); then echo "PASS $name: $(echo "$out" | head -1)"; else echo "FAIL $name: $out"; fail=1; fi
}
check clock    sh -c 'test "$(date +%Y)" -ge 2025 && date -u'
check node     node -v
check npm      npm -v
check python   python3 -V
check git      git --version
check chromium chromium --version
check headless sh -c 'chromium --headless=new --no-sandbox --disable-gpu --dump-dom "data:text/html,<p id=x>ok</p>" 2>/dev/null | grep -o "<p id=\"x\">ok</p>"'
check anatomy  test -f /opt/anatomy/SKILL.md
check capture  sh -c 'cd /opt/anatomy/examples/dial-indicator && node build.mjs >/dev/null && rm -f /tmp/vl-shot.png && node ../../scripts/capture.mjs dial-indicator.html --out /tmp/vl-shot.png >/dev/null 2>&1 && test -s /tmp/vl-shot.png && echo "screenshot $(wc -c < /tmp/vl-shot.png) bytes"'
check agent    /opt/vlagent/bin/python -c 'import anthropic; print("anthropic", anthropic.__version__)'
exit $fail
