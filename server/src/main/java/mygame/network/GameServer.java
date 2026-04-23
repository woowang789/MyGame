package mygame.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase A — 에코 서버.
 *
 * <p>접속한 클라이언트에게 고유한 세션 ID를 부여하고, 수신한 메시지를
 * 그대로 되돌려준다. 이후 Phase에서 {@link PacketDispatcher}를 통해
 * 타입별 핸들러로 라우팅하도록 확장된다.
 *
 * <p>동시성 노트: {@link #sessions}는 여러 WebSocket 스레드에서
 * 동시에 접근되므로 반드시 {@link ConcurrentHashMap}을 사용한다.
 */
public final class GameServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    private final ObjectMapper json = new ObjectMapper();
    private final Map<WebSocket, Integer> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionIdSeq = new AtomicInteger(1);

    public GameServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        int sessionId = sessionIdSeq.getAndIncrement();
        sessions.put(conn, sessionId);
        log.info("접속: sessionId={}, remote={}", sessionId, conn.getRemoteSocketAddress());

        ObjectNode welcome = json.createObjectNode();
        welcome.put("type", "WELCOME");
        welcome.put("sessionId", sessionId);
        welcome.put("message", "MyGame 서버에 오신 것을 환영합니다.");
        conn.send(welcome.toString());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Integer sessionId = sessions.remove(conn);
        log.info("접속 해제: sessionId={}, code={}, reason={}", sessionId, code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Integer sessionId = sessions.get(conn);
        log.info("수신 [session={}]: {}", sessionId, message);

        // Phase A: 단순 에코. 파싱이 실패하면 원문을 echo 필드에 담아 돌려준다.
        try {
            JsonNode received = json.readTree(message);
            ObjectNode response = json.createObjectNode();
            response.put("type", "ECHO");
            response.put("sessionId", sessionId == null ? -1 : sessionId);
            response.set("payload", received);
            conn.send(response.toString());
        } catch (Exception e) {
            log.warn("JSON 파싱 실패: {}", e.getMessage());
            ObjectNode err = json.createObjectNode();
            err.put("type", "ERROR");
            err.put("message", "invalid json");
            conn.send(err.toString());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // conn 이 null 이면 서버 자체 오류(포트 바인드 실패 등)
        if (conn == null) {
            log.error("서버 오류", ex);
        } else {
            log.error("세션 오류 [session={}]", sessions.get(conn), ex);
        }
    }

    @Override
    public void onStart() {
        log.info("WebSocket 서버 리스닝 시작.");
        setConnectionLostTimeout(60);
    }
}
