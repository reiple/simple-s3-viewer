package s3viewer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 읽기 전용 S3 클라이언트 (ListBuckets, ListObjectsV2, HeadObject, GetObject). */
final class S3Client {

    static final class Bucket {
        final String name;
        final String creationDate;

        Bucket(String name, String creationDate) {
            this.name = name;
            this.creationDate = creationDate;
        }
    }

    static final class ObjectInfo {
        final String key;
        final long size;
        final String lastModified;
        final String etag;
        final String storageClass;

        ObjectInfo(String key, long size, String lastModified, String etag, String storageClass) {
            this.key = key;
            this.size = size;
            this.lastModified = lastModified;
            this.etag = etag;
            this.storageClass = storageClass;
        }
    }

    static final class Listing {
        final List<String> prefixes = new ArrayList<>();
        final List<ObjectInfo> objects = new ArrayList<>();
        boolean truncated;
        String nextToken;
    }

    /** 스트리밍 응답. 사용 후 반드시 close. */
    static final class Response implements Closeable {
        final int status;
        private final HttpURLConnection conn;

        Response(int status, HttpURLConnection conn) {
            this.status = status;
            this.conn = conn;
        }

        String header(String name) {
            return conn.getHeaderField(name);
        }

        Map<String, List<String>> headers() {
            return conn.getHeaderFields();
        }

        long contentLength() {
            return conn.getContentLengthLong();
        }

        InputStream body() throws IOException {
            return conn.getInputStream();
        }

