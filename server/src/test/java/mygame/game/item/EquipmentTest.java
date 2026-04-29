package mygame.game.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import mygame.game.stat.BaseStats;
import mygame.game.stat.Stats;
import mygame.game.stat.StatProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장비 슬롯의 장착·교체·해제와 Decorator 체인 합산을 함께 검증.
 *
 * <p>Player 가 의존하는 핵심 경로(인벤 → 장비 슬롯 → 데코레이터 → 최종 스탯) 가
 * 슬롯 수 변경에도 깨지지 않음을 회귀 방지한다.
 */
class EquipmentTest {

    @BeforeAll
    static void seedItemRegistry() {
        // ItemRegistry 가 DB-backed 캐시로 바뀐 뒤로는 테스트 시작 시점에 부트스트랩이 필요.
        mygame.admin.TestRepos.bootstrapDefaultItems();
    }

    @Test
    @DisplayName("빈 장비: decorate(base) 결과는 base 와 동일")
    void empty_decoratorChain_returnsBaseStats() {
        Equipment eq = new Equipment();
        StatProvider base = new BaseStats(1);

        assertEquals(base.stats(), eq.decorate(base).stats());
    }

    @Test
    @DisplayName("여러 장비 장착: 모든 보너스가 누적되어 최종 스탯이 나온다")
    void equipMultiple_aggregatesBonuses() {
        Equipment eq = new Equipment();
        eq.equip("wooden_sword");   // attack +10
        eq.equip("leather_cap");    // maxHp +15, maxMp +5
        eq.equip("cloth_armor");    // maxHp +25

        Stats s = eq.decorate(new BaseStats(1)).stats();
        // 베이스(50,25,20,200) + 합(40,5,10,0)
        assertEquals(90, s.maxHp());
        assertEquals(30, s.maxMp());
        assertEquals(30, s.attack());
        assertEquals(200, s.speed());
    }

    @Test
    @DisplayName("같은 슬롯 재장착: 기존 templateId 가 반환되어 호출자가 인벤토리로 복귀시킬 수 있다")
    void equipSameSlot_returnsReplacedTemplate() {
        Equipment eq = new Equipment();
        assertNull(eq.equip("wooden_sword"));
        String replaced = eq.equip("iron_sword");

        assertEquals("wooden_sword", replaced);
        // 무기는 새 것으로 교체되어 attack 보너스가 25 만 적용
        Stats s = eq.decorate(new BaseStats(1)).stats();
        assertEquals(20 + 25, s.attack());
    }

    @Test
    @DisplayName("unequip: 슬롯이 비고 보너스가 제거된다")
    void unequip_removesBonus() {
        Equipment eq = new Equipment();
        eq.equip("iron_sword");
        String removed = eq.unequip(EquipSlot.WEAPON);

        assertEquals("iron_sword", removed);
        Stats s = eq.decorate(new BaseStats(1)).stats();
        assertEquals(20, s.attack()); // 베이스로 복귀
        assertNull(eq.unequip(EquipSlot.WEAPON)); // 두 번째는 null
    }

    @Test
    @DisplayName("snapshot: EnumMap 의 방어적 복사본이 반환되어 외부 변경이 내부에 영향을 주지 않는다")
    void snapshot_isDefensiveCopy() {
        Equipment eq = new Equipment();
        eq.equip("wooden_sword");

        var snap = eq.snapshot();
        assertEquals("wooden_sword", snap.get(EquipSlot.WEAPON));
        // 불변 뷰 — 직접 수정 시도는 UnsupportedOperationException
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snap.remove(EquipSlot.WEAPON));
    }
}
