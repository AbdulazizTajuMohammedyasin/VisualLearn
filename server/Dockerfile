# VisualLearn server with everything a figure job needs: Node 24, Chromium,
# OpenCode and the Anatomy skill pinned to a commit.
# Build from the repository root (the OpenUI prompt comes from ui-library/):
#   docker build -f server/Dockerfile -t visuallearn-server .
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

ENV NODE_ENV=production \
    PORT=8080 \
    VL_DATA_DIR=/app/server/data \
    VL_ANATOMY_DIR=/opt/anatomy \
    VL_UI_PROMPT=/app/ui-library/dist/system-prompt.md \
    CHROME_PATH=/usr/bin/chromium \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

# Data stays below /app/server: figure jobs' OpenCode tools resolve @opencode-ai/plugin
# by walking up from their folder to /app/server/node_modules.
RUN mkdir -p /app/server/data && chown node:node /app/server/data
USER node
WORKDIR /app/server
VOLUME ["/app/server/data"]
EXPOSE 8080
CMD ["node", "--disable-warning=ExperimentalWarning", "src/main.ts"]
