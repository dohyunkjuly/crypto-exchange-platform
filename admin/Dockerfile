FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

# Inlined into the client bundle at BUILD time, so a build arg, and the URL the BROWSER uses:
# http://localhost:8083, not http://admin-bff:8083. See web/Dockerfile.
ARG NEXT_PUBLIC_ADMIN_BFF_URL
ENV NEXT_PUBLIC_ADMIN_BFF_URL=$NEXT_PUBLIC_ADMIN_BFF_URL
ARG NEXT_PUBLIC_EXCHANGE_NAME
ENV NEXT_PUBLIC_EXCHANGE_NAME=$NEXT_PUBLIC_EXCHANGE_NAME
RUN npm run build
# The app has no public/ of its own yet, and git keeps no empty directory: without this the
# COPY below fails on any fresh checkout, which is every CI runner.
RUN mkdir -p public

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production PORT=3001
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public

EXPOSE 3001
CMD ["node", "server.js"]
