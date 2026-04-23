package mygame.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Player;
import mygame.network.packets.Packets.JoinRequest;
import mygame.network.packets.Packets.MoveRequest;
import mygame.network.packets.Packets.PlayerJoinedPacket;
import mygame.network.packets.Packets.PlayerLeftPacket;
import mygame.network.packets.Packets.PlayerMovedPacket;
import mygame.network.packets.Packets.WelcomePacket;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase C — 멀티플레이어 위치 동기화 서버.
 *
 * <p>프로토콜:
 * <ul>
 *   <li>C→S {@code JOIN {name}}  : 입장. 서버가 Player 를 생성해 기본 맵에 배치.
 *   <li>S→C {@code WELCOME}      : 자신의 정보와 이미 맵에 있던 타 플레이어 목록.
 *   <li>S→C {@code PLAYER_JOIN}  : 다른 플레이어의 입장 알림.
 *   <li>C→S {@code MOVE}         : 내 위치 갱신.
 *   <li>S→C {@code PLAYER_MOVE}  : 타인 이동 브로드캐스트(본인 제외).
 *   <li>S→C {@code PLAYER_LEAVE} : 퇴장 알림.
 * </ul>
 */
public final class GameServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    private final ObjectMapper json = new ObjectMapper()
            // payload record 에는 없는 "type" 필드를 허용 — envelope 를 그대로 record 로 변환하기 위함.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final World world = new World(json);
    private final PacketDispatcher dispatcher = new PacketDispatcher(json);

    // 세션 ↔ Player 매핑. JOIN 이전에는 값이 null 일 수 있으므로 별도 관리.
    private final Map<WebSocket, Player> sessionPlayers = new ConcurrentHashMap<>();
    private final AtomicInteger playerIdSeq = new AtomicInteger(1);

    public GameServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        registerHandlers();
    }

    private void registerHandlers() {
        dispatcher.register("JOIN", this::handleJoin);
        dispatcher.register("MOVE", this::handleMove);
    }

    // --- WebSocket 콜백 ---

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("소켓 연결: {}", conn.getRemoteSocketAddress());
        // JOIN 패킷을 기다린다. 그 전까지는 Player 가 없다.
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Player player = sessionPlayers.remove(conn);
        if (player == null) {
            log.info("JOIN 전에 연결 종료: remote={}", conn.getRemoteSocketAddress());
            return;
        }
        GameMap map = world.defaultMap();
        map.removePlayer(player.id());
        map.broadcast("PLAYER_LEAVE", new PlayerLeftPacket(player.id()));
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Player player = sessionPlayers.get(conn);
        dispatcher.dispatch(conn, player, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            log.error("서버 오류", ex);
        } else {
            log.error("세션 오류 [player={}]", sessionPlayers.get(conn), ex);
        }
    }

    @Override
    public void onStart() {
        log.info("WebSocket 서버 리스닝 시작.");
        setConnectionLostTimeout(60);
    }

    // --- 핸들러 ---

    private void handleJoin(PacketContext ctx) throws Exception {
        if (sessionPlayers.containsKey(ctx.conn())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "already joined"));
            return;
        }
        JoinRequest req = ctx.json().treeToValue(ctx.body(), JoinRequest.class);
        String name = (req.name() == null || req.name().isBlank())
                ? "Player" + playerIdSeq.get()
                : req.name();

        GameMap map = world.defaultMap();
        int id = playerIdSeq.getAndIncrement();
        Player player = new Player(id, name, ctx.conn(), map.spawnX(), map.spawnY());

        sessionPlayers.put(ctx.conn(), player);
        map.addPlayer(player);

        // 새 플레이어에게 자신의 정보 + 이미 있던 다른 플레이어 목록 전송
        WelcomePacket welcome = new WelcomePacket(
                id,
                player.toState(),
                map.othersOf(id).stream().map(Player::toState).toList()
        );
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "WELCOME", welcome));

        // 기존 플레이어들에게 신규 입장 알림
        map.broadcastExcept(id, "PLAYER_JOIN", new PlayerJoinedPacket(player.toState()));
    }

    private void handleMove(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "join required"));
            return;
        }
        MoveRequest req = ctx.json().treeToValue(ctx.body(), MoveRequest.class);
        player.updatePosition(req.x(), req.y(), req.vx(), req.vy());

        GameMap map = world.defaultMap();
        map.broadcastExcept(
                player.id(),
                "PLAYER_MOVE",
                new PlayerMovedPacket(player.id(), req.x(), req.y(), req.vx(), req.vy())
        );
    }
}
