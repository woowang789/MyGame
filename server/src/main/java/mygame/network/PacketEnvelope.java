package mygame.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 패킷 송수신 공용 포맷.
 *
 * <p>모든 패킷은 최상위에 {@code type} 필드를 갖고 페이로드를 가진 JSON.
 * 클라이언트 구현이 간단해지도록 payload 는 루트에 평탄화하지 않고
 * 최상위에 병합한다:
 *
 * <pre>{ "type": "PLAYER_MOVE", "id": 3, "x": 100, "y": 200 }</pre>
 */
public final class PacketEnvelope {

    private PacketEnvelope() {}

    /** record 또는 POJO 를 type 필드와 함께 직렬화. */
    public static String wrap(ObjectMapper json, String type, Object payload) {
        ObjectNode node = json.valueToTree(payload);
        node.put("type", type);
        return node.toString();
    }

    /** 에러 패킷 헬퍼. */
    public static String error(ObjectMapper json, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "ERROR");
        node.put("message", message);
        return node.toString();
    }

    /** 수신 메시지에서 type 추출. 없거나 파싱 실패면 null. */
    public static String typeOf(JsonNode node) {
        JsonNode t = node.get("type");
        return (t == null || t.isNull()) ? null : t.asText();
    }
}
