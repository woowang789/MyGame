package mygame.game.entity;

import mygame.game.item.DropTable;

/**
 * 몬스터 원형. 같은 종(species)이 공유하는 불변 스펙을 한 곳에 모은다.
 *
 * <p>Rule of Three 를 지나 이제 달팽이 외 몬스터가 여럿 추가되므로,
 * 개별 스폰마다 HP/ATK/EXP 를 중복 기입하던 코드를 템플릿 한 번 정의로
 * 대체한다. {@link mygame.game.SpawnPoint} 는 "이 템플릿을 어디에 둘지"
 * 만 지정하면 된다.
 */
public record MonsterTemplate(
        String id,
        String displayName,
        int maxHp,
        int attackDamage,
        long attackIntervalMs,
        double speed,
        int expReward,
        long respawnDelayMs,
        int mesoMin,
        int mesoMax,
        DropTable dropTable,
        /** 클라이언트가 텍스처 색상을 고를 때 쓰는 참조값. 0xRRGGBB. */
        int bodyColor
) {}
