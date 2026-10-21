# 구조

## 개요

```
브라우저 ──(GET, 상대 경로)──▶ ViewerServer ──(SigV4 서명 GET/HEAD)──▶ S3 호환 스토리지
 index.html / app.js            JDK HttpServer        S3Client + SigV4
```

- 브라우저는 S3 와 직접 통신하지 않는다. 자격 증명은 서버에만 있다.
- 객체 본문은 메모리에 올리지 않고 64KB 버퍼로 스트리밍 중계한다.
- 런타임 의존성 없음: `com.sun.net.httpserver`, `java.net.HttpURLConnection`, `javax.xml.parsers`, `javax.crypto` 만 사용한다.

## 소스 구성

| 파일 | 역할 |
|---|---|
| `Main.java` | 진입점. 설정 로드 후 서버 시작 |
| `Config.java` | 설정 로드. 우선순위 `-D` > 환경 변수 > 설정 파일. `s3.accessKey` → `S3_ACCESS_KEY` 로 이름 변환 |
| `SigV4.java` | AWS Signature V4 (헤더 방식, 본문 없는 요청 전용), RFC 3986 URI 인코딩, 정규화 쿼리 |
| `S3Client.java` | ListBuckets, ListObjectsV2, HeadObject, GetObject. XML 파싱, 오류 변환 |
| `S3Exception.java` | S3 오류 응답 (HTTP 상태 + S3 오류 코드) |
| `ViewerServer.java` | 라우팅, 인증, JSON 응답, 객체 스트리밍, 정적 파일 |
| `Json.java` | JSON 문자열 이스케이프 |
| `web/index.html`, `app.js`, `style.css` | UI (vanilla JS, 외부 라이브러리 없음) |

## S3 요청 처리

1. **URL 구성** — path-style: `/{bucket}/{key}`, virtual-hosted: `{bucket}.{host}/{key}`. 키는 `/` 를 제외하고 RFC 3986 방식으로 인코딩한다. URL 과 서명에 **같은 인코딩 문자열**을 사용해 이중 인코딩 문제를 피한다.
2. **Host 헤더** — `HttpURLConnection` 은 기본 포트(80/443)면 Host 에 포트를 붙이지 않으므로, 서명할 때도 같은 규칙을 적용한다.
3. **서명** — 서명 대상 헤더는 `host`, `x-amz-content-sha256`(빈 본문 해시), `x-amz-date`, (`x-amz-security-token`). `Range` 는 서명하지 않고 추가한다.
4. **응답** — 2xx 가 아니면 오류 XML 의 `Code`/`Message` 를 읽어 `S3Exception` 으로 바꾼다. 리다이렉트는 따라가지 않는다 (서명이 깨지므로).
5. **XML** — DOCTYPE 을 금지해 XXE 를 막는다.

자격 증명이 없으면 서명하지 않은 익명 요청을 보낸다 (공개 버킷용).

## HTTP API

모두 GET. 오류는 `{"error": 코드, "message": 메시지}`.

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/api/info` | | `endpoint`, `region`, `fixedBuckets` |
| `/api/buckets` | | `buckets[]{name, creationDate}`. `s3.buckets` 가 있으면 S3 호출 없이 그 목록 |
| `/api/list` | `bucket`, `prefix`, `token`, `delimiter`(기본 `/`, 빈 값이면 재귀), `max`(1~1000) | `prefixes[]`, `objects[]{key,size,lastModified,etag,storageClass}`, `truncated`, `nextToken` |
| `/api/head` | `bucket`, `key` | `size`, `contentType`, `etag`, `lastModified`, `headers{}`, `metadata{}`(x-amz-meta-*) |
| `/api/object` | `bucket`, `key`, `inline=1` | 객체 본문. `Range` 요청 헤더를 그대로 전달 (206 응답) |

S3 상태 코드 매핑: 403/404/416 은 그대로, 나머지 S3 오류는 502. 잘못된 파라미터는 400.

## 보안 설계

| 위협 | 대응 |
|---|---|
| 쓰기/삭제 | GET 외 메서드 405. S3 로도 GET/HEAD 만 보냄 |
| 저장된 HTML/SVG 로 XSS | `inline=1` 응답: 이미지·동영상·오디오·PDF 외 형식은 `text/plain` 으로 변경, `Content-Security-Policy: sandbox` (PDF 제외), `nosniff`. 다운로드는 `Content-Disposition: attachment` |
| UI 에 대한 XSS | DOM 은 `textContent` 로만 구성. UI 응답에 `default-src 'self'` CSP (인라인 스크립트/스타일 없음) |
| JSON 하이재킹/스니핑 | `<`, `>`, `&` 를 `\u` 이스케이프, `nosniff` |
| 클릭재킹 | `X-Frame-Options: SAMEORIGIN` |
| 인증 비교 타이밍 | `MessageDigest.isEqual` 로 상수 시간 비교 |
| 허용되지 않은 버킷 접근 | `s3.buckets` 지정 시 모든 API 에서 버킷 이름 검사 |
| XXE | DOCTYPE 금지 |

## UI

- 라우팅: `#b={bucket}&p={prefix}` 해시. 새로고침·북마크·뒤로 가기가 동작한다.
- 목록: 1000건 단위로 불러오고 "더 보기"/"모두 불러오기". 입력 중에는 불러온 항목을 클라이언트에서 필터, Enter 를 누르면 현재 폴더 기준 접두사로 서버 검색.
- 미리보기: Content-Type, 없으면 확장자로 판단. 텍스트는 `Range: bytes=0-262143` 로 앞 256KB 만 받는다. 업로드 시 `application/octet-stream` 으로 저장된 미디어는 서버가 확장자로 형식을 추정한다.
- 늦게 도착한 응답이 화면을 덮어쓰지 않도록 요청 순번(`seq`)을 비교한다.

## 테스트

`docker/` 아래 통합 테스트 환경 (SeaweedFS, RustFS) 과 `test.sh` API 테스트가 있다. 자세한 내용은 [README](../README.md#docker-로-테스트) 참고.
