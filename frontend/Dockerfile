FROM node:20-alpine AS build

WORKDIR /workspace
COPY package*.json ./
RUN npm ci
COPY . ./
RUN npm run build

FROM nginx:alpine

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/search/

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -qO- http://127.0.0.1/health | grep -q '"status":"ok"'
