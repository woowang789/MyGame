package mygame.game.skill;

import mygame.game.GameMap;
import mygame.game.entity.Player;

/**
 * 스킬 실행 문맥.
 *
 * <p>스킬 구현체가 필요한 최소 의존성만 담는다. 네트워크 송신은 호출자가 담당하고,
 * 구현체는 "누가 누구에게 무엇을 했는지" 를 {@link SkillOutcome} 으로 반환.
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
        SkillOutcome outcome) {

    public static SkillContext of(Player caster, GameMap map, String dir, long now) {
        return new SkillContext(caster, map, dir, now, caster.id(), new SkillOutcome());
    }
}
