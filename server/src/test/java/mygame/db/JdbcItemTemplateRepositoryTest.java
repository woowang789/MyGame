package mygame.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
import mygame.game.stat.Stats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 H2 위에서 {@link JdbcItemTemplateRepository} 의 type 별 매핑·MERGE upsert·
 * 참조 카운트 동작을 검증. v11/v12 마이그레이션이 자동 적용되므로 시드 9개 행이 들어 있다.
 */
class JdbcItemTemplateRepositoryTest {

    private Database db;
    private JdbcItemTemplateRepository repo;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:item-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        db = new Database(url, "sa", "");
        Migrations.apply(db);
        repo = new JdbcItemTemplateRepository(db);
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    @DisplayName("시드 적재: CONSUMABLE/EQUIPMENT/ETC 모두 type 별 ctor 분기 정상 매핑")
    void seedRowsLoaded() {
        ItemTemplate redPotion = repo.findById("red_potion").orElseThrow();
        assertEquals(ItemType.CONSUMABLE, redPotion.type());
        assertEquals(30, redPotion.use().heal());
        assertEquals(0, redPotion.use().manaHeal());
        assertEquals(25L, redPotion.sellPrice());
        assertNull(redPotion.slot());

        ItemTemplate sword = repo.findById("wooden_sword").orElseThrow();
        assertEquals(ItemType.EQUIPMENT, sword.type());
        assertEquals(EquipSlot.WEAPON, sword.slot());
        assertEquals(10, sword.bonus().attack());
        assertNull(sword.use());

        ItemTemplate shell = repo.findById("snail_shell").orElseThrow();
        assertEquals(ItemType.ETC, shell.type());
        assertNull(shell.slot());
        assertNull(shell.bonus());
        assertNull(shell.use());
    }

    @Test
    @DisplayName("upsert: 기존 id 갱신 + 새 id 삽입")
    void upsertReplacesAndInserts() {
        // 기존 갱신
        repo.upsert(new ItemTemplate("red_potion", "특제 빨간 포션", 0xff0000,
                ItemType.CONSUMABLE, new UseEffect(99, 0), 100L));
        var updated = repo.findById("red_potion").orElseThrow();
        assertEquals("특제 빨간 포션", updated.name());
        assertEquals(99, updated.use().heal());
        assertEquals(100L, updated.sellPrice());

        // 새 EQUIPMENT 삽입
        repo.upsert(new ItemTemplate("test_helm", "시험 투구", 0x123456,
                ItemType.EQUIPMENT, EquipSlot.HAT, new Stats(10, 0, 0, 0), 50L));
        var helm = repo.findById("test_helm").orElseThrow();
        assertEquals(EquipSlot.HAT, helm.slot());
        assertEquals(10, helm.bonus().maxHp());
    }

    @Test
    @DisplayName("countShopReferences: 시드 시점 red_potion 은 henesys_general 에서 1회 참조")
    void shopReferenceCount() {
        assertEquals(1, repo.countShopReferences("red_potion"));
        assertEquals(0, repo.countShopReferences("snail_shell")); // 어떤 shop 도 안 팜
    }

    @Test
    @DisplayName("deleteById: 행 삭제 + 다음 조회 빈 결과")
    void deleteById_removes() {
        assertEquals(1, repo.deleteById("snail_shell"));
        assertTrue(repo.findById("snail_shell").isEmpty());
        assertEquals(0, repo.deleteById("snail_shell")); // 두 번째는 0
    }
}
