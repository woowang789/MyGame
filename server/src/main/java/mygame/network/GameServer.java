package mygame.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.game.GameLoop;
import mygame.game.GameMap;
import mygame.game.ProgressionSystem;
import mygame.game.World;
import mygame.game.entity.Monster;
import mygame.game.entity.Player;
import mygame.game.event.EventBus;
import mygame.game.event.GameEvent;
import mygame.game.event.GameEvent.ExpGained;
import mygame.game.event.GameEvent.LeveledUp;
import mygame.game.event.GameEvent.MonsterKilled;
import mygame.network.packets.Packets.ChangeMapRequest;
import mygame.network.packets.Packets.JoinRequest;
import mygame.game.SpawnPoint;
import mygame.game.item.DroppedItem;
import mygame.network.packets.Packets.AttackRequest;
import mygame.network.packets.Packets.DroppedItemState;
import mygame.network.packets.Packets.InventoryPacket;
import mygame.network.packets.Packets.ExpUpdatedPacket;
import mygame.network.packets.Packets.LevelUpPacket;
import mygame.network.packets.Packets.MapChangedPacket;
import mygame.network.packets.Packets.MonsterDamagedPacket;
import mygame.network.packets.Packets.MonsterDiedPacket;
import mygame.network.packets.Packets.MonsterState;
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
 * Phase D — 다중 맵 + 포털 전환.
 *
 * <p>프로토콜 변경점(vs Phase C):
 * <ul>
 *   <li>플레이어는 반드시 특정 맵에 소속된다. 이동/브로드캐스트는 그 맵 내부에서만 발생.
 *   <li>{@code CHANGE_MAP} 요청 시 현재 맵에서 제거(PLAYER_LEAVE 브로드캐스트) 후
 *       대상 맵에 추가(PLAYER_JOIN 브로드캐스트). 전환 당사자에게는
 *       {@code MAP_CHANGED} 로 새 맵의 현재 플레이어 목록을 전달한다.
 * </ul>
 */
