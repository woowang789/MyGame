package mygame.game.skill;

import mygame.game.entity.Monster;
import mygame.game.skill.AttackBox;
import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;

/**
 * 초보자 스킬 — 트리플 블로우(삼연타).
 *
 * <p>전방 광역, 기본 공격의 70% 를 3회 동시 타격. 범위는 기본 공격의 1.2배.
 * 쿨다운이 길어 연사 대신 처리량 버스트 용도.
 */
public final class TripleBlow implements Skill {

    public static final TripleBlow INSTANCE = new TripleBlow();
    private static final double DAMAGE_MUL_PER_HIT = 0.7;
    private static final int HITS = 3;

    @Override public String id() { return "triple_blow"; }
    @Override public String name() { return "트리플 블로우"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 18; }
    @Override public long cooldownMs() { return 4000; }

    @Override
    public void apply(SkillContext ctx) {
        var monsters = AttackBox.monstersInFront(ctx.caster(), ctx.map(), ctx.dir(), 1.2);
        if (monsters.isEmpty()) return;
        int perHit = (int) Math.round(ctx.caster().effectiveStats().attack() * DAMAGE_MUL_PER_HIT);
        for (Monster m : monsters) {
            if (m.isDead()) continue;
            int total = 0;
            for (int i = 0; i < HITS; i++) {
                if (m.isDead()) break;
                total += m.applyDamage(perHit);
            }
            ctx.outcome().addHit(m.id(), total, m.hp(), m.isDead());
        }
    }
}
