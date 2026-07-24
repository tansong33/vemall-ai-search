# File service

Lightweight upload/download service used behind the project Nginx gateway.

- `GET /` provides the browser UI.
- `GET /api/files` lists files.
- `POST /api/files` uploads one or more multipart fields named `files`.
- `GET /api/files/{name}` downloads a file.
- `GET /health` is the container health endpoint.

The service deliberately has no delete endpoint and accepts only flat filenames. Nginx applies
the shared administrator Basic Auth policy at `/files/`.
