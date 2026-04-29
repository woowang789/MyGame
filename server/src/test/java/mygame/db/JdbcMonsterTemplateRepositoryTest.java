package mygame.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;
import mygame.game.item.DropTable.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 H2 위에서 {@link JdbcMonsterTemplateRepository} 의 1:N drop 매핑·MERGE upsert·
 * replaceDrops 트랜잭션·CASCADE 삭제 동작을 검증.
 */
class JdbcMonsterTemplateRepositoryTest {

    private Database db;
    private JdbcMonsterTemplateRepository repo;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:mon-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        db = new Database(url, "sa", "");
        Migrations.apply(db);
        repo = new JdbcMonsterTemplateRepository(db);
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    @DisplayName("시드 적재: 5개 종 + drop table 정렬 (sort_order)")
    void seedRowsLoaded() {
        var snail = repo.findById("snail").orElseThrow();
        assertEquals("달팽이", snail.displayName());
        assertEquals(50, snail.maxHp());
        // sort_order 0 이 첫 번째 — red_potion(0.50)
        var first = snail.dropTable().entries().get(0);
        assertEquals("red_potion", first.itemId());
        assertEquals(0.50, first.chance(), 1e-9);
        // 5개 라인 모두
        assertEquals(5, snail.dropTable().entries().size());
    }

    @Test
    @DisplayName("upsertTemplate: 기존 종 부모 행만 갱신, drop table 은 보존")
    void upsertParentPreservesDrops() {
        // 기존 snail 의 maxHp 만 999 로 갱신
        var current = repo.findById("snail").orElseThrow();
        var updated = new MonsterTemplate(current.id(), current.displayName(),
                999, current.attackDamage(), current.attackIntervalMs(), current.speed(),
                current.expReward(), current.respawnDelayMs(),
                current.mesoMin(), current.mesoMax(),
                current.dropTable(), current.bodyColor());
        repo.upsertTemplate(updated);

        var after = repo.findById("snail").orElseThrow();
        assertEquals(999, after.maxHp());
        // drop table 5 라인 그대로
        assertEquals(5, after.dropTable().entries().size());
    }

    @Test
    @DisplayName("replaceDrops: 트랜잭션으로 전체 교체 — 새 라인 N 행만 남는다")
    void replaceDrops_transactional() {
        // 기존 snail 의 5 라인을 2 라인으로 교체
        DropTable next = new DropTable(List.of(
                new Entry("snail_shell", 0.99),
                new Entry("blue_potion", 0.10)));
        int affected = repo.replaceDrops("snail", next);
        assertEquals(2, affected);

        var after = repo.findById("snail").orElseThrow();
        assertEquals(2, after.dropTable().entries().size());
        assertEquals("snail_shell", after.dropTable().entries().get(0).itemId());
        assertEquals(0.99, after.dropTable().entries().get(0).chance(), 1e-9);
    }

    @Test
    @DisplayName("upsertDropLine + deleteDropLine: 단일 라인 인라인 편집")
    void singleLineEdit() {
        // 기존 snail / red_potion 의 chance 50% → 70% 로 갱신
        repo.upsertDropLine("snail", "red_potion", 0.70, 0);
        Entry after = findEntry(repo.findById("snail").orElseThrow(), "red_potion");
        assertEquals(0.70, after.chance(), 1e-9);

        // 삭제
        assertEquals(1, repo.deleteDropLine("snail", "red_potion"));
        assertEquals(null, findEntryOrNull(repo.findById("snail").orElseThrow(), "red_potion"));
        // 두 번째 삭제는 0
        assertEquals(0, repo.deleteDropLine("snail", "red_potion"));
    }

    private static Entry findEntry(MonsterTemplate t, String itemId) {
        Entry e = findEntryOrNull(t, itemId);
        if (e == null) throw new AssertionError("missing entry: " + itemId);
        return e;
    }

    private static Entry findEntryOrNull(MonsterTemplate t, String itemId) {
        for (Entry e : t.dropTable().entries()) {
            if (e.itemId().equals(itemId)) return e;
        }
        return null;
    }

    @Test
    @DisplayName("deleteById: 부모 삭제 시 monster_drops 도 FK CASCADE 로 함께 삭제")
    void cascadeDelete() {
        assertEquals(1, repo.deleteById("snail"));
        assertTrue(repo.findById("snail").isEmpty());
        // monster_drops 에서도 사라졌는지 직접 카운트
        try (var conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM monster_drops WHERE monster_id = 'snail'");
             var rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
