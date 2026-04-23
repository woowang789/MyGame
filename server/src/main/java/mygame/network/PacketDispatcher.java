package mygame.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.game.entity.Player;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수신 패킷을 type 별 {@link PacketHandler} 로 라우팅.
 *
 * <p>Command 패턴의 단순한 구현: 명령 문자열을 키로, 처리기를 값으로
 * 맵에 담아두고 이벤트 루프에서 꺼내 실행한다. Phase M 이후 Spring 으로
 * 이관할 때 이 구조는 그대로 {@code @MessageMapping} 핸들러로 대응된다.
 */
public final class PacketDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PacketDispatcher.class);

    private final Map<String, PacketHandler> handlers = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public PacketDispatcher(ObjectMapper json) {
        this.json = json;
    }

    public void register(String type, PacketHandler handler) {
        handlers.put(type, handler);
    }

    /**
     * WebSocket 세션의 수신 메시지를 적절한 핸들러로 디스패치.
     *
     * @param conn    송신 소켓
     * @param player  접속 세션에 매핑된 Player (JOIN 이전에는 null)
     * @param message 원문 메시지
     */
    public void dispatch(WebSocket conn, Player player, String message) {
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (Exception e) {
            conn.send(PacketEnvelope.error(json, "invalid json"));
            return;
        }

        String type = PacketEnvelope.typeOf(root);
        if (type == null) {
            conn.send(PacketEnvelope.error(json, "missing type"));
            return;
        }

        PacketHandler handler = handlers.get(type);
        if (handler == null) {
            log.warn("미등록 패킷 타입: {}", type);
            conn.send(PacketEnvelope.error(json, "unknown type: " + type));
            return;
        }

        try {
            handler.handle(new PacketContext(conn, player, root, json));
        } catch (Exception e) {
            log.error("핸들러 실행 실패 [type={}]: {}", type, e.getMessage(), e);
            conn.send(PacketEnvelope.error(json, "handler error"));
        }
    }
}
