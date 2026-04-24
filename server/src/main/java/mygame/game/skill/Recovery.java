package mygame.game.skill;

import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;

/**
 * 초보자 스킬 — 리커버리.
 *
 * <p>MP 를 일정량 회복. 역설적이지만 비용은 0 이고 쿨다운만 길다.
 * 메이플 초보자 스킬 "리커버리" 에서 착안(원작은 HP 회복이지만 HP 시스템이
 * 플레이어에 아직 없어 MP 회복으로 대체).
 */
public final class Recovery implements Skill {

    public static final Recovery INSTANCE = new Recovery();
    private static final int MP_RESTORED = 30;

    @Override public String id() { return "recovery"; }
    @Override public String name() { return "리커버리"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 0; }
    @Override public long cooldownMs() { return 10_000; }

    @Override
    public void apply(SkillContext ctx) {
        int before = ctx.caster().mp();
        ctx.caster().restoreMp(MP_RESTORED);
        int delta = ctx.caster().mp() - before;
        ctx.outcome().setMpRestored(delta);
    }
}
