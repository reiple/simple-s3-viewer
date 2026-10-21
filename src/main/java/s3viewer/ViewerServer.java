package s3viewer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP 서버. 모든 엔드포인트는 GET 전용이다.
 * <pre>
 *   GET /                      UI
 *   GET /api/info              엔드포인트 정보
 *   GET /api/buckets           버킷 목록
 *   GET /api/list              ?bucket&amp;prefix&amp;token&amp;delimiter&amp;max
 *   GET /api/head              ?bucket&amp;key
 *   GET /api/object            ?bucket&amp;key[&amp;inline=1]   (Range 헤더 전달)
 * </pre>
 */
final class ViewerServer {
    private static final Logger LOG = Logger.getLogger(ViewerServer.class.getName());

    private static final Map<String, String> STATIC = new HashMap<>();

    static {
        STATIC.put("/", "index.html");
        STATIC.put("/index.html", "index.html");
        STATIC.put("/app.js", "app.js");
        STATIC.put("/style.css", "style.css");
    }

    /** inline 미리보기에서 원래 Content-Type 을 그대로 보낼 형식. 나머지는 text/plain 으로 바꾼다. */
    private static final String[] INLINE_SAFE_PREFIXES = {"image/", "video/", "audio/", "application/pdf"};

    private static final Map<String, String> MEDIA_BY_EXT = new HashMap<>();

