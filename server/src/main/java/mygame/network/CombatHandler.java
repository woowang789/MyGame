package mygame.network;

import mygame.game.CombatService;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Monster;
import mygame.game.entity.Player;
import mygame.game.skill.AttackBox;
import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;
import mygame.game.skill.SkillRegistry;
import mygame.network.packets.Packets.AttackRequest;
import mygame.network.packets.Packets.MonsterDamagedPacket;
import mygame.network.packets.Packets.SkillUsedPacket;
import mygame.network.packets.Packets.UseSkillRequest;

/**
 * 전투 관련 핸들러. 기본 공격(ATTACK) 과 스킬 사용(USE_SKILL) 을 담당.
 *
 * <p>스킬은 쿨다운/MP 차감/데미지 적용을 거쳐 CombatService 에 마무리(드롭·경험치) 를 위임한다.
 */
public final class CombatHandler {

    private final World world;
    private final CombatService combatService;
    private final SessionNotifier notifier;

    public CombatHandler(World world, CombatService combatService, SessionNotifier notifier) {
        this.world = world;
        this.combatService = combatService;
        this.notifier = notifier;
    }

    public void handleAttack(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "join required"));
            return;
        }
        if (player.isDead()) return;
        AttackRequest req = ctx.json().treeToValue(ctx.body(), AttackRequest.class);
        GameMap map = world.map(player.mapId());
        if (map == null) return;

        String dir = (req.dir() == null || req.dir().isBlank()) ? player.facing() : req.dir();
        // 데미지 = 최종 스탯의 attack (Decorator 체인: 레벨 + 장비 합산).
        int damage = player.effectiveStats().attack();
        // 기본 공격은 1마리만. 범위 내 가장 가까운 몬스터를 고른다.
        for (Monster m : AttackBox.nearestInFront(player, map, dir, 1.0, 1)) {
            combatService.damageMonster(map, player, m, damage);
        }
    }

    public void handleUseSkill(PacketContext ctx) throws Exception {
        Player player = ctx.player();
        if (player == null) return;
        if (player.isDead()) return;
        UseSkillRequest req = ctx.json().treeToValue(ctx.body(), UseSkillRequest.class);
        String skillId = req.skillId();
        if (skillId == null || !SkillRegistry.exists(skillId)) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "알 수 없는 스킬: " + skillId));
            return;
        }
        Skill skill = SkillRegistry.get(skillId);

        // 직업 태그 검증. 초보자는 BEGINNER 스킬만 사용 가능. 전직 Phase 확장 지점.
        if (!Skill.JOB_BEGINNER.equals(skill.job())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "아직 배우지 않은 스킬입니다."));
            return;
        }

        long now = System.currentTimeMillis();
        if (!player.tryActivateSkill(skill.id(), skill.cooldownMs(), now)) {
            // 쿨다운 중. 스팸 방지를 위해 조용히 드롭하고 클라 HUD 가 쿨다운을 표시.
            return;
        }
        if (!player.spendMp(skill.mpCost())) {
            ctx.conn().send(PacketEnvelope.error(ctx.json(), "MP 가 부족합니다."));
            return;
        }

        GameMap map = world.map(player.mapId());
        if (map == null) return;
        String dir = (req.dir() == null || req.dir().isBlank()) ? player.facing() : req.dir();
        SkillContext skillCtx = SkillContext.of(player, map, dir, now);
        skill.apply(skillCtx);

        // 이펙트용 브로드캐스트 먼저(근접/원거리 모든 스킬 공통).
        map.broadcast("SKILL_USED", new SkillUsedPacket(player.id(), skill.id(), dir));

        // 몬스터 피해/사망 결과 송출. 스킬 구현체가 이미 Monster.applyDamage 를 호출했으므로
        // 여기서는 CombatService.finishKill 로 드롭/EXP/브로드캐스트만 마무리한다.
        for (var hit : skillCtx.outcome().hits()) {
            map.broadcast("MONSTER_DAMAGED",
                    new MonsterDamagedPacket(hit.monsterId(), hit.damage(), hit.remainingHp(), player.id()));
            if (!hit.killed()) continue;
            Monster target = map.monster(hit.monsterId());
            if (target != null) combatService.finishKill(map, player, target);
        }

        // MP / 쿨다운 상태 동기화. 스탯 패킷이 현재 mp 를 포함하므로 한 번 보내면 된다.
        notifier.sendStats(player);
    }
}
