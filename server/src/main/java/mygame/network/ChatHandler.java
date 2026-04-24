package mygame.network;

import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Player;
import mygame.network.packets.Packets.ChatMessage;
import mygame.network.packets.Packets.ChatRequest;

/**
 * 채팅 요청 전용 핸들러. ALL(같은 맵 브로드캐스트) / WHISPER(개인 1:1) 라우팅만 담당.
 *
 * <p>플레이어 상태에 영향이 없고 순수 메시징이라 GameServer 에서 떼어내도 의존성이 적다.
 */
public final class ChatHandler {

    /** 한 메시지당 허용 최대 길이. 초과 시 자르고 허용(에러로 막지 않는다). */
    private static final int MAX_CHAT_LEN = 200;

    private final World world;

    public ChatHandler(World world) {
        this.world = world;
    }

    public void handle(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        ChatRequest req = ctx.json().treeToValue(ctx.body(), ChatRequest.class);
        if (req.message() == null || req.message().isBlank()) return;

        String msg = req.message();
        if (msg.length() > MAX_CHAT_LEN) msg = msg.substring(0, MAX_CHAT_LEN);

        String scope = req.scope() == null ? "ALL" : req.scope();
        switch (scope) {
            case "ALL" -> {
                GameMap map = world.map(player.mapId());
                if (map != null) {
                    map.broadcast("CHAT", new ChatMessage("ALL", player.name(), msg));
                }
            }
            case "WHISPER" -> {
                Player target = world.playerByName(req.target());
                if (target == null) {
                    ctx.conn().send(PacketEnvelope.error(ctx.json(), "no such player: " + req.target()));
                    return;
                }
                // 보낸 사람·받는 사람 모두에게 같은 메시지를 echo 해 로컬 UI 가 구성되도록 한다.
                String payload = PacketEnvelope.wrap(ctx.json(), "CHAT",
                        new ChatMessage("WHISPER:" + target.name(), player.name(), msg));
                ctx.conn().send(payload);
                if (target.id() != player.id() && target.connection().isOpen()) {
                    target.connection().send(payload);
                }
            }
            default -> ctx.conn().send(PacketEnvelope.error(ctx.json(), "unknown scope"));
        }
    }
}
