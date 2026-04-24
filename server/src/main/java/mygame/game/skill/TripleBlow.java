package mygame.game.skill;

import mygame.game.entity.Monster;

/**
 * 초보자 스킬 — 트리플 블로우(삼연타).
 *
 * <p>전방 단일 대상에 기본 공격의 100% 를 3회 연타. 범위는 기본 공격과 동일(1.0x).
 * 쿨다운이 길어 단일 개체에 고정타를 빠르게 몰아넣는 버스트 용도.
 */
public final class TripleBlow implements Skill {

    public static final TripleBlow INSTANCE = new TripleBlow();
    private static final double DAMAGE_MUL_PER_HIT = 1.0;
    private static final int HITS = 3;
    private static final int MAX_TARGETS = 1;

    @Override public String id() { return "triple_blow"; }
    @Override public String name() { return "트리플 블로우"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 18; }
    @Override public long cooldownMs() { return 4000; }

    @Override
    public void apply(SkillContext ctx) {
        var targets = AttackBox.nearestInFront(ctx.caster(), ctx.map(), ctx.dir(), 1.0, MAX_TARGETS);
        if (targets.isEmpty()) return;
        int perHit = (int) Math.round(ctx.caster().effectiveStats().attack() * DAMAGE_MUL_PER_HIT);
        for (Monster m : targets) {
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
