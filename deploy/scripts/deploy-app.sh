#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE_TAG=""
REGISTRY_OWNER=""
ENV_FILE=""
HEALTH_BASE_URL="http://127.0.0.1:18080"
PROJECT_NAME="ai-search-prod"
DEPLOYMENT_ROOT=""
GIT_REF=""
GIT_COMMIT=""
HEALTH_TIMEOUT_SECONDS=240
ROLLBACK=0

while (($#)); do
  case "$1" in
    --image-tag) IMAGE_TAG="$2"; shift 2 ;;
    --registry-owner) REGISTRY_OWNER="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --health-base-url) HEALTH_BASE_URL="$2"; shift 2 ;;
    --project-name) PROJECT_NAME="$2"; shift 2 ;;
    --deployment-root) DEPLOYMENT_ROOT="$2"; shift 2 ;;
    --git-ref) GIT_REF="$2"; shift 2 ;;
    --git-commit) GIT_COMMIT="$2"; shift 2 ;;
    --health-timeout-seconds) HEALTH_TIMEOUT_SECONDS="$2"; shift 2 ;;
    --rollback) ROLLBACK=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if ((!ROLLBACK)); then
  if [[ ! "$IMAGE_TAG" =~ ^sha-[0-9a-f]{40}$ ]]; then
    echo "--image-tag must use sha- followed by the full 40-character commit SHA." >&2
    exit 2
  fi
  if [[ ! "$REGISTRY_OWNER" =~ ^[a-zA-Z0-9-]+$ ]]; then
    echo "--registry-owner is missing or invalid." >&2
    exit 2
  fi
fi
if [[ -z "$ENV_FILE" || ! -f "$ENV_FILE" ]]; then
  echo "Deployment environment file does not exist: $ENV_FILE" >&2
  exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
if [[ -n "$DEPLOYMENT_ROOT" ]]; then
  PROJECT_ROOT="$(readlink -f "$DEPLOYMENT_ROOT")"
else
  PROJECT_ROOT="$SOURCE_PROJECT_ROOT"
fi
COMPOSE_FILE="$PROJECT_ROOT/compose.apps.yml"
ENV_FILE="$(readlink -f "$ENV_FILE")"
BACKUP_FILE="${ENV_FILE}.previous"
FAILED_FILE="${ENV_FILE}.failed"
LOCK_FILE="${ENV_FILE}.deploy.lock"
HEALTH_BASE_URL="${HEALTH_BASE_URL%/}"

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "Another application deployment is already running." >&2
  exit 1
fi

compose() {
  docker compose \
    -p "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

sync_deployment_repository() {
  if [[ -z "$GIT_REF" && -z "$GIT_COMMIT" ]]; then
    return 0
  fi
  if [[ ! "$GIT_REF" =~ ^(dev|main)$ || ! "$GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
    echo "--git-ref (dev/main) and a full --git-commit must be provided together." >&2
    return 1
  fi
  if [[ "$IMAGE_TAG" != "sha-$GIT_COMMIT" ]]; then
    echo "--image-tag must match --git-commit." >&2
    return 1
  fi
  if [[ ! -d "$PROJECT_ROOT/.git" ]]; then
    echo "Deployment root is not a Git repository: $PROJECT_ROOT" >&2
    return 1
  fi
  if [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]]; then
    echo "Deployment repository contains local changes: $PROJECT_ROOT" >&2
    return 1
  fi

  git -C "$PROJECT_ROOT" fetch --prune origin \
    "+refs/heads/$GIT_REF:refs/remotes/origin/$GIT_REF"
  if git -C "$PROJECT_ROOT" show-ref --verify --quiet "refs/heads/$GIT_REF"; then
    git -C "$PROJECT_ROOT" checkout "$GIT_REF"
  else
    git -C "$PROJECT_ROOT" checkout -b "$GIT_REF" --track "origin/$GIT_REF"
  fi
  git -C "$PROJECT_ROOT" merge --ff-only "origin/$GIT_REF"

  local actual_commit
  actual_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
  if [[ "$actual_commit" != "$GIT_COMMIT" ]]; then
    echo "Deployment repository is at $actual_commit, expected $GIT_COMMIT." >&2
    return 1
  fi
  echo "Deployment repository synchronized to $GIT_REF at $GIT_COMMIT"
}

set_dotenv_value() {
  local name="$1"
  local value="$2"
  local temporary
  temporary="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
  awk -v key="$name" -v replacement="$name=$value" '
    BEGIN { found = 0 }
    $0 ~ "^[[:space:]]*" key "=" {
      if (!found) {
        print replacement
        found = 1
      }
      next
    }
    { print }
    END {
      if (!found) print replacement
    }
  ' "$ENV_FILE" >"$temporary"
  chmod --reference="$ENV_FILE" "$temporary"
  mv -f -- "$temporary" "$ENV_FILE"
}

set_application_images() {
  local tag="$1"
  local owner="${REGISTRY_OWNER,,}"
  local prefix="ghcr.io/${owner}/vemall-ai-search"
  set_dotenv_value AI_SEARCH_REST_IMAGE "${prefix}-rest:${tag}"
  set_dotenv_value FRONTEND_IMAGE "${prefix}-frontend:${tag}"
  set_dotenv_value GATEWAY_IMAGE "${prefix}-gateway:${tag}"
}

wait_application_health() {
  local started=$SECONDS
  local gateway application
  while ((SECONDS - started < HEALTH_TIMEOUT_SECONDS)); do
    gateway="$(curl -fsS --max-time 10 "$HEALTH_BASE_URL/health" 2>/dev/null || true)"
    application="$(curl -fsS --max-time 15 "$HEALTH_BASE_URL/actuator/health" 2>/dev/null || true)"
    if grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' <<<"$gateway" &&
       grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$application"; then
      echo "Application is healthy: $HEALTH_BASE_URL"
      return 0
    fi
    echo "Waiting for application health..."
    sleep 5
  done
  echo "Application did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s." >&2
  return 1
}

restore_previous_deployment() {
  if [[ ! -f "$BACKUP_FILE" ]]; then
    echo "Rollback file does not exist: $BACKUP_FILE" >&2
    return 1
  fi
  cp -f -- "$ENV_FILE" "$FAILED_FILE"
  cp -f -- "$BACKUP_FILE" "$ENV_FILE"
  compose config --quiet
  compose pull
  compose up -d --no-build --remove-orphans
  wait_application_health
  echo "Rollback completed from $BACKUP_FILE"
}

if ((ROLLBACK)); then
  restore_previous_deployment
  exit 0
fi

sync_deployment_repository
if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file does not exist after repository synchronization: $COMPOSE_FILE" >&2
  exit 1
fi

cp -f -- "$ENV_FILE" "$BACKUP_FILE"
set_application_images "$IMAGE_TAG"

if compose config --quiet &&
   compose pull &&
   compose up -d --no-build --remove-orphans &&
   wait_application_health; then
  echo "Deployment completed: $IMAGE_TAG"
  exit 0
fi

echo "Deployment failed; restoring previous image references." >&2
if restore_previous_deployment; then
  echo "Deployment failed and was rolled back." >&2
  exit 1
fi

echo "Deployment and automatic rollback both failed. Manual recovery is required." >&2
exit 1