    static {
        String[][] types = {
                {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
                {"webp", "image/webp"}, {"bmp", "image/bmp"}, {"svg", "image/svg+xml"}, {"ico", "image/x-icon"},
                {"avif", "image/avif"}, {"mp4", "video/mp4"}, {"m4v", "video/mp4"}, {"webm", "video/webm"},
                {"mov", "video/quicktime"}, {"mp3", "audio/mpeg"}, {"wav", "audio/wav"}, {"ogg", "audio/ogg"},
                {"m4a", "audio/mp4"}, {"flac", "audio/flac"}, {"pdf", "application/pdf"},
        };
        for (String[] t : types) MEDIA_BY_EXT.put(t[0], t[1]);
    }

    private final Config cfg;
    private final S3Client s3;
    private final HttpServer server;
    private final byte[] expectedAuth;

    ViewerServer(Config cfg, S3Client s3) throws IOException {
        this.cfg = cfg;
        this.s3 = s3;
        this.server = HttpServer.create(new InetSocketAddress(cfg.bind, cfg.port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(cfg.threads));
        this.server.createContext("/", this::handle);
        this.expectedAuth = cfg.authEnabled()
                ? ("Basic " + Base64.getEncoder().encodeToString(
                        (cfg.viewerUser + ":" + cfg.viewerPassword).getBytes(StandardCharsets.UTF_8)))
                        .getBytes(StandardCharsets.UTF_8)
                : null;
    }

    void start() {
        server.start();
    }

    private void handle(HttpExchange ex) {
        try {
            Headers h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Frame-Options", "SAMEORIGIN");

            if (!authorized(ex)) {
                h.set("WWW-Authenticate", "Basic realm=\"S3 Viewer\", charset=\"UTF-8\"");
                sendJson(ex, 401, error("Unauthorized", "인증이 필요합니다"));
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                h.set("Allow", "GET");
                sendJson(ex, 405, error("MethodNotAllowed", "읽기 전용 서버입니다"));
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (STATIC.containsKey(path)) {
                serveStatic(ex, STATIC.get(path));
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            switch (path) {
                case "/api/info": info(ex); break;
                case "/api/buckets": buckets(ex); break;
                case "/api/list": list(ex, q); break;
                case "/api/head": head(ex, q); break;
                case "/api/object": object(ex, q); break;
                default: sendJson(ex, 404, error("NotFound", "없는 경로입니다: " + path));
            }
        } catch (S3Exception e) {
            int status = e.status == 403 || e.status == 404 || e.status == 416 ? e.status : 502;
            trySendError(ex, status, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            trySendError(ex, 400, "BadRequest", e.getMessage());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "요청 처리 실패: " + ex.getRequestURI(), e);
            trySendError(ex, 502, "UpstreamError", e.toString());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "내부 오류: " + ex.getRequestURI(), e);
            trySendError(ex, 500, "InternalError", e.toString());
        } finally {
            ex.close();
        }
    }

    // ---- API ----

    private void info(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"endpoint\":" + Json.q(cfg.endpoint) + ",\"region\":" + Json.q(cfg.region)
                + ",\"fixedBuckets\":" + !cfg.buckets.isEmpty() + "}");
    }

    private void buckets(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{\"buckets\":[");
        if (!cfg.buckets.isEmpty()) {
            for (int i = 0; i < cfg.buckets.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(Json.q(cfg.buckets.get(i))).append(",\"creationDate\":null}");
            }
        } else {
            List<S3Client.Bucket> list = s3.listBuckets();
            for (int i = 0; i < list.size(); i++) {
                S3Client.Bucket b = list.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(Json.q(b.name))
                        .append(",\"creationDate\":").append(Json.q(b.creationDate)).append('}');
            }
        }
        sendJson(ex, 200, sb.append("]}").toString());
    }

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        String bucket = bucket(q);
        String prefix = q.getOrDefault("prefix", "");
        String delimiter = q.containsKey("delimiter") ? q.get("delimiter") : "/";
        int max = Math.max(1, Math.min(1000, parseInt(q.get("max"), 1000)));

        S3Client.Listing l = s3.listObjects(bucket, prefix, delimiter, q.get("token"), max);
        StringBuilder sb = new StringBuilder(256 + l.objects.size() * 160);
        sb.append("{\"bucket\":").append(Json.q(bucket))
                .append(",\"prefix\":").append(Json.q(prefix))
                .append(",\"truncated\":").append(l.truncated)
                .append(",\"nextToken\":").append(Json.q(l.nextToken))
                .append(",\"prefixes\":[");
        for (int i = 0; i < l.prefixes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.q(l.prefixes.get(i)));
        }
        sb.append("],\"objects\":[");
        for (int i = 0; i < l.objects.size(); i++) {
            S3Client.ObjectInfo o = l.objects.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"key\":").append(Json.q(o.key))
                    .append(",\"size\":").append(o.size)
                    .append(",\"lastModified\":").append(Json.q(o.lastModified))
                    .append(",\"etag\":").append(Json.q(o.etag))
                    .append(",\"storageClass\":").append(Json.q(o.storageClass)).append('}');
        }
        sendJson(ex, 200, sb.append("]}").toString());
    }

    private void head(HttpExchange ex, Map<String, String> q) throws IOException {
        String bucket = bucket(q);
        String key = key(q);
        try (S3Client.Response r = s3.headObject(bucket, key)) {
            StringBuilder headers = new StringBuilder();
            StringBuilder meta = new StringBuilder();
            for (Map.Entry<String, List<String>> e : new TreeMap<>(lowerCase(r.headers())).entrySet()) {
                String name = e.getKey();
                String value = String.join(", ", e.getValue());
                if (name.startsWith("x-amz-meta-")) {
                    if (meta.length() > 0) meta.append(',');
                    meta.append(Json.q(name.substring("x-amz-meta-".length()))).append(':').append(Json.q(value));
                } else if (isInterestingHeader(name)) {
                    if (headers.length() > 0) headers.append(',');
                    headers.append(Json.q(name)).append(':').append(Json.q(value));
                }
            }
            sendJson(ex, 200, "{\"bucket\":" + Json.q(bucket) + ",\"key\":" + Json.q(key)
                    + ",\"size\":" + r.contentLength()
                    + ",\"contentType\":" + Json.q(r.header("Content-Type"))
                    + ",\"lastModified\":" + Json.q(r.header("Last-Modified"))
                    + ",\"etag\":" + Json.q(r.header("ETag"))
                    + ",\"headers\":{" + headers + "},\"metadata\":{" + meta + "}}");
        }
    }

    private void object(HttpExchange ex, Map<String, String> q) throws IOException {
        String bucket = bucket(q);
        String key = key(q);
        boolean inline = "1".equals(q.get("inline"));
        String range = ex.getRequestHeaders().getFirst("Range");

        try (S3Client.Response r = s3.getObject(bucket, key, range)) {
            Headers h = ex.getResponseHeaders();
            String contentType = r.header("Content-Type");
            if (contentType == null) contentType = "application/octet-stream";
            String fileName = key.substring(key.lastIndexOf('/') + 1);

            if (inline) {
                contentType = inlineContentType(contentType, fileName);
                // 객체가 뷰어 origin 에서 스크립트를 실행하지 못하도록 격리 (PDF 뷰어는 sandbox 에서 동작하지 않음)
                if (!contentType.startsWith("application/pdf")) h.set("Content-Security-Policy", "sandbox");
                h.set("Content-Disposition", contentDisposition("inline", fileName));
            } else {
                h.set("Content-Disposition", contentDisposition("attachment", fileName));
            }
            h.set("Content-Type", contentType);
            h.set("Cache-Control", "private, no-cache");
            h.set("Accept-Ranges", "bytes");
            copyHeader(r, h, "Content-Range");
            copyHeader(r, h, "ETag");
            copyHeader(r, h, "Last-Modified");

            long len = r.contentLength();
            ex.sendResponseHeaders(r.status, len == 0 ? -1 : (len > 0 ? len : 0));
            if (len == 0) return;
            try (InputStream in = r.body(); OutputStream out = ex.getResponseBody()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException e) {
                // 대부분 브라우저가 다운로드를 취소한 경우
                LOG.fine("스트리밍 중단: " + e);
            }
        }
    }

    // ---- 보조 ----

    private String bucket(Map<String, String> q) throws S3Exception {
        String b = q.get("bucket");
        if (b == null || b.isEmpty()) throw new IllegalArgumentException("bucket 파라미터가 필요합니다");
        if (!cfg.bucketAllowed(b)) throw new S3Exception(403, "AccessDenied", "허용되지 않은 버킷입니다: " + b);
        return b;
    }

    private static String key(Map<String, String> q) {
        String k = q.get("key");
        if (k == null || k.isEmpty()) throw new IllegalArgumentException("key 파라미터가 필요합니다");
        return k;
    }

    private boolean authorized(HttpExchange ex) {
        if (expectedAuth == null) return true;
        String got = ex.getRequestHeaders().getFirst("Authorization");
        return got != null && MessageDigest.isEqual(expectedAuth, got.getBytes(StandardCharsets.UTF_8));
    }

    private static String inlineContentType(String original, String fileName) {
        String lower = original.toLowerCase(Locale.ROOT);
        if (lower.startsWith("application/octet-stream") || lower.startsWith("binary/octet-stream")) {
            // 업로드 시 Content-Type 을 지정하지 않은 객체는 확장자로 추정
            int dot = fileName.lastIndexOf('.');
            String guessed = dot < 0 ? null : MEDIA_BY_EXT.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (guessed != null) return guessed;
        }
        for (String p : INLINE_SAFE_PREFIXES) {
            if (lower.startsWith(p)) return original;
        }
        int cs = lower.indexOf("charset=");
        return "text/plain; charset=" + (cs >= 0 ? original.substring(cs + 8).trim() : "utf-8");
    }

    private static String contentDisposition(String type, String fileName) {
        StringBuilder ascii = new StringBuilder();
        for (char c : fileName.toCharArray()) {
            ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + SigV4.uriEncode(fileName, true);
    }

    private static boolean isInterestingHeader(String name) {
        return name.startsWith("x-amz-") || name.startsWith("content-") || name.equals("cache-control")
                || name.equals("expires") || name.equals("etag") || name.equals("last-modified");
    }

    private static Map<String, List<String>> lowerCase(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    private static void copyHeader(S3Client.Response r, Headers h, String name) {
        String v = r.header(name);
        if (v != null) h.set(name, v);
    }

    private void serveStatic(HttpExchange ex, String file) throws IOException {
        InputStream in = ViewerServer.class.getResourceAsStream("/web/" + file);
        if (in == null) {
            sendJson(ex, 404, error("NotFound", file));
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream is = in) {
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) != -1) buf.write(b, 0, n);
        }
        String type = file.endsWith(".js") ? "text/javascript; charset=utf-8"
                : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-cache");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; media-src 'self'; "
                + "frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'");
        send(ex, 200, buf.toByteArray());
    }

    private static String error(String code, String message) {
        return "{\"error\":" + Json.q(code) + ",\"message\":" + Json.q(message) + "}";
    }

    private static void trySendError(HttpExchange ex, int status, String code, String message) {
        if (ex.getResponseCode() != -1) return; // 이미 응답 헤더를 보냄
        try {
            ex.getResponseHeaders().remove("Content-Disposition");
            ex.getResponseHeaders().remove("Content-Security-Policy");
            sendJson(ex, status, error(code, message));
        } catch (IOException ignored) {
            // 클라이언트 연결 끊김
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            out.put(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
