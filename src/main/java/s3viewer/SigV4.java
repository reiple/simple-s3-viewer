package s3viewer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 (S3, 헤더 방식, 본문 없는 요청 전용). */
final class SigV4 {
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SigV4() {
    }

    /**
     * headers 에 x-amz-date, x-amz-content-sha256, (x-amz-security-token), authorization 을 추가한다.
     * headers 는 소문자 이름의 TreeMap 이어야 하며 host 를 포함해야 한다. 서명 시점의 모든 헤더가 서명 대상이다.
     */
    static void sign(TreeMap<String, String> headers, String method, String canonicalPath, String canonicalQuery,
                     String region, String accessKey, String secretKey, String sessionToken, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String date = amzDate.substring(0, 8);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", EMPTY_SHA256);
        if (sessionToken != null) headers.put("x-amz-security-token", sessionToken);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + '\n' + canonicalPath + '\n' + canonicalQuery + '\n'
                + canonicalHeaders + '\n' + signedHeaders + '\n' + EMPTY_SHA256;
        String scope = date + '/' + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + '\n' + scope + '\n'
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, stringToSign));

        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    }

    /** RFC 3986 방식 URI 인코딩 (S3 규칙: unreserved 문자 외 모두 %XX). */
    static String uriEncode(String s, boolean encodeSlash) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return sb.toString();
    }

    /** 정렬·인코딩된 쿼리 문자열. URL 과 서명에 동일하게 사용한다. */
    static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            sorted.put(uriEncode(e.getKey(), true), e.getValue() == null ? "" : uriEncode(e.getValue(), true));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
