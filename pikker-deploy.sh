#!/bin/bash
set -e

SERVER=root@pikker.dev

ssh -t $SERVER << 'END'
  set -e
  cd efti-gate-ee
  GIT_TERMINAL_PROMPT=0 git pull
  docker compose -f compose.yml -f compose.pikker.yml up --build -d --wait
  docker compose logs --tail=50
END