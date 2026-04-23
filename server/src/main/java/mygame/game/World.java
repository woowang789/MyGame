package mygame.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 월드는 모든 맵의 컨테이너.
 *
 * <p>현재 Phase C 에서는 "henesys" 단일 맵만 존재한다. Phase D 에서
 * 포털로 여러 맵을 오가게 되며, Phase L 에서 채널 분리로 확장된다.
 */
public final class World {

    private final Map<String, GameMap> maps = new ConcurrentHashMap<>();

    public World(ObjectMapper json) {
        // Phase B 의 타일맵 JSON 과 맞춘 기본 스폰 위치.
        // 실제 포털 진입 시 스폰 좌표는 포털 정의의 targetX/Y 를 사용한다.
        maps.put("henesys", new GameMap("henesys", 80, 100, json));
        maps.put("ellinia", new GameMap("ellinia", 80, 480, json));
    }

    public GameMap map(String id) {
        return maps.get(id);
    }

    public GameMap defaultMap() {
        return maps.get("henesys");
    }
}
