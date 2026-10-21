---
name: run-s3-viewer
description: Simple S3 Viewer 를 빌드·실행하고 웹페이지가 잘 보이는지 확인하는 방법. 뷰어를 실행/시작/띄우기, 특정 S3 endpoint(예: localhost:9000)에 붙여 보기, 통합 테스트(docker/test.sh) 실행, 헤드리스 Chrome 으로 UI 스크린샷·동작 확인을 요청받았을 때 사용.
---

# S3 Viewer 실행·확인

## 이 환경에 대한 사실

- 로컬에 `java`, `mvn` 이 **없다**. 빌드·실행은 항상 Docker 로 한다.
  - 빌드 이미지 `maven:3.9-eclipse-temurin-8`, 실행 이미지 `eclipse-temurin:8-jre` (`Dockerfile` 이 둘 다 사용)
- Docker Hub 의 `minio/minio`, `quay.io/minio/*` 는 받을 수 **없다**. S3 서버가 필요하면 `chrislusf/seaweedfs`, `rustfs/rustfs` 를 쓴다 (`docker/compose.yml`).
- Docker 는 WSL2 의 네이티브 엔진이라 `--network host` 가 동작한다. 호스트의 `localhost:포트` 에 떠 있는 S3 에 붙을 때 사용한다.
- 헤드리스 브라우저: `zenika/alpine-chrome:with-puppeteer` (puppeteer 내장, chromium 경로 `/usr/bin/chromium-browser`).
- 스크린샷·임시 파일은 세션 scratchpad 에 둔다. puppeteer 컨테이너에는 `-v <scratchpad>/shots:/out` 로 마운트한다 (디렉터리 권한 `chmod 777` 필요).

## A. 이미 떠 있는 S3 endpoint 에 붙여 실행

기본값은 `http://localhost:9000`, 키 `local`/`local`, 뷰어 포트 `18090`.

```bash
# 먼저 S3 서버가 살아 있는지 확인 (403 AccessDenied XML 이 오면 정상: 서명 없는 요청이라서)
curl -s -m 5 -i http://localhost:9000/ | head -5

S3_BUCKETS=demo .claude/skills/run-s3-viewer/scripts/run-local.sh
# 다른 endpoint: S3_ENDPOINT=... S3_ACCESS_KEY=... S3_SECRET_KEY=... PORT=... 로 덮어쓰기
```

스크립트는 현재 소스로 `s3-viewer:latest` 이미지를 빌드하고, `s3-viewer-local` 컨테이너를 host 네트워크로 띄운 뒤 `/api/buckets` 와 (`S3_BUCKETS` 가 있으면) 첫 버킷의 `/api/list` 결과를 출력한다. 중지: `docker rm -f s3-viewer-local`.

### 버킷 이름 정하기

- 먼저 `S3_BUCKETS` 없이 실행해 본다. `/api/buckets` 가 목록을 주면 그대로 사용한다.
- `InvalidArgument` (예: `Use /bucket?list-type=2 or /bucket/key`), `NotImplemented`, `AccessDenied` 이면 서버가 ListBuckets 를 지원하지 않는 것이다. 버킷 이름을 알아내 `S3_BUCKETS` 로 지정한다.
  - 로컬 컨테이너라면 `docker inspect <컨테이너> --format '{{range .Mounts}}{{.Source}}->{{.Destination}} {{end}}'` 로 데이터 디렉터리를 찾고, 그 최상위 디렉터리 이름을 버킷으로 본다.
  - 예: `localhost:9000` 의 `local-s3` 컨테이너(이미지 `local-s3-java8`, 데이터 `/home/trsprs/workspace/codex/test1/data`)는 ListBuckets 미지원, 버킷 `demo`, 객체 `docs/hello.txt`.
- **주의**: `S3_BUCKETS` 를 지정하면 `/api/buckets` 는 S3 를 호출하지 않는다. 이 응답이 성공해도 연결이 된 것은 아니므로 반드시 `/api/list` 결과로 확인한다.
- 그 S3 서버 컨테이너는 다른 작업의 것이므로 꺼져 있어도 임의로 다시 띄우거나 지우지 말고 사용자에게 알린다.

## B. UI 확인 (헤드리스 Chrome)

```bash
S=<scratchpad>; mkdir -p $S/shots && chmod 777 $S/shots
docker run --rm --network host \
  -e URL=http://localhost:18090/ -e BUCKET=demo -e KEY=docs/hello.txt \
  -v $S/shots:/out \
  -v $PWD/.claude/skills/run-s3-viewer/scripts/ui-check.js:/usr/src/app/ui-check.js:ro \
  zenika/alpine-chrome:with-puppeteer node ui-check.js
# Basic 인증이 있으면 -e AUTH=user:pass
```

출력의 `breadcrumb`, `summary`, `message`(오류 배너), `detail`, `preview`, `console errors` 를 확인하고 `$S/shots/list.png`, `$S/shots/detail.png` 를 Read 로 열어 본다.
- `console errors: []` 이어야 한다. CSP 위반(`Refused to ...`)이 보이면 인라인 스타일·스크립트를 쓴 것이다 (UI 는 `default-src 'self'`).
- `FAIL: 목록에서 ... 찾지 못함` 이면 `message` 줄에 S3 오류가 있다 (예: `ConnectException` = S3 서버가 꺼짐).

## C. 통합 테스트 (SeaweedFS + RustFS)

```bash
docker compose -f docker/compose.yml up -d --build
docker/seed.sh          # 최초 1회 (두 서버에 테스트 데이터 업로드)
docker/test.sh          # 끝에 ALL PASSED 가 나와야 함
```

| 포트 | 대상 | 비고 |
|---|---|---|
| 18080 | SeaweedFS 뷰어 | 인증 없음 |
| 18081 | RustFS 뷰어 | Basic `admin`/`admin` |
| 18082 | SeaweedFS 뷰어 | `S3_BUCKETS=test-bucket` 허용 목록 |
| 18333 / 19000 | SeaweedFS / RustFS S3 | 키 `testkey`/`testsecret+/key`, `rustkey`/`rustsecret` |

소스를 바꾼 뒤에는 `up -d --build` 로 뷰어 이미지를 다시 빌드해야 반영된다. 정리: `docker compose -f docker/compose.yml down`.

서명 구현을 바꿨다면 잘못된 비밀키로 `SignatureDoesNotMatch` 가 나오는지도 확인한다 (테스트 통과가 서명 미검증 때문이 아님을 보장).

```bash
docker run -d --rm --name sv-bad --network docker_default -p 18089:8080 \
  -e S3_ENDPOINT=http://seaweedfs:8333 -e S3_ACCESS_KEY=testkey -e S3_SECRET_KEY=wrong s3-viewer:latest
sleep 2; curl -s 'http://localhost:18089/api/list?bucket=test-bucket'; docker rm -f sv-bad
```

## D. jar 만 빌드

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-8 mvn -q -B package
# → target/s3-viewer.jar (root 소유로 생성됨. 필요 없으면 docker 로 같이 지운다: ... sh -c 'mvn -q package && ... && rm -rf target')
```

## 결과 보고

사용자에게는 실행 URL, 확인한 항목(목록/상세/미리보기/다운로드/Range), 콘솔 오류 여부, 띄워 둔 컨테이너와 중지 명령을 알린다. 스크린샷이 있으면 scratchpad 의 전체 경로를 적는다.