public final class GameServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final World world = new World(json);
    private final GameLoop gameLoop = new GameLoop(world);
    private final EventBus eventBus = new EventBus();
    @SuppressWarnings("unused") // 생성 시 EventBus 에 자기 자신을 등록하므로 참조 유지만 하면 충분
    private final ProgressionSystem progression = new ProgressionSystem(eventBus);
    private final PacketDispatcher dispatcher = new PacketDispatcher(json);

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
        dispatcher.register("CHANGE_MAP", this::handleChangeMap);
        dispatcher.register("ATTACK", this::handleAttack);
        dispatcher.register("PICKUP", this::handlePickup);

        // EventBus 구독: 진행(ExpGained/LeveledUp) → 네트워크 알림.
        // ProgressionSystem 은 도메인 로직만, 송신은 여기서 분리 처리한다.
        eventBus.subscribe(this::broadcastProgression);
    }

    private void broadcastProgression(GameEvent event) {
        switch (event) {
            case ExpGained e -> {
                Player p = e.player();
                p.connection().send(PacketEnvelope.wrap(json, "PLAYER_EXP",
                        new ExpUpdatedPacket(p.exp(), p.level(), p.expToNextLevel(), e.gained())));
            }
            case LeveledUp e -> {
                Player p = e.player();
                // 본인 + 같은 맵 플레이어에게 이펙트용 알림
                GameMap map = world.map(p.mapId());
                if (map != null) {
                    map.broadcast("PLAYER_LEVELUP", new LevelUpPacket(p.id(), e.newLevel()));
                }
                // EXP 바 동기화(가산 0 으로 리셋된 상태 전달)
                p.connection().send(PacketEnvelope.wrap(json, "PLAYER_EXP",
                        new ExpUpdatedPacket(p.exp(), p.level(), p.expToNextLevel(), 0)));
            }
            default -> {}
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("소켓 연결: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Player player = sessionPlayers.remove(conn);
        if (player == null) {
            log.info("JOIN 전에 연결 종료: remote={}", conn.getRemoteSocketAddress());
            return;
        }
        GameMap map = world.map(player.mapId());
        if (map != null) {
            map.removePlayer(player.id());
            map.broadcast("PLAYER_LEAVE", new PlayerLeftPacket(player.id()));
        }
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
        gameLoop.start();
    }

    // --- 핸들러 ---

    private void handleJoin(PacketContext ctx) throws Exception {
        if (sessionPlayers.containsKey(ctx.conn())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "already joined"));
            return;
        }
        JoinRequest req = ctx.json().treeToValue(ctx.body(), JoinRequest.class);
        int id = playerIdSeq.getAndIncrement();
        String name = (req.name() == null || req.name().isBlank()) ? ("Player" + id) : req.name();

        GameMap map = world.defaultMap();
        Player player = new Player(id, name, ctx.conn(), map.id(), map.spawnX(), map.spawnY());

        sessionPlayers.put(ctx.conn(), player);
        map.addPlayer(player);

        WelcomePacket welcome = new WelcomePacket(
                id,
                player.toState(),
                map.othersOf(id).stream().map(Player::toState).toList(),
                map.monsters().stream().map(GameServer::toMonsterState).toList(),
                map.droppedItems().stream().map(GameServer::toItemState).toList()
        );
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "WELCOME", welcome));
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

        GameMap map = world.map(player.mapId());
        if (map == null) return;
        map.broadcastExcept(
                player.id(),
                "PLAYER_MOVE",
                new PlayerMovedPacket(player.id(), req.x(), req.y(), req.vx(), req.vy())
        );
    }

    private void handleChangeMap(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "join required"));
            return;
        }
        ChangeMapRequest req = ctx.json().treeToValue(ctx.body(), ChangeMapRequest.class);
        GameMap target = world.map(req.targetMap());
        if (target == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "unknown map: " + req.targetMap()));
            return;
        }
        if (target.id().equals(player.mapId())) {
            return; // 같은 맵 재진입 방지
        }

        // 1) 기존 맵에서 제거 + 그 맵 사용자에게 퇴장 알림
        GameMap current = world.map(player.mapId());
        if (current != null) {
            current.removePlayer(player.id());
            current.broadcast("PLAYER_LEAVE", new PlayerLeftPacket(player.id()));
        }

        // 2) 플레이어 상태 갱신 후 새 맵에 추가
        player.moveTo(target.id(), req.targetX(), req.targetY());
        target.addPlayer(player);

        // 3) 전환 당사자에게 새 맵 정보 전달
        MapChangedPacket resp = new MapChangedPacket(
                target.id(),
                player.x(),
                player.y(),
                target.othersOf(player.id()).stream().map(Player::toState).toList(),
                target.monsters().stream().map(GameServer::toMonsterState).toList(),
                target.droppedItems().stream().map(GameServer::toItemState).toList()
        );
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "MAP_CHANGED", resp));

        // 4) 새 맵 기존 사용자에게 입장 알림
        target.broadcastExcept(player.id(), "PLAYER_JOIN", new PlayerJoinedPacket(player.toState()));
    }

    private static MonsterState toMonsterState(Monster m) {
        return new MonsterState(m.id(), m.template(), m.x(), m.y(), m.hp(), m.maxHp());
    }

    private static DroppedItemState toItemState(DroppedItem d) {
        return new DroppedItemState(d.id(), d.templateId(), d.x(), d.y());
    }

    private static final long DROP_TTL_MS = 60_000;
    private static final double PICKUP_RANGE = 40;

    private void handlePickup(PacketContext ctx) {
        Player player = ctx.player();
        if (player == null) return;
        GameMap map = world.map(player.mapId());
        if (map == null) return;

        for (DroppedItem d : map.droppedItems()) {
            double dx = d.x() - player.x();
            double dy = d.y() - player.y();
            if (Math.abs(dx) <= PICKUP_RANGE && Math.abs(dy) <= PICKUP_RANGE) {
                if (map.takeDroppedItem(d.id()) == null) continue;
                player.inventory().add(d.templateId(), 1);
                player.connection().send(PacketEnvelope.wrap(json, "INVENTORY",
                        new InventoryPacket(player.inventory().snapshot())));
            }
        }
    }

    // --- 공격 ---

    private static final double ATTACK_RANGE_X = 70;
    // 플레이어 중심 기준으로 상/하 모두 검사하여 서 있는 몬스터도 충분히 포함.
    private static final double ATTACK_RANGE_Y_UP = 60;
    private static final double ATTACK_RANGE_Y_DOWN = 60;
    private static final int PLAYER_DAMAGE = 25;

    private void handleAttack(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "join required"));
            return;
        }
        AttackRequest req = ctx.json().treeToValue(ctx.body(), AttackRequest.class);
        GameMap map = world.map(player.mapId());
        if (map == null) return;

        String dir = (req.dir() == null || req.dir().isBlank()) ? player.facing() : req.dir();
        double px = player.x();
        double py = player.y();
        double hitMinX = dir.equals("left") ? px - ATTACK_RANGE_X : px;
        double hitMaxX = dir.equals("left") ? px : px + ATTACK_RANGE_X;
        double hitMinY = py - ATTACK_RANGE_Y_UP;
        double hitMaxY = py + ATTACK_RANGE_Y_DOWN;

        for (Monster m : map.monsters()) {
            if (m.isDead()) continue;
            if (m.x() < hitMinX || m.x() > hitMaxX) continue;
            if (m.y() < hitMinY || m.y() > hitMaxY) continue;

            int dmg = m.applyDamage(PLAYER_DAMAGE);
            map.broadcast("MONSTER_DAMAGED",
                    new MonsterDamagedPacket(m.id(), dmg, m.hp(), player.id()));
            if (m.isDead()) {
                SpawnPoint origin = map.findSpawnFor(m);
                map.killMonster(m, origin);
                map.broadcast("MONSTER_DIED", new MonsterDiedPacket(m.id()));
                int expReward = origin != null ? origin.expReward() : 0;
                eventBus.publish(new MonsterKilled(player, m, expReward));

                // 드롭 롤: 각 아이템은 독립 시행. 몬스터 위치 근처에 흩뿌림.
                if (origin != null && origin.dropTable() != null) {
                    int scatter = 0;
                    for (String itemId : origin.dropTable().roll()) {
                        double dropX = m.x() + (scatter - 1) * 16;
                        scatter++;
                        DroppedItem d = new DroppedItem(
                                world.itemIdSeq().getAndIncrement(),
                                itemId, dropX, m.y(), DROP_TTL_MS);
                        map.addDroppedItem(d);
                    }
                }
            }
        }
    }
}
