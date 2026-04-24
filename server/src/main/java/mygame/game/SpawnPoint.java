package mygame.game;

import java.util.function.IntSupplier;
import mygame.game.entity.Monster;
import mygame.game.item.DropTable;

/**
 * 몬스터 스폰 정의. 맵이 자신의 {@link Monster} 를 재생성할 때 참조한다.
 *
 * <p>record 의 불변성 덕분에 한번 정의하면 맵 전 생애에 걸쳐 안전하게 공유된다.
 */
public record SpawnPoint(
        String template,
        double spawnX,
        double groundY,
        double minX,
        double maxX,
        double speed,
        int maxHp,
        int expReward,
        DropTable dropTable,
        long respawnDelayMs,
        /** 접촉 공격력. 0 이면 비공격 몬스터. Phase N. */
        int attackDamage,
        /** 같은 플레이어에게 연속 공격 가능한 최소 간격(ms). */
        long attackIntervalMs
) {

    public Monster create(IntSupplier idGen) {
        return new Monster(
                idGen.getAsInt(), template,
                spawnX, groundY, minX, maxX, speed,
                maxHp, attackDamage, attackIntervalMs
        );
    }
}
