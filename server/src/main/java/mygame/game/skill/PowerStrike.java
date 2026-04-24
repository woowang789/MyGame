package mygame.game.skill;

import mygame.game.entity.Monster;
import mygame.game.skill.AttackBox;
import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;

/**
 * 초보자 스킬 — 파워 스트라이크.
 *
 * <p>전방 1타. 기본 공격 대비 180% 데미지. 단일 대상(가장 가까운 몬스터).
 * 메이플 초보자 스킬 "파워 스트라이크" 에서 착안.
 */
public final class PowerStrike implements Skill {

    public static final PowerStrike INSTANCE = new PowerStrike();
    private static final double DAMAGE_MUL = 1.8;

    @Override public String id() { return "power_strike"; }
    @Override public String name() { return "파워 스트라이크"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 8; }
    @Override public long cooldownMs() { return 1500; }

    @Override
    public void apply(SkillContext ctx) {
        var monsters = AttackBox.monstersInFront(ctx.caster(), ctx.map(), ctx.dir(), 1.0);
        if (monsters.isEmpty()) return;
        // 가장 가까운 몬스터 1기만.
        Monster target = monsters.get(0);
        double nearest = Math.abs(target.x() - ctx.caster().x());
        for (Monster m : monsters) {
            double d = Math.abs(m.x() - ctx.caster().x());
            if (d < nearest) { nearest = d; target = m; }
        }
        int dmg = (int) Math.round(ctx.caster().effectiveStats().attack() * DAMAGE_MUL);
        int applied = target.applyDamage(dmg);
        ctx.outcome().addHit(target.id(), applied, target.hp(), target.isDead());
    }
}
