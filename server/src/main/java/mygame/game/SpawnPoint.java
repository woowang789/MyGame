package mygame.game;

import java.util.function.IntSupplier;
import mygame.game.entity.Monster;
import mygame.game.entity.MonsterRegistry;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;

/**
 * 몬스터 스폰 정의. 맵이 자신의 {@link Monster} 를 재생성할 때 참조한다.
 *
 * <p>"어떤 몬스터를" 은 {@link MonsterTemplate} 가, "어디에/어느 구간을 돌며"
 * 는 이 record 가 담당한다. 관심사 분리 덕에 같은 템플릿을 여러 맵/구간에
 * 자유롭게 배치할 수 있다.
 */
public record SpawnPoint(
        String template,
        double spawnX,
        double groundY,
        double minX,
        double maxX
) {

    public static SpawnPoint of(String templateId, double spawnX, double groundY,
                                double minX, double maxX) {
        // 존재하지 않는 템플릿을 앞단에서 실패시킨다(빠른 실패).
        MonsterRegistry.get(templateId);
        return new SpawnPoint(templateId, spawnX, groundY, minX, maxX);
    }

    public MonsterTemplate templateSpec() { return MonsterRegistry.get(template); }

    public int maxHp() { return templateSpec().maxHp(); }
    public int expReward() { return templateSpec().expReward(); }
    public DropTable dropTable() { return templateSpec().dropTable(); }
    public long respawnDelayMs() { return templateSpec().respawnDelayMs(); }
    public int mesoMin() { return templateSpec().mesoMin(); }
    public int mesoMax() { return templateSpec().mesoMax(); }

    public Monster create(IntSupplier idGen) {
        MonsterTemplate t = templateSpec();
        return new Monster(
                idGen.getAsInt(), t.id(),
                spawnX, groundY, minX, maxX, t.speed(),
                t.maxHp(), t.attackDamage(), t.attackIntervalMs()
        );
    }
}
