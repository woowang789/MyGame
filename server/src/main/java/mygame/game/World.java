package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 월드 = 모든 맵의 컨테이너 + 몬스터 ID 발급.
 */
public final class World {

    private static final double GROUND_Y = 544;
    private static final int SNAIL_HP = 50;
    private static final long SNAIL_RESPAWN_MS = 5000;

    private final Map<String, GameMap> maps = new ConcurrentHashMap<>();
    private final AtomicInteger monsterIdSeq = new AtomicInteger(1);

    public World(ObjectMapper json) {
        GameMap henesys = new GameMap("henesys", 80, 100, json, monsterIdSeq::getAndIncrement);
        GameMap ellinia = new GameMap("ellinia", 80, 480, json, monsterIdSeq::getAndIncrement);
        maps.put(henesys.id(), henesys);
        maps.put(ellinia.id(), ellinia);

        registerSnails(henesys, 2, 200, 700, 60);
        registerSnails(ellinia, 3, 160, 720, 50);
    }

    private void registerSnails(GameMap map, int count, double minX, double maxX, double speed) {
        double step = (maxX - minX) / (count + 1);
        for (int i = 1; i <= count; i++) {
            double spawnX = minX + step * i;
            map.registerSpawn(new SpawnPoint(
                    "snail", spawnX, GROUND_Y, minX, maxX, speed, SNAIL_HP, SNAIL_RESPAWN_MS
            ));
        }
    }

    public GameMap map(String id) { return maps.get(id); }
    public GameMap defaultMap() { return maps.get("henesys"); }
    public Collection<GameMap> maps() { return maps.values(); }
}
