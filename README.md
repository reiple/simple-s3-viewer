# Simple S3 Viewer

S3 호환 스토리지(AWS S3, Ceph RGW, MinIO, SeaweedFS, RustFS 등)를 웹 브라우저로 둘러보는 **읽기 전용** 뷰어.

- Java 8 이상, 런타임 의존성 없음 (JDK 내장 `HttpServer`, `HttpURLConnection`, DOM 파서만 사용)
- AWS Signature V4 직접 구현
- 폴더 탐색, 페이지네이션(1000개 단위 / 모두 불러오기), 접두사 검색, 정렬
- 메타데이터 보기, 다운로드, 미리보기 (이미지·동영상·오디오·PDF·텍스트, Range 요청 지원)
- 선택: HTTP Basic 인증, 버킷 허용 목록

문서: [운영 가이드](docs/OPERATIONS.md) (설치, systemd, nginx, 문제 해결) · [구조](docs/ARCHITECTURE.md) (설계, API, 보안)

## 빌드

```bash
mvn package            # → target/s3-viewer.jar (약 40KB)
```

Maven 이 없는 곳이라면 Docker 로 빌드할 수 있다.

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-8 mvn -q package
```

## 실행

```bash
cp s3-viewer.properties.example s3-viewer.properties   # 값 수정
java -jar s3-viewer.jar                                # 현재 디렉터리의 s3-viewer.properties 사용
java -jar s3-viewer.jar /etc/s3-viewer.properties      # 또는 경로 지정
```

환경 변수만으로도 실행할 수 있다.

```bash
S3_ENDPOINT=https://s3.example.com S3_ACCESS_KEY=... S3_SECRET_KEY=... java -jar s3-viewer.jar
```

## 설정

우선순위: `-D` 시스템 프로퍼티 > 환경 변수 > 설정 파일

| 키 | 환경 변수 | 기본값 | 설명 |
|---|---|---|---|
| `s3.endpoint` | `S3_ENDPOINT` | (필수) | 예: `https://s3.ap-northeast-2.amazonaws.com`, `http://ceph:7480` |
| `s3.region` | `S3_REGION` | `us-east-1` | 서명에 쓰는 리전 |
| `s3.accessKey` / `s3.secretKey` | `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 없음 | 비우면 익명(서명 없는) 요청 |
| `s3.sessionToken` | `S3_SESSION_TOKEN` | 없음 | STS 임시 자격 증명 |
| `s3.pathStyle` | `S3_PATH_STYLE` | `true` | `false` 면 `bucket.endpoint` 형태(virtual-hosted) |
| `s3.buckets` | `S3_BUCKETS` | 없음 | 쉼표 구분. 지정하면 이 버킷만 표시·허용 (ListBuckets 권한이 없을 때도 사용) |
| `s3.insecure` | `S3_INSECURE` | `false` | TLS 인증서 검증 끄기 |
| `s3.connectTimeoutMs` / `s3.readTimeoutMs` | | `10000` / `60000` | |
| `server.bind` / `server.port` | `SERVER_BIND` / `SERVER_PORT` | `0.0.0.0` / `8080` | |
| `server.threads` | `SERVER_THREADS` | `32` | 동시 처리 요청 수 (다운로드 포함) |
| `viewer.user` / `viewer.password` | `VIEWER_USER` / `VIEWER_PASSWORD` | 없음 | 둘 다 지정하면 Basic 인증 |

> **ListBuckets 를 지원하지 않는 서버**(버킷 목록에 `InvalidArgument`, `NotImplemented`, `AccessDenied` 오류)라면
> `s3.buckets` 에 버킷 이름을 직접 지정한다. 예: `S3_BUCKETS=demo`

> 사설 인증서는 `s3.insecure` 보다 JVM truststore 에 추가하는 편이 안전하다:
> `java -Djavax.net.ssl.trustStore=my.jks -Djavax.net.ssl.trustStorePassword=... -jar s3-viewer.jar`

## 보안 참고

- 서버는 GET 외의 메서드를 모두 405 로 거부하며, S3 로도 GET/HEAD 요청만 보낸다. 그래도 **S3 키 자체를 읽기 전용 권한으로 발급**하는 것을 권장한다.
- Basic 인증은 평문 전송이므로 외부에 노출할 때는 HTTPS 리버스 프록시(nginx 등) 뒤에 둘 것.
- 미리보기(`inline=1`)는 HTML 등 실행 가능한 형식을 `text/plain` 으로 바꾸고 `Content-Security-Policy: sandbox` 를 붙여 뷰어 origin 에서 스크립트가 돌지 않게 한다.
- UI 는 상대 경로만 사용하므로 리버스 프록시의 하위 경로(`/s3/` 등)에 붙여도 동작한다. ([nginx 예시](docs/OPERATIONS.md#5-nginx-리버스-프록시-하위-경로))

## HTTP API

`/api/buckets`, `/api/list`, `/api/head`, `/api/object` (모두 GET). 자세한 파라미터는 [구조 문서](docs/ARCHITECTURE.md#http-api) 참고.

## Docker 로 테스트

SeaweedFS, RustFS 두 S3 호환 서버에 대해 통합 테스트를 실행한다.

```bash
docker compose -f docker/compose.yml up -d --build
docker/seed.sh     # 테스트 데이터 업로드 (한글·특수문자 키, 1205개 파일, 메타데이터 등)
docker/test.sh     # API 테스트
# 브라우저: http://localhost:18080  (RustFS: http://localhost:18081, admin/admin)
docker compose -f docker/compose.yml down
```

이미 떠 있는 S3 서버(예: `localhost:9000`)에 붙여 보려면 host 네트워크로 실행한다.

```bash
docker build -t s3-viewer .
docker run --rm --network host -e S3_ENDPOINT=http://localhost:9000 \
  -e S3_ACCESS_KEY=local -e S3_SECRET_KEY=local -e S3_BUCKETS=demo -e SERVER_PORT=18090 s3-viewer
# http://localhost:18090
```
