package mygame.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygame.game.entity.Player;
import org.java_websocket.WebSocket;

/**
 * 패킷 핸들러에 전달되는 실행 컨텍스트.
 *
 * <p>핸들러는 이 객체만 받아 로직을 수행한다. 의존성(WebSocket, 현재 Player,
 * JSON 본문, Jackson 인스턴스)을 한 군데로 모아 시그니처를 고정한다.
 */
public record PacketContext(
        WebSocket conn,
        Player player,
        JsonNode body,
        ObjectMapper json
) {}
