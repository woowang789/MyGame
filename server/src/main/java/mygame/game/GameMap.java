package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.game.entity.Player;
import mygame.network.PacketEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 플레이어 격리·브로드캐스트 단위.
 *
 * <p>메이플스토리의 "맵" 과 같은 개념: 같은 맵에 속한 플레이어들끼리만
 * 서로의 이동·이벤트를 공유한다. 동일 월드라도 다른 맵에 있으면 패킷이
 * 오가지 않아 네트워크 부하가 맵 단위로 분산된다.
 *
 * <p>동시성: {@link #players} 는 여러 WebSocket 스레드에서 추가/삭제되므로
 * {@link ConcurrentHashMap} 을 쓴다. 브로드캐스트 시 순회는
 * {@code ConcurrentHashMap} 의 약한 일관성 iterator 를 신뢰한다.
 */
public final class GameMap {

    private static final Logger log = LoggerFactory.getLogger(GameMap.class);

    private final String id;
    private final double spawnX;
    private final double spawnY;
    private final ObjectMapper json;
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();

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

    /** 맵 내 모든 플레이어에게 전송. */
    public void broadcast(String type, Object payload) {
        String msg = PacketEnvelope.wrap(json, type, payload);
        for (Player p : players.values()) {
            trySend(p, msg);
        }
    }

    /** 지정한 플레이어를 제외하고 브로드캐스트 (이동 알림 등에서 본인 스킵용). */
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
