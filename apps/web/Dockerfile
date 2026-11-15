# syntax=docker/dockerfile:1
# ArchSim web image (Next.js standalone output). A release publishes it for amd64 and arm64 as
# ghcr.io/dante0747/archsim-web:<version> (.github/workflows/release.yml, ADR-0077), which
# docker-compose.yml runs; docker-compose.local.yml builds it from this checkout as archsim-web:local.
# Build it from the repository root:
#   docker build -f apps/web/Dockerfile -t archsim-web:local .
FROM node:24-alpine AS deps
WORKDIR /app
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY apps/web/ ./
# next.config.ts leaves sharp and its libvips out of the standalone output; fail if they come back
RUN npm run build && node scripts/check-standalone.mjs

FROM node:24-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    ARCHSIM_API_URL=http://api:8000
RUN addgroup -S -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs
COPY --from=build --chown=nextjs:nodejs /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
