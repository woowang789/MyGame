package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.game.entity.Monster;

/**
 * 월드는 모든 맵의 컨테이너이자 몬스터 ID 발급기.
 */
public final class World {

    /** 클라이언트 타일맵의 ground Y 와 일치 (row 28 * 20px = 560, 스프라이트 중심 보정 -16). */
    private static final double GROUND_Y = 544;

    private final Map<String, GameMap> maps = new ConcurrentHashMap<>();
    private final AtomicInteger monsterIdSeq = new AtomicInteger(1);

    public World(ObjectMapper json) {
        GameMap henesys = new GameMap("henesys", 80, 100, json);
        GameMap ellinia = new GameMap("ellinia", 80, 480, json);
        maps.put(henesys.id(), henesys);
        maps.put(ellinia.id(), ellinia);

        // Phase E: 초기 몬스터 스폰.
        spawnSnails(henesys, 2, 200, 700, 60);
        spawnSnails(ellinia, 3, 160, 720, 50);
    }

    private void spawnSnails(GameMap map, int count, double minX, double maxX, double speed) {
        double step = (maxX - minX) / (count + 1);
        for (int i = 1; i <= count; i++) {
            double spawnX = minX + step * i;
            map.spawnMonster(new Monster(
                    monsterIdSeq.getAndIncrement(),
                    "snail",
                    spawnX, GROUND_Y, minX, maxX, speed
            ));
        }
    }

    public GameMap map(String id) {
        return maps.get(id);
    }

    public GameMap defaultMap() {
        return maps.get("henesys");
    }

    public Collection<GameMap> maps() {
        return maps.values();
    }
}
