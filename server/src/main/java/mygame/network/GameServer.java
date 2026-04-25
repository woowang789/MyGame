package mygame.network;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.auth.AuthService;
import mygame.db.AccountRepository;
import mygame.db.Database;
import mygame.db.JdbcAccountRepository;
import mygame.db.JdbcPlayerRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.game.CombatService;
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
import mygame.game.item.DroppedItem;
import mygame.game.item.ItemRegistry;
import mygame.game.skill.SkillRegistry;
import mygame.network.packets.Packets.ChangeMapRequest;
import mygame.network.packets.Packets.DroppedItemState;
import mygame.network.packets.Packets.ExpUpdatedPacket;
import mygame.network.packets.Packets.InventoryPacket;
import mygame.network.packets.Packets.JoinRequest;
import mygame.network.packets.Packets.LevelUpPacket;
import mygame.network.packets.Packets.MapChangedPacket;
import mygame.network.packets.Packets.MesoUpdatedPacket;
import mygame.network.packets.Packets.MetaPacket;
import mygame.network.packets.Packets.SkillMetaEntry;
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
 *
 * <p>핸들러는 도메인별로 {@link ChatHandler}/{@link InventoryHandler}/{@link CombatHandler} 로 분리했고,
 * 이 클래스는 세션 수명 주기(JOIN/ LEAVE/ MOVE/ CHANGE_MAP) 와 DB 연동만 책임진다.
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
    private final CombatService combatService = new CombatService(world, eventBus);
    private final Database database;
    private final PlayerRepository playerRepo;
    private final AccountRepository accountRepo;
    private final AuthSessions auth;
    private final PacketDispatcher dispatcher = new PacketDispatcher(json);
    private final SessionNotifier notifier = new SessionNotifier(json);
    private final ChatHandler chatHandler = new ChatHandler(world);
    private final InventoryHandler inventoryHandler = new InventoryHandler(world, json, notifier);
    private final CombatHandler combatHandler = new CombatHandler(world, combatService, notifier);

    private final Map<WebSocket, Player> sessionPlayers = new ConcurrentHashMap<>();
    private final AtomicInteger playerIdSeq = new AtomicInteger(1);

    public GameServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        // ~/mygame-data/mygame DB (파일 기반, 재시작 후 영속)
        String home = System.getProperty("user.home");
        this.database = new Database(
                "jdbc:h2:file:" + home + "/mygame-data/mygame;AUTO_SERVER=TRUE",
                "sa", "");
        this.database.runMigrations();
        this.playerRepo = new JdbcPlayerRepository(database);
        this.accountRepo = new JdbcAccountRepository(database);
        this.auth = new AuthSessions(new AuthService(accountRepo), json);
        world.setCombatListener(new PlayerCombatHandler(world, notifier::sendStats));
        registerHandlers();
    }

    private void registerHandlers() {
        // Phase L — 인증은 JOIN 이전에 선행. REGISTER 는 성공해도 자동 로그인은 하지 않는다.
        dispatcher.register("LOGIN", auth::handleLogin);
        dispatcher.register("REGISTER", auth::handleRegister);
        dispatcher.register("JOIN", this::handleJoin);
        dispatcher.register("MOVE", this::handleMove);
        dispatcher.register("CHANGE_MAP", this::handleChangeMap);
        // ATTACK 패킷 제거: 기본 공격은 USE_SKILL{skillId:"basic_attack"} 한 경로로 통합.
        dispatcher.register("USE_SKILL", combatHandler::handleUseSkill);
        dispatcher.register("PICKUP", inventoryHandler::handlePickup);
        dispatcher.register("EQUIP", inventoryHandler::handleEquip);
        dispatcher.register("UNEQUIP", inventoryHandler::handleUnequip);
        dispatcher.register("USE_ITEM", inventoryHandler::handleUseItem);
        dispatcher.register("DROP_ITEM", inventoryHandler::handleDropItem);
        dispatcher.register("CHAT", chatHandler::handle);

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
        auth.clear(conn); // 인증 정보 항상 해제(JOIN 전 종료 포함)
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
        world.unregisterPlayer(player);

        // DB 저장. 실패해도 연결 종료는 계속 진행.
        if (player.dbId() > 0) {
            try {
                playerRepo.save(
                        player.dbId(),
                        player.level(),
                        player.exp(),
                        player.meso(),
                        player.inventory().snapshot(),
                        SessionNotifier.equipmentSnapshotAsStringMap(player));
                log.info("플레이어 저장: name={}, lv={}, exp={}", player.name(), player.level(), player.exp());
            } catch (Exception e) {
                log.error("플레이어 저장 실패: name={}", player.name(), e);
            }
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

    // --- 세션 수명 주기 핸들러 ---

    private void handleJoin(PacketContext ctx) throws Exception {
        // Phase L: 인증 선행 필수
        Long accountId = auth.accountIdOf(ctx.conn());
        if (accountId == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "로그인이 필요합니다."));
            return;
        }
        if (sessionPlayers.containsKey(ctx.conn())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "already joined"));
            return;
        }
        JoinRequest req = ctx.json().treeToValue(ctx.body(), JoinRequest.class);

        // 해당 계정의 캐릭터가 이미 있으면 그 이름을 쓰고, 없으면 요청된 이름으로 신규 생성.
        PlayerData data = playerRepo.findByAccountId(accountId).orElse(null);
        String name;
        if (data != null) {
            name = data.name();
        } else {
            name = (req.name() == null || req.name().isBlank())
                    ? ("Player" + accountId) : req.name();
            // 다른 계정이 이미 이 이름으로 캐릭터를 가지고 있으면 거부(players.name UNIQUE).
            if (playerRepo.findByName(name).isPresent()) {
                ctx.conn().send(PacketEnvelope.error(ctx.json(),
                        "이미 사용 중인 캐릭터 이름입니다: " + name));
                return;
            }
            data = playerRepo.create(name, accountId);
        }

        int id = playerIdSeq.getAndIncrement();
        GameMap map = world.defaultMap();
        Player player = new Player(id, name, ctx.conn(), map.id(), map.spawnX(), map.spawnY());

        player.setDbId(data.id());
        player.restoreProgress(data.level(), data.exp());
        player.restoreMeso(data.meso());
        data.items().forEach(player.inventory()::add);
        // 장비 복원: 슬롯별로 equip() 호출(원래 로직과 동일한 경로를 타게 해 불일치 제거).
        data.equipment().forEach((slotName, itemId) -> player.equipment().equip(itemId));
        // HP/MP 는 세션 시작 시 최대로 충전. (HP/MP 영속화는 이후 Phase 에서.)
        player.fullHealHp();
        player.fullHealMp();

        sessionPlayers.put(ctx.conn(), player);
        map.addPlayer(player);
        world.registerPlayer(player);

        WelcomePacket welcome = new WelcomePacket(
                id,
                player.toState(),
                map.othersOf(id).stream().map(Player::toState).toList(),
                map.monsters().stream().map(GameServer::toMonsterState).toList(),
                map.droppedItems().stream().map(GameServer::toItemState).toList()
        );
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "WELCOME", welcome));
        // META: 정적 레지스트리(아이템·스킬) 를 단일 진실 원천으로 내려 보낸다.
        // 클라 HUD 의 장비 판별, 스킬 쿨다운 HUD 가 이 패킷으로 초기화된다.
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "META", buildMeta()));
        map.broadcastExcept(id, "PLAYER_JOIN", new PlayerJoinedPacket(player.toState()));

        // 복원된 진행도/인벤토리/장비/최종 스탯을 당사자에게 전달
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "PLAYER_EXP",
                new ExpUpdatedPacket(player.exp(), player.level(), player.expToNextLevel(), 0)));
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "MESO",
                new MesoUpdatedPacket(player.meso(), 0)));
        notifier.sendEquipmentAndStats(player);
    }

    private void handleMove(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "join required"));
            return;
        }
        // 사망 중에는 위치 변경 불가. 부활 패킷이 권위적으로 스폰 지점에 세울 때까지 고정.
        if (player.isDead()) return;
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
        if (player.isDead()) return;
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

    private static MetaPacket buildMeta() {
        var skills = SkillRegistry.all().stream()
                .map(s -> new SkillMetaEntry(s.id(), s.name(), s.mpCost(), s.cooldownMs()))
                .toList();
        return new MetaPacket(ItemRegistry.equipmentIds(), skills);
    }

    private static MonsterState toMonsterState(Monster m) {
        return new MonsterState(m.id(), m.template(), m.x(), m.y(), m.hp(), m.maxHp());
    }

    private static DroppedItemState toItemState(DroppedItem d) {
        return new DroppedItemState(d.id(), d.templateId(), d.x(), d.y());
    }
}
