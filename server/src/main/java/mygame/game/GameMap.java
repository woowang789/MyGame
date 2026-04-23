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
    private static final long MONSTER_SAMPLE_MS = 200;

    private final String id;
    private final double spawnX;
    private final double spawnY;
    private final ObjectMapper json;
    private final IntSupplier monsterIdGen;
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private final Map<Integer, Monster> monsters = new ConcurrentHashMap<>();
    private final List<SpawnPoint> spawnPoints = new ArrayList<>();
    private final ConcurrentLinkedQueue<PendingRespawn> pendingRespawns = new ConcurrentLinkedQueue<>();
    private final Map<Integer, DroppedItem> items = new ConcurrentHashMap<>();

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
        monsters.remove(m.id());
        if (origin != null) {
            long at = System.currentTimeMillis() + origin.respawnDelayMs();
            pendingRespawns.add(new PendingRespawn(at, origin));
        }
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
