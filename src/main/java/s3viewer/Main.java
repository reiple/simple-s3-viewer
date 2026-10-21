package s3viewer;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config cfg;
        try {
            cfg = Config.load(args);
        } catch (IllegalArgumentException e) {
            System.err.println("설정 오류: " + e.getMessage());
            System.exit(2);
            return;
        }
        ViewerServer server = new ViewerServer(cfg, new S3Client(cfg));
        server.start();
        System.out.println("S3 Viewer 시작: http://" + cfg.bind + ":" + cfg.port + "/  (endpoint=" + cfg.endpoint
                + ", region=" + cfg.region + ", pathStyle=" + cfg.pathStyle
                + (cfg.authEnabled() ? ", basic auth 사용" : ", 인증 없음") + ")");
    }
}
