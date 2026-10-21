package s3viewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 설정. 우선순위: -D 시스템 프로퍼티 > 환경 변수 > 설정 파일.
 * 환경 변수 이름은 키를 대문자 스네이크 케이스로 바꾼 것 (s3.accessKey -> S3_ACCESS_KEY).
 */
final class Config {
    final String endpoint;
    final String region;
    final String accessKey;
    final String secretKey;
    final String sessionToken;
    final boolean pathStyle;
    final boolean insecure;
    final List<String> buckets;
    final String bind;
    final int port;
    final int threads;
    final String viewerUser;
    final String viewerPassword;
    final int connectTimeoutMs;
    final int readTimeoutMs;

    private Config(Properties p) {
        endpoint = trimSlash(require(p, "s3.endpoint"));
        region = get(p, "s3.region", "us-east-1");
        accessKey = get(p, "s3.accessKey", null);
        secretKey = get(p, "s3.secretKey", null);
        sessionToken = get(p, "s3.sessionToken", null);
        pathStyle = Boolean.parseBoolean(get(p, "s3.pathStyle", "true"));
        insecure = Boolean.parseBoolean(get(p, "s3.insecure", "false"));
        buckets = splitList(get(p, "s3.buckets", null));
        bind = get(p, "server.bind", "0.0.0.0");
        port = Integer.parseInt(get(p, "server.port", "8080"));
        threads = Integer.parseInt(get(p, "server.threads", "32"));
        viewerUser = get(p, "viewer.user", null);
        viewerPassword = get(p, "viewer.password", null);
        connectTimeoutMs = Integer.parseInt(get(p, "s3.connectTimeoutMs", "10000"));
        readTimeoutMs = Integer.parseInt(get(p, "s3.readTimeoutMs", "60000"));

        if ((accessKey == null) != (secretKey == null)) {
            throw new IllegalArgumentException("s3.accessKey 와 s3.secretKey 는 함께 지정해야 합니다.");
        }
        if ((viewerUser == null) != (viewerPassword == null)) {
            throw new IllegalArgumentException("viewer.user 와 viewer.password 는 함께 지정해야 합니다.");
        }
    }

    static Config load(String[] args) throws IOException {
        String path = System.getProperty("config", args.length > 0 ? args[0] : "s3-viewer.properties");
        Properties p = new Properties();
        File f = new File(path);
        if (f.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            System.out.println("설정 파일: " + f.getAbsolutePath());
        } else if (args.length > 0 || System.getProperty("config") != null) {
            throw new IOException("설정 파일을 찾을 수 없습니다: " + f.getAbsolutePath());
        }
        return new Config(p);
    }

    boolean hasCredentials() {
        return accessKey != null;
    }

    boolean authEnabled() {
        return viewerUser != null;
    }

    boolean bucketAllowed(String bucket) {
        return buckets.isEmpty() || buckets.contains(bucket);
    }

    private static String require(Properties p, String key) {
        String v = get(p, key, null);
        if (v == null) {
            throw new IllegalArgumentException("필수 설정이 없습니다: " + key + " (환경 변수 " + envName(key) + ")");
        }
        return v;
    }

    private static String get(Properties p, String key, String def) {
        String v = System.getProperty(key);
        if (isBlank(v)) v = System.getenv(envName(key));
        if (isBlank(v)) v = p.getProperty(key);
        return isBlank(v) ? def : v.trim();
    }

    static String envName(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == '.') sb.append('_');
            else if (Character.isUpperCase(c)) sb.append('_').append(c);
            else sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static List<String> splitList(String s) {
        if (s == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.trim().isEmpty()) out.add(part.trim());
        }
        return Collections.unmodifiableList(out);
    }
}
