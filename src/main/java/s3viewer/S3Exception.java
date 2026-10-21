package s3viewer;

import java.io.IOException;

/** S3 가 오류 응답을 돌려준 경우. */
final class S3Exception extends IOException {
    final int status;
    final String code;

    S3Exception(int status, String code, String message) {
        super(message == null || message.isEmpty() ? code : message);
        this.status = status;
        this.code = code;
    }
}
