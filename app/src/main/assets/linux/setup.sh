#!/bin/sh
# Runs inside the Debian guest. Idempotent: safe to run again after a failure.
set -eu

NODE_VERSION=v22.23.4
NODE_SHA256=c9057c6a3b8fbb9651ed115c1e96d4a7281b7b9c9ca89fa05865c98ed7ffd437
ANATOMY_REPO=https://github.com/wheresryan22/anatomy.git
ANATOMY_COMMIT=1482e8469b0a9e0d8cc18ea2740a01423d29ae80

step() { echo "==> $*"; }

step "Installing vDSO fix hook"
cat > /usr/local/sbin/vl-fix-vdso <<'EOF'
#!/usr/bin/perl
# Re-applies the clock_gettime64 vDSO rename after libc6 upgrades (see LinuxEnv.patchVdso).
my $f = '/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3';
open my $in, '<:raw', $f or exit 0;
local $/; my $d = <$in>; close $in;
exit 0 unless $d =~ s/__vdso_clock_gettime64\0/__vdso_clock_gettimeNO\0/;
open my $out, '>:raw', "$f.new" or die; print $out $d; close $out;
chmod 0755, "$f.new"; rename "$f.new", $f or die;
EOF
chmod 755 /usr/local/sbin/vl-fix-vdso
echo 'DPkg::Post-Invoke { "/usr/local/sbin/vl-fix-vdso"; };' > /etc/apt/apt.conf.d/99-vl-fix-vdso

step "apt packages"
apt-get update -q
apt-get install -y -q --no-install-recommends \
    python3 python3-venv python3-pip chromium git ca-certificates curl xz-utils perl fonts-dejavu-core \
    libatomic1

if [ "$(/opt/node/bin/node -v 2>/dev/null || true)" != "$NODE_VERSION" ]; then
    step "Node.js $NODE_VERSION"
    tarball="node-$NODE_VERSION-linux-armv7l.tar.xz"
    curl -fsSL -o "/tmp/$tarball" "https://nodejs.org/dist/$NODE_VERSION/$tarball"
    echo "$NODE_SHA256  /tmp/$tarball" | sha256sum -c -
    rm -rf /opt/node && mkdir -p /opt/node
    tar -xJf "/tmp/$tarball" -C /opt/node --strip-components=1
    rm "/tmp/$tarball"
fi

if [ "$(git -C /opt/anatomy rev-parse HEAD 2>/dev/null || true)" != "$ANATOMY_COMMIT" ]; then
    step "Anatomy skill @ ${ANATOMY_COMMIT%"${ANATOMY_COMMIT#???????}"}"
    rm -rf /opt/anatomy && mkdir -p /opt/anatomy
    git -C /opt/anatomy init -q
    git -C /opt/anatomy fetch -q --depth 1 "$ANATOMY_REPO" "$ANATOMY_COMMIT"
    git -C /opt/anatomy checkout -q FETCH_HEAD
fi

step "Done"
