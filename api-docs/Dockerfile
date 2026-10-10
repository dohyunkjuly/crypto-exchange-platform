# The documentation site as an image: build the markdown once, serve the files with nginx.
#   docker build -t exchange/api-docs .   (run from api-docs/, or docker compose --profile full build)
FROM node:22-alpine AS build
WORKDIR /site
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY build.mjs ./
COPY content ./content
COPY site ./site
# The operator's name, in the page titles; the compose stack passes the local placeholder.
ARG EXCHANGE_NAME
RUN EXCHANGE_NAME="$EXCHANGE_NAME" node build.mjs

FROM nginx:1.27-alpine
COPY --from=build /site/build /usr/share/nginx/html
