#   docker build -t exchange/web .   (run from web/, or docker compose --profile full build)

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

# NEXT_PUBLIC_* values are inlined into the client bundle at BUILD time, so they have to be
# build args -- passing them as runtime environment would do nothing at all. lib/env.ts throws
# when the URL is missing, so forgetting this fails the build here rather than shipping an image
# that cannot reach the backend.
#
# The value is what the BROWSER must use. Inside compose every other URL is a service name, but
# the browser runs outside that network, so this one is http://localhost:8081 and not
# http://web-bff:8081.
ARG NEXT_PUBLIC_BFF_URL
ARG NEXT_PUBLIC_EXCHANGE_NAME
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=
ENV NEXT_PUBLIC_BFF_URL=$NEXT_PUBLIC_BFF_URL \
    NEXT_PUBLIC_EXCHANGE_NAME=$NEXT_PUBLIC_EXCHANGE_NAME \
    NEXT_PUBLIC_GOOGLE_CLIENT_ID=$NEXT_PUBLIC_GOOGLE_CLIENT_ID
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
# output: "standalone" in next.config.ts traces each page's real dependencies and copies
# only those into .next/standalone -- this image never runs `npm install`, so it carries
# none of node_modules beyond what's actually reachable at runtime.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public

EXPOSE 3000
CMD ["node", "server.js"]
