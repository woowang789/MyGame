package mygame.game.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.item.DropTable;
import mygame.game.item.DropTable.Entry;

/**
 * 몬스터 템플릿 레지스트리.
 *
 * <p>{@link mygame.game.item.ItemRegistry} 와 대칭 구조. 현재 in-memory 이지만
 * 추후 JSON/DB 로 교체해도 호출부는 변하지 않는다.
 */
public final class MonsterRegistry {

    private static final Map<String, MonsterTemplate> TEMPLATES;

    static {
        Map<String, MonsterTemplate> m = new LinkedHashMap<>();

        // 달팽이: 초보용. 약한 공격, 잦은 포션 드롭.
        put(m, new MonsterTemplate(
                "snail", "달팽이",
                50, 10, 1500, 60, 15, 5000,
                5, 20,
                DropTable.of(
                        new Entry("red_potion", 0.50),
                        new Entry("snail_shell", 0.40),
                        new Entry("blue_potion", 0.10),
                        new Entry("wooden_sword", 0.05),
                        new Entry("leather_cap", 0.03)
                ),
                0x7cd36a));

        // 파란 달팽이: 이동은 비슷하지만 맷집이 두 배 가까이.
        put(m, new MonsterTemplate(
                "blue_snail", "파란 달팽이",
                90, 14, 1400, 70, 28, 6000,
                10, 35,
                DropTable.of(
                        new Entry("blue_potion", 0.45),
                        new Entry("snail_shell", 0.30),
                        new Entry("red_potion", 0.20),
                        new Entry("wooden_sword", 0.06)
                ),
                0x6cb7ff));

        // 빨간 달팽이: 공격력과 리워드가 더 높다. 중급 필드용.
        put(m, new MonsterTemplate(
                "red_snail", "빨간 달팽이",
                140, 22, 1200, 80, 50, 7000,
                20, 60,
                DropTable.of(
                        new Entry("red_potion", 0.55),
                        new Entry("snail_shell", 0.25),
                        new Entry("iron_sword", 0.04),
                        new Entry("cloth_armor", 0.05)
                ),
                0xff6a5a));

        // 버섯: 느리고 무해하지만 EXP/메소 효율이 좋다.
        put(m, new MonsterTemplate(
                "orange_mushroom", "주황버섯",
                110, 18, 1600, 45, 40, 6500,
                15, 45,
                DropTable.of(
                        new Entry("red_potion", 0.35),
                        new Entry("blue_potion", 0.35),
                        new Entry("leather_cap", 0.08),
                        new Entry("cloth_armor", 0.06)
                ),
                0xffa64a));

        // 나무 토막: 느리고 공격도 약하지만 접촉 시 약간의 피해는 준다.
        put(m, new MonsterTemplate(
                "stump", "나무 토막",
                70, 8, 2000, 30, 20, 5500,
                8, 25,
                DropTable.of(
                        new Entry("snail_shell", 0.20),
                        new Entry("red_potion", 0.15)
                ),
                0x9a6b3d));

        TEMPLATES = Collections.unmodifiableMap(m);
    }

    private MonsterRegistry() {}

    private static void put(Map<String, MonsterTemplate> m, MonsterTemplate t) {
        m.put(t.id(), t);
    }

    public static MonsterTemplate get(String id) {
        MonsterTemplate t = TEMPLATES.get(id);
        if (t == null) throw new IllegalArgumentException("알 수 없는 몬스터 템플릿: " + id);
        return t;
    }
}
