#!/bin/bash
set -e

SERVER=root@pikker.dev

DOCKER_DEFAULT_PLATFORM=linux/amd64 docker compose -f compose.yml -f compose.pikker.yml build

BUILD_SERVICES=$(docker compose -f compose.yml -f compose.pikker.yml config --format json | jq -r '.services | to_entries[] | select(.value | has("build")) | .key')
IMAGES=$(docker compose -f compose.yml -f compose.pikker.yml images -q $BUILD_SERVICES)

echo "Saving images..."
docker save $IMAGES | gzip | ssh $SERVER 'gunzip | docker load'

scp compose.yml compose.pikker.yml $SERVER:efti-gate-ee/

ssh -t $SERVER << 'END'
  set -e
  cd efti-gate-ee
  mkdir -p logs
  docker compose -f compose.yml -f compose.pikker.yml logs -t > logs/deploy-$(date --iso-8601=minutes).log 2>&1 || true
  docker compose -f compose.yml -f compose.pikker.yml up -d --wait
  docker compose -f compose.yml -f compose.pikker.yml logs -f
END
