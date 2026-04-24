package mygame.game.skill;

import mygame.game.entity.Monster;

/**
 * 초보자 스킬 — 파워 스트라이크.
 *
 * <p>전방 최대 2대상 타격. 각 대상에 기본 공격 대비 180% 데미지.
 * 가까운 순으로 선택하므로 밀집된 몬스터를 빠르게 정리할 때 유리하다.
 */
public final class PowerStrike implements Skill {

    public static final PowerStrike INSTANCE = new PowerStrike();
    private static final double DAMAGE_MUL = 1.8;
    private static final int MAX_TARGETS = 2;

    @Override public String id() { return "power_strike"; }
    @Override public String name() { return "파워 스트라이크"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 8; }
    @Override public long cooldownMs() { return 1500; }

    @Override
    public void apply(SkillContext ctx) {
        var targets = AttackBox.nearestInFront(ctx.caster(), ctx.map(), ctx.dir(), 1.0, MAX_TARGETS);
        if (targets.isEmpty()) return;
        int dmg = (int) Math.round(ctx.caster().effectiveStats().attack() * DAMAGE_MUL);
        for (Monster target : targets) {
            if (target.isDead()) continue;
            int applied = target.applyDamage(dmg);
            ctx.outcome().addHit(target.id(), applied, target.hp(), target.isDead());
        }
    }
}
