#!/bin/sh
# Builds Anatomy's smallest example on the device and copies both themes to /root/figures.
set -eu
cd /opt/anatomy/examples/dial-indicator
start=$(date +%s)
node build.mjs
node build.mjs --light
mkdir -p /root/figures
cp dial-indicator.html dial-indicator-light.html /root/figures/
echo "Built in $(( $(date +%s) - start ))s"
ls -la /root/figures
