# VisualLearn server with everything a figure job needs: Node 24, Chromium,
# OpenCode and the Anatomy skill pinned to a commit, plus the LearnHarness
# build that runs each learner's lessons.
# Build from the repository root (the OpenUI prompt comes from ui-library/):
#   docker build -f server/Dockerfile -t visuallearn-server .

# LearnHarness (harness/, AGPL-3.0) as a standalone Next.js server; the
# Electron shell isn't used, so its binary isn't downloaded.
FROM node:24-bookworm-slim AS harness
WORKDIR /harness
COPY harness/package.json harness/package-lock.json ./
RUN ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm ci
COPY harness ./
RUN npx next build && node scripts/build-server.mjs

FROM node:24-bookworm-slim

ARG OPENCODE_VERSION=1.19.5
ARG ANATOMY_COMMIT=1482e8469b0a9e0d8cc18ea2740a01423d29ae80

RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium git ca-certificates fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g "opencode-ai@${OPENCODE_VERSION}"

RUN git clone https://github.com/wheresryan22/anatomy /opt/anatomy \
 && git -C /opt/anatomy checkout "${ANATOMY_COMMIT}" \
 && rm -rf /opt/anatomy/.git

WORKDIR /app
COPY ui-library/package.json ui-library/package-lock.json ui-library/
RUN cd ui-library && npm ci
COPY ui-library ui-library
RUN cd ui-library && npm run prompt

COPY server/package.json server/package-lock.json server/
RUN cd server && npm ci --omit=dev
COPY server server
COPY --from=harness /harness/.next/standalone harness/.next/standalone

ENV NODE_ENV=production \
    PORT=8080 \
    VL_DATA_DIR=/data \
    VL_ANATOMY_DIR=/opt/anatomy \
    VL_UI_PROMPT=/app/ui-library/dist/system-prompt.md \
    VL_HARNESS_DIR=/app/harness \
    CHROME_PATH=/usr/bin/chromium \
    VL_CHROME_NO_SANDBOX=1 \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

RUN mkdir -p /data && chown node:node /data
USER node
WORKDIR /app/server
VOLUME ["/data"]
EXPOSE 8080
CMD ["node", "--disable-warning=ExperimentalWarning", "src/main.ts"]
