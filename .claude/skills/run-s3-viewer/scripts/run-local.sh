#!/usr/bin/env bash
# 현재 소스로 뷰어 이미지를 빌드하고, 이미 떠 있는 S3 endpoint 에 host 네트워크로 붙여 실행한다.
# 사용: run-local.sh   (환경 변수로 덮어쓰기: S3_ENDPOINT S3_ACCESS_KEY S3_SECRET_KEY S3_BUCKETS PORT)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
: "${S3_ENDPOINT:=http://localhost:9000}" "${S3_ACCESS_KEY:=local}" "${S3_SECRET_KEY:=local}" "${S3_BUCKETS:=}" "${PORT:=18090}"
NAME=s3-viewer-local

docker build -q -t s3-viewer:latest "$ROOT" >/dev/null
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --network host \
  -e S3_ENDPOINT="$S3_ENDPOINT" -e S3_ACCESS_KEY="$S3_ACCESS_KEY" -e S3_SECRET_KEY="$S3_SECRET_KEY" \
  -e S3_BUCKETS="$S3_BUCKETS" -e SERVER_PORT="$PORT" s3-viewer:latest >/dev/null

for _ in $(seq 1 20); do curl -s -o /dev/null "http://localhost:$PORT/" && break; sleep 0.5; done
docker logs "$NAME"
echo "--- /api/buckets"
curl -s "http://localhost:$PORT/api/buckets"; echo
if [[ -n "$S3_BUCKETS" ]]; then
  # S3_BUCKETS 가 있으면 /api/buckets 는 S3 를 호출하지 않으므로 실제 목록 조회로 연결을 확인한다
  FIRST="${S3_BUCKETS%%,*}"
  echo "--- /api/list?bucket=$FIRST"
  curl -s "http://localhost:$PORT/api/list?bucket=$FIRST"; echo
fi
echo "UI: http://localhost:$PORT/   (중지: docker rm -f $NAME)"
