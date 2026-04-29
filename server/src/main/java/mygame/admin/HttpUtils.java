package mygame.admin;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 백오피스 HTTP 핸들러용 공통 헬퍼.
 *
 * <p>JDK {@code HttpServer} 만으로 라우팅·쿠키·폼 파싱을 직접 짜본다. Spring 의
 * {@code @RequestMapping}/{@code @CookieValue} 가 무엇을 추상화하는지 체감 목적.
 */
public final class HttpUtils {

    private HttpUtils() {}

    public static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    public static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    public static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    /** 단순 폼 파서 — multipart 미지원, application/x-www-form-urlencoded 만. */
    public static Map<String, String> parseFormBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        return parseQuery(new String(raw, StandardCharsets.UTF_8));
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Cookie 헤더에서 단일 쿠키 값을 추출. 없으면 null. */
    public static String cookie(HttpExchange ex, String name) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String pair : header.split(";")) {
                String trimmed = pair.trim();
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                if (trimmed.substring(0, eq).equals(name)) {
                    return trimmed.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /** Set-Cookie 헤더 추가. HttpOnly + SameSite=Strict 기본. maxAgeSeconds < 0 이면 세션 쿠키. */
    public static void setCookie(HttpExchange ex, String name, String value, long maxAgeSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value);
        sb.append("; Path=/; HttpOnly; SameSite=Strict");
        if (maxAgeSeconds >= 0) sb.append("; Max-Age=").append(maxAgeSeconds);
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    public static void clearCookie(HttpExchange ex, String name) {
        ex.getResponseHeaders().add("Set-Cookie",
                name + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    public static Map<String, String> queryParams(HttpExchange ex) {
        return parseQuery(ex.getRequestURI().getRawQuery());
    }

    public static int intParam(Map<String, String> params, String key, int defaultValue) {
        String v = params.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** htmx 요청인지 감지 — 부분 HTML 응답을 줄지, 풀 페이지를 줄지 분기. */
    public static boolean isHtmx(HttpExchange ex) {
        return "true".equals(ex.getRequestHeaders().getFirst("HX-Request"));
    }

    /** 동일 키 여러 값 처리가 필요할 때(거의 안 쓰임). */
    public static Map<String, List<String>> headersAsMap(HttpExchange ex) {
        return new HashMap<>(ex.getRequestHeaders());
    }
}