        @Override
        public void close() {
            try {
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) in.close();
            } catch (IOException ignored) {
                // 이미 끊긴 연결
            }
        }
    }

    private final Config cfg;
    private final String scheme;
    private final String host;
    private final int port;
    private final String basePath;
    private final SSLSocketFactory insecureFactory;

    S3Client(Config cfg) {
        this.cfg = cfg;
        URI u = URI.create(cfg.endpoint);
        if (u.getScheme() == null || u.getHost() == null) {
            throw new IllegalArgumentException("s3.endpoint 형식이 잘못되었습니다 (예: https://s3.example.com): " + cfg.endpoint);
        }
        scheme = u.getScheme().toLowerCase(Locale.ROOT);
        host = u.getHost();
        int p = u.getPort();
        // HttpURLConnection 은 기본 포트일 때 Host 헤더에 포트를 붙이지 않으므로 서명도 맞춘다
        if ((p == 80 && "http".equals(scheme)) || (p == 443 && "https".equals(scheme))) p = -1;
        port = p;
        basePath = u.getRawPath() == null ? "" : u.getRawPath();
        insecureFactory = cfg.insecure ? trustAllFactory() : null;
    }

    List<Bucket> listBuckets() throws IOException {
        try (Response r = send("GET", null, null, null, null)) {
            Element root = parse(r);
            List<Bucket> out = new ArrayList<>();
            Element bucketsEl = child(root, "Buckets");
            if (bucketsEl != null) {
                for (Element b : children(bucketsEl, "Bucket")) {
                    out.add(new Bucket(text(b, "Name"), text(b, "CreationDate")));
                }
            }
            return out;
        }
    }

    Listing listObjects(String bucket, String prefix, String delimiter, String token, int maxKeys) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("max-keys", String.valueOf(maxKeys));
        if (prefix != null && !prefix.isEmpty()) q.put("prefix", prefix);
        if (delimiter != null && !delimiter.isEmpty()) q.put("delimiter", delimiter);
        if (token != null && !token.isEmpty()) q.put("continuation-token", token);

        try (Response r = send("GET", bucket, null, q, null)) {
            Element root = parse(r);
            Listing l = new Listing();
            for (Element cp : children(root, "CommonPrefixes")) {
                l.prefixes.add(text(cp, "Prefix"));
            }
            for (Element c : children(root, "Contents")) {
                String size = text(c, "Size");
                l.objects.add(new ObjectInfo(text(c, "Key"), size == null ? 0 : Long.parseLong(size),
                        text(c, "LastModified"), stripQuotes(text(c, "ETag")), text(c, "StorageClass")));
            }
            l.truncated = "true".equalsIgnoreCase(text(root, "IsTruncated"));
            l.nextToken = text(root, "NextContinuationToken");
            return l;
        }
    }

    /** HeadObject. 호출 측에서 헤더를 읽은 뒤 close 한다. */
    Response headObject(String bucket, String key) throws IOException {
        Response r = send("HEAD", bucket, key, null, null);
        if (r.status >= 300) {
            r.close();
            throw new S3Exception(r.status, statusCode(r.status), "HEAD " + r.status);
        }
        return r;
    }

    /** GetObject. 2xx 가 아니면 S3Exception. range 는 그대로 전달 (예: "bytes=0-1023"). */
    Response getObject(String bucket, String key, String range) throws IOException {
        Map<String, String> extra = null;
        if (range != null) {
            extra = new TreeMap<>();
            extra.put("Range", range);
        }
        Response r = send("GET", bucket, key, null, extra);
        if (r.status >= 300) {
            try {
                throw error(r);
            } finally {
                r.close();
            }
        }
        return r;
    }

    private Response send(String method, String bucket, String key, Map<String, String> query,
                          Map<String, String> extraHeaders) throws IOException {
        String reqHost = host;
        String path;
        if (bucket == null) {
            path = "/";
        } else if (cfg.pathStyle) {
            path = "/" + SigV4.uriEncode(bucket, true) + (key == null ? "/" : "/" + SigV4.uriEncode(key, false));
        } else {
            reqHost = bucket + "." + host;
            path = "/" + (key == null ? "" : SigV4.uriEncode(key, false));
        }
        path = basePath + path;
        String hostHeader = port == -1 ? reqHost : reqHost + ":" + port;
        String qs = SigV4.canonicalQuery(query);

        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", hostHeader);
        if (cfg.hasCredentials()) {
            SigV4.sign(headers, method, path, qs, cfg.region, cfg.accessKey, cfg.secretKey, cfg.sessionToken,
                    Instant.now());
        }

        URL url = new URL(scheme + "://" + hostHeader + path + (qs.isEmpty() ? "" : "?" + qs));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (insecureFactory != null && c instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            https.setSSLSocketFactory(insecureFactory);
            https.setHostnameVerifier((h, s) -> true);
        }
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(cfg.connectTimeoutMs);
        c.setReadTimeout(cfg.readTimeoutMs);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!"host".equals(e.getKey())) c.setRequestProperty(e.getKey(), e.getValue());
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return new Response(c.getResponseCode(), c);
    }

    private Element parse(Response r) throws IOException {
        if (r.status >= 300) throw error(r);
        return parseXml(readAll(r.body(), 64 * 1024 * 1024)).getDocumentElement();
    }

    private static S3Exception error(Response r) {
        String code = statusCode(r.status);
        String message = null;
        try {
            InputStream in = r.conn.getErrorStream();
            if (in != null) {
                byte[] body = readAll(in, 1024 * 1024);
                if (body.length > 0) {
                    Element root = parseXml(body).getDocumentElement();
                    if (text(root, "Code") != null) code = text(root, "Code");
                    message = text(root, "Message");
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // XML 이 아닌 오류 응답
        }
        return new S3Exception(r.status, code, message);
    }

    private static String statusCode(int status) {
        switch (status) {
            case 301: case 307: return "Redirect (region 또는 pathStyle 설정 확인)";
            case 403: return "AccessDenied";
            case 404: return "NotFound";
            default: return "HTTP " + status;
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("응답이 너무 큽니다");
            }
            return out.toByteArray();
        }
    }

    private static Document parseXml(byte[] data) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new java.io.ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new IOException("S3 응답 XML 파싱 실패: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) out.add((Element) n);
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        List<Element> l = children(parent, name);
        return l.isEmpty() ? null : l.get(0);
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent();
    }

    private static String stripQuotes(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static SSLSocketFactory trustAllFactory() {
        try {
            TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
