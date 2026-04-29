package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import mygame.admin.HttpUtils;

/**
 * /admin/assets/* 정적 파일. classpath {@code admin/} 디렉터리에서 읽는다.
 *
 * <p>경로 트래버설 방지: '..' 등 위험 토큰 차단. 화이트리스트 기반 확장자 점검.
 */
public final class StaticAssetHandler implements HttpHandler {

    private static final String PREFIX = "/admin/assets/";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            HttpUtils.sendText(ex, 404, "Not Found");
            return;
        }
        String name = path.substring(PREFIX.length());
        if (name.isEmpty() || name.contains("..") || name.contains("/")) {
            HttpUtils.sendText(ex, 400, "Bad Request");
            return;
        }
        String contentType = guessType(name);
        if (contentType == null) {
            HttpUtils.sendText(ex, 415, "Unsupported asset type");
            return;
        }
        try (InputStream in = StaticAssetHandler.class.getClassLoader()
                .getResourceAsStream("admin/" + name)) {
            if (in == null) {
                HttpUtils.sendText(ex, 404, "Not Found: " + name);
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        }
    }

    private static String guessType(String name) {
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=" + StandardCharsets.UTF_8.name().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        return null;
    }
}
