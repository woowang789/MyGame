package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import mygame.game.ai.IdleState;
import mygame.game.entity.Monster;
import mygame.game.entity.Npc;
import mygame.game.entity.Player;
import mygame.game.item.DroppedItem;
import mygame.network.PacketEnvelope;
import mygame.network.packets.Packets.DroppedItemState;
import mygame.network.packets.Packets.ItemDroppedPacket;
import mygame.network.packets.Packets.ItemRemovedPacket;
import mygame.network.packets.Packets.MonsterMovedPacket;
import mygame.network.packets.Packets.MonsterSpawnedPacket;
import mygame.network.packets.Packets.MonsterState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 플레이어·몬스터 격리 단위. Phase F 에서 몬스터 사망·리스폰 로직을 맡는다.
 */
public final class GameMap {

    private static final Logger log = LoggerFactory.getLogger(GameMap.class);
    /**
     * 몬스터 위치 브로드캐스트 주기. 너무 길면(5Hz 급) 클라 보간이 새 샘플 전에 수렴해
     * 끊겨 보인다. 10Hz 정도가 네트워크 비용 대비 움직임이 매끄럽다.
     */
    private static final long MONSTER_SAMPLE_MS = 100;

    /** 플레이어-몬스터 접촉 판정 박스. 간단한 AABB. */
    private static final double CONTACT_RANGE_X = 36;
    private static final double CONTACT_RANGE_Y = 50;
    /**
     * 피격 후 무적 시간. 몬스터 접촉이 잦은 지역에서 체력이 한 번에 쓸리지 않도록
     * 충분히 길게 잡는다. 클라이언트 깜빡임 연출 길이와 맞춰야 시각적으로 자연스럽다.
     */
    private static final long IFRAME_MS = 1500;

    private final String id;
    private final double spawnX;
    private final double spawnY;
    private final ObjectMapper json;
    private final IntSupplier monsterIdGen;
    private volatile CombatListener combatListener = null;
    /**
     * (playerId, monsterId) → 마지막 피격 시각. 몬스터 공격 간격 관리.
     *
     * <p>키는 두 32비트 정수를 한 long 으로 인코딩한다 — Map<Pair> 보다 GC·해시 비용이 낮다.
     * 메모리 누수를 막기 위해 플레이어 퇴장({@link #removePlayer}) · 몬스터 사망
     * ({@link #killMonster}) 시 해당 id 가 포함된 키를 cleanup 한다.
     */
    private final Map<Long, Long> lastContactHitAt = new ConcurrentHashMap<>();

    public void setCombatListener(CombatListener listener) { this.combatListener = listener; }

    static long pairKey(int playerId, int monsterId) {
        return ((long) playerId << 32) | (monsterId & 0xFFFFFFFFL);
    }

    static int extractPlayerId(long key) {
        return (int) (key >>> 32);
    }

