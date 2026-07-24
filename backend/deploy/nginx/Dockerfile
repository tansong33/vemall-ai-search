FROM node:20-alpine AS frontend-build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:alpine
COPY backend/deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY backend/deploy/nginx/index.html /usr/share/nginx/html/index.html
COPY --from=frontend-build /workspace/dist/ /usr/share/nginx/html/search/
EXPOSE 80
