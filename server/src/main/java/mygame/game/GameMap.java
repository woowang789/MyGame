package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.game.ai.IdleState;
import mygame.game.entity.Monster;
import mygame.game.entity.Player;
import mygame.network.PacketEnvelope;
import mygame.network.packets.Packets.MonsterMovedPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 플레이어·몬스터가 속하는 격리/브로드캐스트 단위.
 *
 * <p>Phase E 에서 몬스터 관리가 추가되었다. 게임 루프가 {@link #tick(long)} 을
 * 주기적으로 호출해 몬스터 상태를 갱신하고, 위치 변화가 있으면 맵 내부에
 * 브로드캐스트한다.
 */
public final class GameMap {

    private static final Logger log = LoggerFactory.getLogger(GameMap.class);
    /** 몬스터 이동 샘플 간격(ms). tick rate 와 독립적으로 네트워크 트래픽 제어. */
    private static final long MONSTER_SAMPLE_MS = 200;

    private final String id;
    private final double spawnX;
    private final double spawnY;
    private final ObjectMapper json;
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private final Map<Integer, Monster> monsters = new ConcurrentHashMap<>();

    private long sampleAccumulator = 0;

    public GameMap(String id, double spawnX, double spawnY, ObjectMapper json) {
        this.id = id;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.json = json;
    }

    public String id() { return id; }
    public double spawnX() { return spawnX; }
    public double spawnY() { return spawnY; }

    public void addPlayer(Player player) {
        players.put(player.id(), player);
        log.info("맵[{}] 입장: playerId={}, 현재 인원={}", id, player.id(), players.size());
    }

    public void removePlayer(int playerId) {
        Player removed = players.remove(playerId);
        if (removed != null) {
            log.info("맵[{}] 퇴장: playerId={}, 현재 인원={}", id, playerId, players.size());
        }
    }

    public Collection<Player> players() {
        return players.values();
    }

    public List<Player> othersOf(int playerId) {
        return players.values().stream()
                .filter(p -> p.id() != playerId)
                .toList();
    }

    // --- 몬스터 ---

    public void spawnMonster(Monster m) {
        monsters.put(m.id(), m);
        m.transitionTo(new IdleState());
        log.info("맵[{}] 몬스터 스폰: id={}, template={}", id, m.id(), m.template());
    }

    public Collection<Monster> monsters() {
        return monsters.values();
    }

    /**
     * 게임 루프가 호출. 모든 몬스터 상태를 갱신하고, 샘플 간격마다 위치를
     * 맵 내부 플레이어들에게 브로드캐스트한다.
     */
    public void tick(long dtMs) {
        for (Monster m : monsters.values()) {
            m.update(dtMs);
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

    /** 맵 내 모든 플레이어에게 전송. */
    public void broadcast(String type, Object payload) {
        String msg = PacketEnvelope.wrap(json, type, payload);
        for (Player p : players.values()) {
            trySend(p, msg);
        }
    }

    /** 지정한 플레이어를 제외하고 브로드캐스트. */
    public void broadcastExcept(int exceptPlayerId, String type, Object payload) {
        String msg = PacketEnvelope.wrap(json, type, payload);
        for (Player p : players.values()) {
            if (p.id() == exceptPlayerId) continue;
            trySend(p, msg);
        }
    }

    private void trySend(Player p, String msg) {
        try {
            if (p.connection().isOpen()) {
                p.connection().send(msg);
            }
        } catch (Exception e) {
            log.warn("전송 실패 playerId={}: {}", p.id(), e.getMessage());
        }
    }
}