    static int extractMonsterId(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private final Map<Integer, Monster> monsters = new ConcurrentHashMap<>();
    private final List<SpawnPoint> spawnPoints = new ArrayList<>();
    private final ConcurrentLinkedQueue<PendingRespawn> pendingRespawns = new ConcurrentLinkedQueue<>();
    private final Map<Integer, DroppedItem> items = new ConcurrentHashMap<>();
    /** 맵 내 NPC. 정적 데이터라 단순 List 로 충분 (수정 빈도 낮음). */
    private final List<Npc> npcs = new ArrayList<>();

    private long sampleAccumulator = 0;

    public GameMap(String id, double spawnX, double spawnY, ObjectMapper json, IntSupplier monsterIdGen) {
        this.id = id;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.json = json;
        this.monsterIdGen = monsterIdGen;
    }

    public String id() { return id; }
    public double spawnX() { return spawnX; }
    public double spawnY() { return spawnY; }

    public void addPlayer(Player player) {
        players.put(player.id(), player);
        log.info("맵[{}] 입장: playerId={}, 현재 인원={}", id, player.id(), players.size());
    }

    public void removePlayer(int playerId) {
        if (players.remove(playerId) != null) {
            log.info("맵[{}] 퇴장: playerId={}, 현재 인원={}", id, playerId, players.size());
            // 이 플레이어의 접촉 기록 제거 — 키 상위 32비트가 playerId.
            lastContactHitAt.keySet().removeIf(k -> extractPlayerId(k) == playerId);
        }
    }

    public Collection<Player> players() { return players.values(); }

    public List<Player> othersOf(int playerId) {
        return players.values().stream().filter(p -> p.id() != playerId).toList();
    }

    // --- 몬스터 ---

    /** 스폰 포인트 등록 + 즉시 1마리 스폰. */
    public void registerSpawn(SpawnPoint point) {
        spawnPoints.add(point);
        Monster m = point.create(monsterIdGen);
        addMonster(m);
    }

    private void addMonster(Monster m) {
        monsters.put(m.id(), m);
        m.transitionTo(new IdleState());
        log.info("맵[{}] 몬스터 스폰: id={}, template={}", id, m.id(), m.template());
    }

    public Monster monster(int id) { return monsters.get(id); }
    public Collection<Monster> monsters() { return monsters.values(); }

    // --- NPC ---

    /** 맵에 NPC 등록. World 초기화에서만 호출되므로 동시성 가드는 불필요. */
    public void registerNpc(Npc npc) {
        npcs.add(npc);
        log.info("맵[{}] NPC 등록: id={}, name={}, shopId={}",
                id, npc.id(), npc.name(), npc.shopId());
    }

    public List<Npc> npcs() { return List.copyOf(npcs); }

    /** shopId 로 NPC 조회. 상점 거래 시 거리 검증 등에 사용. */
    public Npc findNpcByShopId(String shopId) {
        for (Npc n : npcs) {
            if (shopId.equals(n.shopId())) return n;
        }
        return null;
    }

    // --- 드롭 아이템 ---

    public void addDroppedItem(DroppedItem d) {
        items.put(d.id(), d);
        broadcast("ITEM_DROPPED", new ItemDroppedPacket(
                new DroppedItemState(d.id(), d.templateId(), d.x(), d.y())));
    }

    public DroppedItem takeDroppedItem(int itemId) {
        DroppedItem removed = items.remove(itemId);
        if (removed != null) {
            broadcast("ITEM_REMOVED", new ItemRemovedPacket(itemId));
        }
        return removed;
    }

    public Collection<DroppedItem> droppedItems() { return items.values(); }

    /** 사망 처리: 맵에서 제거 + 리스폰 예약 + 브로드캐스트(호출자 책임). */
    public void killMonster(Monster m, SpawnPoint origin) {
        int monsterId = m.id();
        monsters.remove(monsterId);
        // 이 몬스터에 대한 모든 플레이어 접촉 기록 제거. 리스폰 시 새 monsterId 가
        // 발급되므로 이전 키는 다시 쓰일 일 없이 영구히 남는다 — 누수 차단.
        lastContactHitAt.keySet().removeIf(k -> extractMonsterId(k) == monsterId);
        if (origin != null) {
            long at = System.currentTimeMillis() + origin.respawnDelayMs();
            pendingRespawns.add(new PendingRespawn(at, origin));
        }
    }

    /** 테스트용: lastContactHitAt 의 현재 항목 수. 누수 회귀 검증에 쓰인다. */
    int contactHitSize() {
        return lastContactHitAt.size();
    }

    /** 테스트용: (playerId, monsterId) 페어의 접촉 기록을 직접 주입. */
    void putContactHitForTest(int playerId, int monsterId, long now) {
        lastContactHitAt.put(pairKey(playerId, monsterId), now);
    }

    /** 몬스터 id 로 원본 스폰 포인트 찾기. 단순 매칭: x 범위 기반. */
    public SpawnPoint findSpawnFor(Monster m) {
        for (SpawnPoint sp : spawnPoints) {
            if (sp.template().equals(m.template())
                    && m.minX() == sp.minX() && m.maxX() == sp.maxX()) {
                return sp;
            }
        }
        return null;
    }

    public void tick(long dtMs) {
        for (Monster m : monsters.values()) m.update(dtMs);

        // 피격 판정: 살아있는 플레이어 × 공격형 살아있는 몬스터의 AABB 겹침.
        long tickNow = System.currentTimeMillis();
        for (Player p : players.values()) {
            if (p.isDead() || p.isInvulnerable(tickNow)) continue;
            for (Monster m : monsters.values()) {
                if (m.isDead() || m.attackDamage() <= 0) continue;
                if (Math.abs(m.x() - p.x()) > CONTACT_RANGE_X) continue;
                if (Math.abs(m.y() - p.y()) > CONTACT_RANGE_Y) continue;
                // 공격 간격 체크: 같은 몬스터가 너무 자주 때리지 않도록.
                long key = pairKey(p.id(), m.id());
                Long last = lastContactHitAt.get(key);
                if (last != null && tickNow - last < m.attackIntervalMs()) continue;
                int applied = p.takeDamage(m.attackDamage(), tickNow, IFRAME_MS);
                if (applied <= 0) continue;
                lastContactHitAt.put(key, tickNow);
                CombatListener cl = combatListener;
                if (cl != null) cl.onPlayerDamaged(p, m, applied);
                if (p.isDead() && cl != null) {
                    cl.onPlayerDied(p, m);
                    break; // 이번 플레이어는 더 맞을 수 없음.
                }
            }
        }

        // 드롭 아이템 만료(기본 60초) 제거
        long expireNow = System.currentTimeMillis();
        items.values().removeIf(d -> {
            if (!d.isExpired(expireNow)) return false;
            broadcast("ITEM_REMOVED", new ItemRemovedPacket(d.id()));
            return true;
        });

        // 리스폰 처리
        long now = System.currentTimeMillis();
        while (!pendingRespawns.isEmpty()) {
            PendingRespawn head = pendingRespawns.peek();
            if (head == null || head.at() > now) break;
            pendingRespawns.poll();
            Monster fresh = head.point().create(monsterIdGen);
            addMonster(fresh);
            broadcast("MONSTER_SPAWN", new MonsterSpawnedPacket(
                    new MonsterState(fresh.id(), fresh.template(), fresh.x(), fresh.y(),
                            fresh.hp(), fresh.maxHp())));
        }

        sampleAccumulator += dtMs;
        if (sampleAccumulator >= MONSTER_SAMPLE_MS) {
            sampleAccumulator = 0;
            broadcastMonsterPositions();
        }
    }

    private void broadcastMonsterPositions() {
        if (players.isEmpty() || monsters.isEmpty()) return;
        List<String> packets = new ArrayList<>(monsters.size());
        for (Monster m : monsters.values()) {
            packets.add(PacketEnvelope.wrap(json, "MONSTER_MOVE",
                    new MonsterMovedPacket(m.id(), m.x(), m.vx())));
        }
        for (Player p : players.values()) {
            for (String msg : packets) trySend(p, msg);
        }
    }

    public void broadcast(String type, Object payload) {
        String msg = PacketEnvelope.wrap(json, type, payload);
        for (Player p : players.values()) trySend(p, msg);
    }

    public void broadcastExcept(int exceptPlayerId, String type, Object payload) {
        String msg = PacketEnvelope.wrap(json, type, payload);
        for (Player p : players.values()) {
            if (p.id() == exceptPlayerId) continue;
            trySend(p, msg);
        }
    }

    private void trySend(Player p, String msg) {
        try {
            if (p.connection().isOpen()) p.connection().send(msg);
        } catch (Exception e) {
            log.warn("전송 실패 playerId={}: {}", p.id(), e.getMessage());
        }
    }

    private record PendingRespawn(long at, SpawnPoint point) {}
}
