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
exit $fail
