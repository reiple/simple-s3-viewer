#!/usr/bin/env bash
# aws-cli 컨테이너로 테스트 데이터를 넣는다 (compose 네트워크 사용).
set -euo pipefail
cd "$(dirname "$0")"
NET="$(docker compose -f compose.yml ps -q seaweedfs | xargs docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/files/many" "$WORK/files/deep/a/b/c"
echo '{"hello":"world","n":[1,2,3]}' > "$WORK/files/data.json"
printf 'line1\nline2\n한글 텍스트\n' > "$WORK/files/한글 파일 (1).txt"
echo plus > "$WORK/files/a+b=c&d.txt"
echo pct > "$WORK/files/100% done#?.log"
echo deep > "$WORK/files/deep/a/b/c/leaf.txt"
echo '<script>alert(1)</script>' > "$WORK/files/evil.html"
: > "$WORK/files/empty.txt"
head -c 3000000 /dev/urandom > "$WORK/files/random.bin"
# 1x1 PNG
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xcf\xc0\xf0\x1f\x00\x05\x00\x01\xff\x89\x99=\x1d\x00\x00\x00\x00IEND\xaeB`\x82' > "$WORK/files/pixel.png"
for i in $(seq -w 1 1205); do echo "$i" > "$WORK/files/many/item-$i.txt"; done

seed() { # endpoint accessKey secretKey
  docker run --rm --network "$NET" -v "$WORK/files:/files:ro" \
    -e AWS_ACCESS_KEY_ID="$2" -e AWS_SECRET_ACCESS_KEY="$3" -e AWS_DEFAULT_REGION=us-east-1 \
    --entrypoint sh amazon/aws-cli -c "
      set -e
      aws --endpoint-url $1 s3 mb s3://test-bucket || true
      aws --endpoint-url $1 s3 mb s3://second.bucket || true
      aws --endpoint-url $1 s3 cp --recursive --quiet /files s3://test-bucket/
      aws --endpoint-url $1 s3 cp --quiet --metadata owner=tester,purpose=demo /files/data.json 's3://test-bucket/with meta/data.json'
      aws --endpoint-url $1 s3 cp --quiet /files/data.json s3://second.bucket/only.json
      aws --endpoint-url $1 s3 ls s3://test-bucket/
    "
}
seed http://seaweedfs:8333 testkey 'testsecret+/key'
seed http://rustfs:9000 rustkey rustsecret
