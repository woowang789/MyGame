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
import mygame.auth.AuthService;
import mygame.auth.AuthService.AuthFailure;
import mygame.auth.AuthService.AuthResult;
import mygame.auth.AuthService.AuthSuccess;
import mygame.db.AccountRepository;
import mygame.db.Database;
import mygame.db.JdbcAccountRepository;
import mygame.db.JdbcPlayerRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.network.packets.Packets.AuthResponse;
import mygame.network.packets.Packets.LoginRequest;
import mygame.network.packets.Packets.RegisterRequest;
import mygame.game.SpawnPoint;
import mygame.game.item.DroppedItem;
import mygame.network.packets.Packets.AttackRequest;
import mygame.network.packets.Packets.ChatMessage;
import mygame.network.packets.Packets.ChatRequest;
import mygame.network.packets.Packets.DroppedItemState;
import mygame.network.packets.Packets.EquipRequest;
import mygame.network.packets.Packets.EquipmentPacket;
import mygame.network.packets.Packets.InventoryPacket;
import mygame.network.packets.Packets.ExpUpdatedPacket;
import mygame.network.packets.Packets.LevelUpPacket;
import mygame.network.packets.Packets.StatsPacket;
import mygame.network.packets.Packets.UnequipRequest;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemRegistry;
import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;
import mygame.game.skill.SkillRegistry;
import mygame.network.packets.Packets.SkillUsedPacket;
import mygame.network.packets.Packets.UseSkillRequest;
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
    private final Database database;
    private final PlayerRepository playerRepo;
    private final AccountRepository accountRepo;
    private final AuthService authService;
    private final PacketDispatcher dispatcher = new PacketDispatcher(json);

    private final Map<WebSocket, Player> sessionPlayers = new ConcurrentHashMap<>();
    /**
     * Phase L: 로그인 성공한 소켓 → accountId 매핑. JOIN 패킷은 이 맵에 있는 소켓만 허용.
     * 로그아웃/연결 종료 시 제거.
     */
    private final Map<WebSocket, Long> authenticatedAccounts = new ConcurrentHashMap<>();
    /** 같은 계정의 중복 접속을 막기 위한 역인덱스. 값은 현재 접속 중인 소켓. */
    private final Map<Long, WebSocket> accountSessions = new ConcurrentHashMap<>();
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
        this.authService = new AuthService(accountRepo);
        registerHandlers();
    }

    private void registerHandlers() {
        // Phase L — 인증은 JOIN 이전에 선행. REGISTER 는 성공해도 자동 로그인은 하지 않는다.
        dispatcher.register("LOGIN", this::handleLogin);
        dispatcher.register("REGISTER", this::handleRegister);
        dispatcher.register("JOIN", this::handleJoin);
        dispatcher.register("MOVE", this::handleMove);
        dispatcher.register("CHANGE_MAP", this::handleChangeMap);
        dispatcher.register("ATTACK", this::handleAttack);
        dispatcher.register("PICKUP", this::handlePickup);
        dispatcher.register("CHAT", this::handleChat);
        dispatcher.register("EQUIP", this::handleEquip);
        dispatcher.register("UNEQUIP", this::handleUnequip);
        dispatcher.register("USE_SKILL", this::handleUseSkill);

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
        // Phase L: 인증 정보 항상 해제(JOIN 전 종료 포함)
        Long acctId = authenticatedAccounts.remove(conn);
        if (acctId != null) accountSessions.remove(acctId, conn);

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
                        player.inventory().snapshot(),
                        equipmentSnapshotAsStringMap(player));
                log.info("플레이어 저장: name={}, lv={}, exp={}", player.name(), player.level(), player.exp());
            } catch (Exception e) {
                log.error("플레이어 저장 실패: name={}", player.name(), e);
            }
        }
    }

    /** Equipment 의 EnumMap 을 문자열 키 맵(DB · JSON 친화)으로 변환. */
    private static Map<String, String> equipmentSnapshotAsStringMap(Player player) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Map.Entry<EquipSlot, String> e : player.equipment().snapshot().entrySet()) {
            m.put(e.getKey().name(), e.getValue());
        }
        return m;
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

    private void handleRegister(PacketContext ctx) throws Exception {
        RegisterRequest req = ctx.json().treeToValue(ctx.body(), RegisterRequest.class);
        AuthResult result = authService.register(req.username(), req.password());
        if (result instanceof AuthFailure f) {
            ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "AUTH",
                    new AuthResponse(false, f.message(), 0, "")));
            return;
        }
        // 등록 성공은 단순히 성공 메시지만 전달. 클라이언트가 뒤이어 LOGIN 을 보낸다.
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "AUTH",
                new AuthResponse(true, "", 0, req.username())));
    }

    private void handleLogin(PacketContext ctx) throws Exception {
        LoginRequest req = ctx.json().treeToValue(ctx.body(), LoginRequest.class);
        if (authenticatedAccounts.containsKey(ctx.conn())) {
            ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "AUTH",
                    new AuthResponse(false, "이미 로그인 상태입니다.", 0, "")));
            return;
        }
        AuthResult result = authService.login(req.username(), req.password());
        if (result instanceof AuthFailure f) {
            ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "AUTH",
                    new AuthResponse(false, f.message(), 0, "")));
            return;
        }
        long accountId = ((AuthSuccess) result).account().id();
        // 계정 1개 = 세션 1개 보장. 기존 소켓이 있으면 끊는다.
        WebSocket prev = accountSessions.put(accountId, ctx.conn());
        if (prev != null && prev != ctx.conn() && prev.isOpen()) {
            prev.send(PacketEnvelope.error(ctx.json(), "다른 위치에서 로그인되어 연결이 종료됩니다."));
            prev.close();
        }
        authenticatedAccounts.put(ctx.conn(), accountId);
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "AUTH",
                new AuthResponse(true, "", accountId, req.username())));
    }

    private void handleJoin(PacketContext ctx) throws Exception {
        // Phase L: 인증 선행 필수
        Long accountId = authenticatedAccounts.get(ctx.conn());
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
        data.items().forEach(player.inventory()::add);
        // 장비 복원: 슬롯별로 equip() 호출(원래 로직과 동일한 경로를 타게 해 불일치 제거).
        data.equipment().forEach((slotName, itemId) -> player.equipment().equip(itemId));
        // MP 는 세션 시작 시 최대로 충전. (HP/MP 영속화는 이후 Phase 에서.)
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
        map.broadcastExcept(id, "PLAYER_JOIN", new PlayerJoinedPacket(player.toState()));

        // 복원된 진행도/인벤토리/장비/최종 스탯을 당사자에게 전달
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "PLAYER_EXP",
                new ExpUpdatedPacket(player.exp(), player.level(), player.expToNextLevel(), 0)));
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        sendEquipmentAndStats(player);
    }

    // --- Phase I 장비 핸들러 ---

    private void handleEquip(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        EquipRequest req = ctx.json().treeToValue(ctx.body(), EquipRequest.class);
        String itemId = req.templateId();
        if (itemId == null || !ItemRegistry.isEquipment(itemId)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "장비 아이템이 아닙니다."));
            return;
        }
        if (!player.inventory().remove(itemId, 1)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "인벤토리에 해당 장비가 없습니다."));
            return;
        }
        String replaced = player.equipment().equip(itemId);
        // 기존 장비는 인벤토리로 되돌린다(교체). 변경이 원자적이도록 이 순서가 중요.
        if (replaced != null) player.inventory().add(replaced, 1);
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        sendEquipmentAndStats(player);
    }

    private void handleUnequip(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        UnequipRequest req = ctx.json().treeToValue(ctx.body(), UnequipRequest.class);
        EquipSlot slot;
        try {
            slot = EquipSlot.valueOf(req.slot());
        } catch (IllegalArgumentException e) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "알 수 없는 슬롯: " + req.slot()));
            return;
        }
        String removed = player.equipment().unequip(slot);
        if (removed == null) return;
        player.inventory().add(removed, 1);
        ctx.conn().send(PacketEnvelope.wrap(ctx.json(), "INVENTORY",
                new InventoryPacket(player.inventory().snapshot())));
        sendEquipmentAndStats(player);
    }

    // --- Phase M 스킬 핸들러 ---

    private void handleUseSkill(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        UseSkillRequest req = ctx.json().treeToValue(ctx.body(), UseSkillRequest.class);
        String skillId = req.skillId();
        if (skillId == null || !SkillRegistry.exists(skillId)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "알 수 없는 스킬: " + skillId));
            return;
        }
        Skill skill = SkillRegistry.get(skillId);

        // 직업 태그 검증. 초보자는 BEGINNER 스킬만 사용 가능. 전직 Phase 확장 지점.
        if (!Skill.JOB_BEGINNER.equals(skill.job())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "아직 배우지 않은 스킬입니다."));
            return;
        }

        long now = System.currentTimeMillis();
        if (!player.tryActivateSkill(skill.id(), skill.cooldownMs(), now)) {
            // 쿨다운 중. 스팸 방지를 위해 조용히 드롭하고 클라 HUD 가 쿨다운을 표시.
            return;
        }
        if (!player.spendMp(skill.mpCost())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "MP 가 부족합니다."));
            return;
        }

        GameMap map = world.map(player.mapId());
        if (map == null) return;
        String dir = (req.dir() == null || req.dir().isBlank()) ? player.facing() : req.dir();
        SkillContext skillCtx = SkillContext.of(player, map, dir, now);
        skill.apply(skillCtx);

        // 이펙트용 브로드캐스트 먼저(근접/원거리 모든 스킬 공통).
        map.broadcast("SKILL_USED", new SkillUsedPacket(player.id(), skill.id(), dir));

        // 몬스터 피해 결과를 기존 MONSTER_DAMAGED 패킷으로 재활용.
        for (var hit : skillCtx.outcome().hits()) {
            map.broadcast("MONSTER_DAMAGED",
                    new MonsterDamagedPacket(hit.monsterId(), hit.damage(), hit.remainingHp(), player.id()));
            if (hit.killed()) {
                Monster m = findMonsterById(map, hit.monsterId());
                if (m == null) continue;
                SpawnPoint origin = map.findSpawnFor(m);
                map.killMonster(m, origin);
                map.broadcast("MONSTER_DIED", new MonsterDiedPacket(m.id()));
                int expReward = origin != null ? origin.expReward() : 0;
                eventBus.publish(new MonsterKilled(player, m, expReward));
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

        // MP / 쿨다운 상태 동기화. 스탯 패킷이 현재 mp 를 포함하므로 한 번 보내면 된다.
        sendStats(player);
    }

    private static Monster findMonsterById(GameMap map, int monsterId) {
        for (Monster m : map.monsters()) {
            if (m.id() == monsterId) return m;
        }
        return null;
    }

    /** 본인에게 장비 스냅샷과 최종 스탯을 함께 전달. 공격 피해량 등 UI 에 영향. */
    private void sendEquipmentAndStats(Player player) {
        player.connection().send(PacketEnvelope.wrap(json, "EQUIPMENT",
                new EquipmentPacket(player.id(), equipmentSnapshotAsStringMap(player))));
        sendStats(player);
    }

    /** 최종 스탯 + 현재 MP 를 본인에게 전달. 스킬 사용·장비 변경 시 모두 호출. */
    private void sendStats(Player player) {
        mygame.game.stat.Stats s = player.effectiveStats();
        player.connection().send(PacketEnvelope.wrap(json, "STATS",
                new StatsPacket(s.maxHp(), s.maxMp(), s.attack(), s.speed(), player.mp())));
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

    private static final int MAX_CHAT_LEN = 200;

    private void handleChat(PacketContext ctx) throws Exception {
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

            // Phase I: 데미지 = 최종 스탯의 attack. Decorator 체인이 레벨 + 장비 보너스를 합산.
            int attack = player.effectiveStats().attack();
            int dmg = m.applyDamage(attack);
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
