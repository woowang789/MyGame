package mygame.game.skill;

import mygame.game.CombatService;
import mygame.game.GameMap;
import mygame.game.entity.Player;

/**
 * 스킬 실행 문맥.
 *
 * <p>스킬 구현체가 필요한 최소 의존성만 담는다. 데미지 적용은
 * {@link CombatService#damageMonster} 통해서만 이뤄지므로, 구현체는
 * 데미지 공식·대상 선택만 책임지고 패킷 송출/넉백/사망 마무리는 위임된다.
 */
public record SkillContext(
        Player caster,
        GameMap map,
        /** 바라보는 방향("left"/"right"). 근접 공격 판정에 사용. */
        String dir,
        /** 실행 시각(ms). 쿨다운·이펙트 타임스탬프에 공유. */
        long now,
        /** 몬스터 피해 적용 시 브로드캐스트용 attackerId. */
        int attackerId,
        /** 데미지 적용 단일 진입점. 모든 스킬은 이 서비스를 거쳐 몬스터를 타격한다. */
        CombatService combatService) {

    public static SkillContext of(Player caster, GameMap map, String dir, long now,
                                  CombatService combatService) {
        return new SkillContext(caster, map, dir, now, caster.id(), combatService);
    }
}
