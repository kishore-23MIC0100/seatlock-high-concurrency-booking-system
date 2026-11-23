FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
RUN npm ci --no-fund
COPY apps/web apps/web
RUN npm run build

FROM nginx:1.30-alpine
ENV SEATLOCK_UPSTREAM=api:8181 SEATLOCK_TRUSTED_PROXY_CIDR=127.0.0.1/32
COPY infra/docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/apps/web/dist /usr/share/nginx/html
EXPOSE 80
