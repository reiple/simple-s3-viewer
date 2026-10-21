# 운영 가이드

Java 8 서버에 S3 Viewer 를 설치·운영하는 방법.

## 1. 요구 사항

| 항목 | 내용 |
|---|---|
| 실행 | JRE 8 이상 (외부 라이브러리 불필요) |
| 빌드 | Maven 3.x + JDK 8 이상 (빌드 PC 에서만 필요) |
| 네트워크 | 뷰어 → S3 endpoint 로 HTTP(S) 연결 |
| S3 권한 | `s3:ListBucket`, `s3:GetObject` (버킷 목록까지 보려면 `s3:ListAllMyBuckets`) |

## 2. 빌드

```bash
mvn package                  # → target/s3-viewer.jar
```

JDK·Maven 이 없는 PC 에서는 Docker 로 빌드한다.

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-8 mvn -q -B package
```

## 3. 설치

```bash
sudo mkdir -p /opt/s3-viewer
sudo cp target/s3-viewer.jar /opt/s3-viewer/
sudo cp s3-viewer.properties.example /opt/s3-viewer/s3-viewer.properties
sudo chmod 600 /opt/s3-viewer/s3-viewer.properties      # 비밀키가 들어감
```

`s3-viewer.properties` 에 최소한 아래 값을 채운다.

```properties
s3.endpoint=http://s3.internal:9000
s3.accessKey=...
s3.secretKey=...
# ListBuckets 를 지원하지 않거나 권한이 없으면 필수
#s3.buckets=demo
```

실행:

```bash
cd /opt/s3-viewer && java -jar s3-viewer.jar
# 또는
java -jar /opt/s3-viewer/s3-viewer.jar /opt/s3-viewer/s3-viewer.properties
```

시작 로그 예:

```
설정 파일: /opt/s3-viewer/s3-viewer.properties
S3 Viewer 시작: http://0.0.0.0:8080/  (endpoint=http://s3.internal:9000, region=us-east-1, pathStyle=true, 인증 없음)
```

## 4. systemd 서비스 등록 (예시)

`/etc/systemd/system/s3-viewer.service`:

```ini
[Unit]
Description=Simple S3 Viewer
After=network-online.target

[Service]
User=s3viewer
WorkingDirectory=/opt/s3-viewer
ExecStart=/usr/bin/java -Xmx128m -jar /opt/s3-viewer/s3-viewer.jar /opt/s3-viewer/s3-viewer.properties
Restart=on-failure
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /sbin/nologin s3viewer
sudo chown s3viewer: /opt/s3-viewer/s3-viewer.properties
sudo systemctl daemon-reload && sudo systemctl enable --now s3-viewer
journalctl -u s3-viewer -f
```

다운로드는 스트리밍으로 중계하므로 힙을 크게 잡을 필요가 없다 (목록 조회도 요청당 최대 1000건).

## 5. nginx 리버스 프록시 (하위 경로)

UI 와 API 가 모두 상대 경로라서 `/s3/` 같은 하위 경로에 그대로 붙일 수 있다. (검증됨)

```nginx
location /s3/ {
    proxy_pass http://127.0.0.1:8080/;     # 끝의 / 로 /s3 접두사 제거
    proxy_set_header Host $host;
    proxy_buffering off;                    # 큰 파일 다운로드를 바로 흘려보냄
    proxy_read_timeout 300s;
}
```

외부에 노출할 때는 nginx 에서 HTTPS 를 종료하고, 뷰어는 `server.bind=127.0.0.1` 로 로컬에서만 받게 하는 것을 권장한다. 접근 제어는 `viewer.user`/`viewer.password` (Basic 인증) 또는 nginx 쪽 인증을 사용한다.

## 6. S3 호환 스토리지별 설정

| 스토리지 | endpoint 예 | 비고 |
|---|---|---|
| AWS S3 | `https://s3.ap-northeast-2.amazonaws.com` | `s3.region=ap-northeast-2`. 버킷 리전과 다르면 301 오류 |
| MinIO / RustFS | `http://host:9000` | 기본 설정 그대로 |
| SeaweedFS | `http://host:8333` | 기본 설정 그대로 |
| Ceph RGW | `http://host:7480` | 기본 설정 그대로 |
| ListBuckets 미지원 서버 | | `s3.buckets` 지정 필수 |

`s3.pathStyle=false` (virtual-hosted, `bucket.host` 형식) 는 해당 DNS/인증서가 준비된 경우에만 사용한다. 버킷 이름에 `.` 이 있으면 HTTPS 인증서가 맞지 않으므로 path-style 을 쓴다.

## 7. 문제 해결

| 증상 | 원인 / 조치 |
|---|---|
| 시작 시 `필수 설정이 없습니다: s3.endpoint` | 설정 파일 위치 확인. 기본은 **현재 디렉터리**의 `s3-viewer.properties` |
| 버킷 목록에 `InvalidArgument`, `AccessDenied`, `NotImplemented` | 서버가 ListBuckets 를 지원하지 않거나 권한이 없음 → `s3.buckets=버킷1,버킷2` |
| `SignatureDoesNotMatch` | access/secret key 오타, 또는 `s3.region` 불일치. 서버 시계가 15분 이상 틀려도 발생 (`RequestTimeTooSkewed`) |
| `Redirect (region 또는 pathStyle 설정 확인)` | AWS 에서 버킷 리전과 `s3.region` 이 다름 |
| `PKIX path building failed` | 사설 인증서. JVM truststore 에 추가하거나 `s3.insecure=true` |
| 특정 버킷만 403 `허용되지 않은 버킷입니다` | `s3.buckets` 허용 목록에 없는 버킷 |
| 다운로드가 중간에 끊김 | 프록시 타임아웃 (`proxy_read_timeout`), 또는 `s3.readTimeoutMs` 증가 |
| 동시 다운로드가 많으면 응답이 느림 | `server.threads` 증가 (기본 32, 다운로드 1건이 스레드 1개 점유) |

S3 로 보내는 요청과 응답을 직접 확인하려면 뷰어 없이 `curl` 로 API 를 호출해 본다.

```bash
curl -s 'http://localhost:8080/api/buckets'
curl -s 'http://localhost:8080/api/list?bucket=demo&prefix=docs/'
curl -s 'http://localhost:8080/api/head?bucket=demo&key=docs/hello.txt'
```

오류 응답은 `{"error":"코드","message":"메시지"}` 형식이며 S3 오류 코드를 그대로 전달한다.
