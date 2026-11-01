#!/bin/sh
# Runs inside the Debian guest. Idempotent: safe to run again after a failure.
set -eu

NODE_VERSION=v22.23.4
case "$(dpkg --print-architecture)" in
    armhf) NODE_ARCH=armv7l; NODE_SHA256=c9057c6a3b8fbb9651ed115c1e96d4a7281b7b9c9ca89fa05865c98ed7ffd437 ;;
    arm64) NODE_ARCH=arm64;  NODE_SHA256=6287aa46b6c375d816e63ae66d024fbbfd70404825aabdfdf898a970871d2f4b ;;
    *) echo "unsupported architecture"; exit 1 ;;
esac
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
apt-get clean

if [ "$(/opt/node/bin/node -v 2>/dev/null || true)" != "$NODE_VERSION" ]; then
    step "Node.js $NODE_VERSION"
    tarball="node-$NODE_VERSION-linux-$NODE_ARCH.tar.xz"
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

step "Figure agent (Python)"
if [ ! -x /opt/vlagent/bin/python ]; then
    python3 -m venv /opt/vlagent
fi
# Debian's pip 23 only accepts linux_armv8l wheels under a 64-bit kernel; newer pip
# also accepts armv7l. piwheels carries armv7l wheels for pydantic-core and jiter.
/opt/vlagent/bin/pip install -q --disable-pip-version-check -U pip
/opt/vlagent/bin/pip install -q --disable-pip-version-check --prefer-binary \
    --extra-index-url https://www.piwheels.org/simple -r /opt/visuallearn/requirements.txt
/opt/vlagent/bin/python -c 'import anthropic; print("anthropic", anthropic.__version__)'

touch /opt/visuallearn/.tools-ready
step "Done"
