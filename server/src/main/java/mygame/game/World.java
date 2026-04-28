package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.game.entity.Npc;
import mygame.game.entity.Player;

/**
 * 월드 = 모든 맵의 컨테이너 + 몬스터/아이템 ID 발급.
 */
public final class World {

    private static final double GROUND_Y = 544;

    private final Map<String, GameMap> maps = new ConcurrentHashMap<>();
    private final AtomicInteger monsterIdSeq = new AtomicInteger(1);
    private final AtomicInteger itemIdSeq = new AtomicInteger(1);
    private final AtomicInteger npcIdSeq = new AtomicInteger(1);
    /** 전역 플레이어 이름 → Player. 귓속말(맵 경계 넘는 메시지) 라우팅에 사용. */
    private final Map<String, Player> playersByName = new ConcurrentHashMap<>();
    private volatile CombatListener combatListener = null;

    public void setCombatListener(CombatListener listener) {
        this.combatListener = listener;
        for (GameMap map : maps.values()) map.setCombatListener(listener);
    }

    public World(ObjectMapper json) {
        GameMap henesys = new GameMap("henesys", 80, 100, json, monsterIdSeq::getAndIncrement);
        GameMap ellinia = new GameMap("ellinia", 80, 480, json, monsterIdSeq::getAndIncrement);
        maps.put(henesys.id(), henesys);
        maps.put(ellinia.id(), ellinia);

        // 헤네시스: 초보용. 달팽이 + 나무 토막.
        registerBand(henesys, "snail", 2, 200, 700);
        registerBand(henesys, "stump", 2, 300, 650);

        // 엘리니아: 조금 더 어려운 혼합 구성.
        registerBand(ellinia, "blue_snail", 3, 160, 720);
        registerBand(ellinia, "orange_mushroom", 2, 220, 660);
        registerBand(ellinia, "red_snail", 1, 380, 500);

        // 헤네시스 잡화상 NPC. 스폰 지점 근처에 배치해 신규 캐릭터가 쉽게 만난다.
        henesys.registerNpc(new Npc(
                npcIdSeq.getAndIncrement(),
                "잡화상 페트라",
                160, GROUND_Y,
                "henesys_general"));
    }

    /**
     * 한 종을 구간 내에 균등 분포로 {@code count} 마리 배치.
     * 각 개체는 동일한 배회 구간([minX, maxX])을 공유한다.
     */
    private void registerBand(GameMap map, String templateId, int count,
                              double minX, double maxX) {
        double step = (maxX - minX) / (count + 1);
        for (int i = 1; i <= count; i++) {
            double spawnX = minX + step * i;
            map.registerSpawn(SpawnPoint.of(templateId, spawnX, GROUND_Y, minX, maxX));
        }
    }

    public AtomicInteger itemIdSeq() { return itemIdSeq; }

    public GameMap map(String id) { return maps.get(id); }
    public GameMap defaultMap() { return maps.get("henesys"); }
    public Collection<GameMap> maps() { return maps.values(); }

    public void registerPlayer(Player p) { playersByName.put(p.name(), p); }
    public void unregisterPlayer(Player p) { playersByName.remove(p.name(), p); }
    public Player playerByName(String name) { return playersByName.get(name); }
}
