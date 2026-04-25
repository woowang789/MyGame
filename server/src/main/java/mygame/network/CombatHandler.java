package mygame.network;

import mygame.game.CombatService;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Player;
import mygame.game.skill.Skill;
import mygame.game.skill.SkillContext;
import mygame.game.skill.SkillRegistry;
import mygame.network.packets.Packets.SkillUsedPacket;
import mygame.network.packets.Packets.UseSkillRequest;

/**
 * 전투 관련 핸들러. 모든 공격 행동(기본 공격 포함) 은 {@code USE_SKILL} 한 경로로 통합되었다.
 *
 * <p>기본 공격은 {@code skillId="basic_attack"} 스킬로 등록되어 있어 별도 핸들러가 없다.
 * 쿨다운 · MP · 직업 태그 · 데미지 적용 · 패킷 송출이 모두 한 곳에서 일어난다.
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

        // 이펙트용 브로드캐스트 먼저(기본 공격 포함 모든 스킬 공통).
        // 클라가 시전자 자세를 즉시 갱신하도록 데미지 패킷보다 앞에 송신한다.
        map.broadcast("SKILL_USED", new SkillUsedPacket(player.id(), skill.id(), dir));

        // 데미지 적용은 스킬 본체에 위임 — 내부에서 CombatService.damageMonster 가
        // MONSTER_DAMAGED · 넉백 · 사망 시 finishKill(드롭/EXP) 까지 일괄 처리한다.
        SkillContext skillCtx = SkillContext.of(player, map, dir, now, combatService);
        skill.apply(skillCtx);

        // MP / HP 변화를 본인에게 동기화. 회복 스킬도 이 한 줄로 반영된다.
        notifier.sendStats(player);
    }
}
