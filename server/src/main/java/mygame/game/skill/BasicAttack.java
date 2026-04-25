package mygame.game.skill;

import mygame.game.entity.Monster;

/**
 * 기본 공격을 스킬과 동일 선상에 둔 단일 시민.
 *
 * <p>전방 가장 가까운 1대상에 ATK × 1.0 데미지를 적용. MP 비용 없음.
 * 짧은 쿨다운(350ms) 으로 자동 공격 속도를 제한한다.
 *
 * <p>UI 노출: 클라 스킬바 렌더는 {@code id === "basic_attack"} 항목을 필터링해 제외한다.
 * 클라이언트가 Space 키 입력에 대해 {@code USE_SKILL{skillId:"basic_attack"}} 를 송신해
 * 다른 스킬과 동일한 검증·송출 경로를 타게 한다.
 */
public final class BasicAttack implements Skill {

    public static final BasicAttack INSTANCE = new BasicAttack();
    /** 자동 공격 속도 제한. 너무 짧으면 패킷 폭주, 너무 길면 답답하므로 ~350ms. */
    private static final long COOLDOWN_MS = 350;
    private static final int MAX_TARGETS = 1;

    @Override public String id() { return "basic_attack"; }
    @Override public String name() { return "기본 공격"; }
    @Override public String job() { return JOB_BEGINNER; }
    @Override public int mpCost() { return 0; }
    @Override public long cooldownMs() { return COOLDOWN_MS; }

    @Override
    public void apply(SkillContext ctx) {
        int dmg = ctx.caster().effectiveStats().attack();
        for (Monster target : AttackBox.nearestInFront(ctx.caster(), ctx.map(), ctx.dir(), 1.0, MAX_TARGETS)) {
            ctx.combatService().damageMonster(ctx.map(), ctx.caster(), target, dmg);
        }
    }
}
