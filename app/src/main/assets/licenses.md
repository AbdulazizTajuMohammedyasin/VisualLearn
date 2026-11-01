# Open-source software

VisualLearn is built on the work of these projects. Thank you.

## Shipped in the app

**PRoot 5.1.107.101** (Termux build): GNU General Public License v2.0.
Shipped unmodified except that its library reference and run path were rewritten with patchelf so Android can load it as an app library. Source: https://github.com/termux/proot and the Termux package recipe at https://github.com/termux/termux-packages/tree/master/packages/proot

**talloc 2.5.0**: GNU Lesser General Public License v3.0 or later. Its soname was rewritten with patchelf. Source: https://www.samba.org/ftp/talloc/

**libandroid-shmem 0.7**: BSD 3-Clause. Copyright (c) 2013 Sergii Pylypenko, (c) 2017 Fredrik Fornwall. Source: https://github.com/termux/libandroid-shmem

**Anthropic Java SDK**: MIT License. https://github.com/anthropics/anthropic-sdk-java

**AndroidX, Jetpack Compose and Material 3**: Apache License 2.0.

**Kotlin standard library and coroutines**: Apache License 2.0.

**OkHttp and Okio**: Apache License 2.0.

**Jackson**: Apache License 2.0.

**Coil**: Apache License 2.0.

**Apache Commons Compress**: Apache License 2.0.

**XZ for Java**: 0BSD / public domain.

## Downloaded on first setup

These are fetched from their official sources into the app's private Linux environment; each keeps its own licence.

**Debian 12 "bookworm"** root filesystem (via termux/proot-distro) and packages including Chromium, Python 3.11 and git: each package's licence is in /usr/share/doc/<package>/copyright inside the environment.

**Node.js 22**: MIT License and bundled third-party licences. https://nodejs.org

**Anatomy** by Ryan (wheresryan22): MIT License. https://github.com/wheresryan22/anatomy

**Anthropic Python SDK** and its dependencies (pydantic, httpx2, jiter and others): MIT, Apache 2.0 and BSD licences, from PyPI and piwheels.org.

## Gallery figures

Figures in Explore come from the Anatomy community gallery (https://github.com/wheresryan22/anatomy-community). Each is MIT or CC-BY-4.0 licensed and shown with its author's name; see a figure's info panel for its licence and source.

## Claude

Chat and figures are generated with Claude by Anthropic, through your own API key and subject to Anthropic's terms.
