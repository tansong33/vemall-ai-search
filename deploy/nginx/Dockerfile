FROM nginx:alpine
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
# 覆盖基础镜像自带的欢迎页，否则根路径会露出 "Welcome to nginx!"。
COPY deploy/nginx/index.html /usr/share/nginx/html/index.html
EXPOSE 80
