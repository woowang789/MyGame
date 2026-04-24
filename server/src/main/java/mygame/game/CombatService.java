package mygame.game;

import mygame.game.entity.Monster;
import mygame.game.entity.Player;
import mygame.game.event.EventBus;
import mygame.game.event.GameEvent.MonsterKilled;
import mygame.game.item.DroppedItem;
import mygame.network.packets.Packets.MonsterDamagedPacket;
import mygame.network.packets.Packets.MonsterDiedPacket;

/**
 * 몬스터 피격·사망 처리 일원화.
 *
 * <p>기본 공격과 스킬 양쪽이 동일한 "피해 브로드캐스트 + 처치 시 드롭/EXP" 경로를
 * 두 번 쓰고 있었다. 한 곳에 모아 양측이 항상 같은 순서·패킷을 보내도록 보장한다.
 */
public final class CombatService {

    private static final long DROP_TTL_MS = 60_000;
    /** 드롭 아이템 간 x축 간격. 3개 이상 떨어질 때 겹치지 않도록 흩뿌림. */
    private static final double DROP_SCATTER_STEP = 16;
    /** 몬스터 넉백 속도(px/s) 와 지속 시간(ms). 총 이동량 ≈ speed × duration/1000. */
    private static final double MONSTER_KNOCKBACK_SPEED = 320;
    private static final long MONSTER_KNOCKBACK_MS = 280;
    /** 피격 후 추격(어그로) 지속시간. 이 시간이 지나면 일반 배회로 복귀. */
    private static final long MONSTER_AGGRO_MS = 4000;

    private final World world;
    private final EventBus eventBus;

    public CombatService(World world, EventBus eventBus) {
        this.world = world;
        this.eventBus = eventBus;
    }

    /**
     * 한 몬스터에 피해를 입히고 사망 시 킬/드롭/EXP 처리까지 완료한다.
     *
     * @return 이번 타격으로 실제 적용된 피해량
     */
    public int damageMonster(GameMap map, Player attacker, Monster target, int damage) {
        if (target.isDead()) return 0;
        int applied = target.applyDamage(damage);
        map.broadcast("MONSTER_DAMAGED",
                new MonsterDamagedPacket(target.id(), applied, target.hp(), attacker.id()));
        if (target.isDead()) {
            finishKill(map, attacker, target);
        } else {
            // 공격자 위치 기준으로 몬스터를 뒤로 밀어낸다. 공격자가 좌측이면 우측으로.
            double dir = target.x() >= attacker.x() ? 1 : -1;
            target.applyKnockback(dir * MONSTER_KNOCKBACK_SPEED, MONSTER_KNOCKBACK_MS);
            // 넉백 이후에는 공격자를 추격. 배회 구간 밖으로 나가지는 않는다.
            target.setPostKnockbackState(new mygame.game.ai.ChaseState(
                    attacker, map.id(), MONSTER_AGGRO_MS));
            // 이동 브로드캐스트로 클라가 즉시 보간을 시작하도록 최신 상태를 전달.
            map.broadcast("MONSTER_MOVE",
                    new mygame.network.packets.Packets.MonsterMovedPacket(
                            target.id(), target.x(), target.vx()));
        }
        return applied;
    }

    /** 이미 사망 판정이 난 몬스터에 대해서만 호출 (스킬 경로처럼 피해는 이미 반영된 경우). */
    public void finishKill(GameMap map, Player attacker, Monster target) {
        SpawnPoint origin = map.findSpawnFor(target);
        map.killMonster(target, origin);
        map.broadcast("MONSTER_DIED", new MonsterDiedPacket(target.id()));
        int expReward = origin != null ? origin.expReward() : 0;
        eventBus.publish(new MonsterKilled(attacker, target, expReward));
        rollDrops(map, target, origin);
    }

    private void rollDrops(GameMap map, Monster target, SpawnPoint origin) {
        if (origin == null) return;
        int scatter = 0;
        if (origin.dropTable() != null) {
            for (String itemId : origin.dropTable().roll()) {
                double dropX = target.x() + (scatter - 1) * DROP_SCATTER_STEP;
                scatter++;
                map.addDroppedItem(new DroppedItem(
                        world.itemIdSeq().getAndIncrement(),
                        itemId, dropX, target.y(), DROP_TTL_MS));
            }
        }
        // 메소 드롭: [mesoMin, mesoMax] 균등 분포. 0 이면 생략.
        if (origin.mesoMax() > 0 && origin.mesoMax() >= origin.mesoMin()) {
            int amount = java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(origin.mesoMin(), origin.mesoMax() + 1);
            if (amount > 0) {
                double dropX = target.x() + (scatter - 1) * DROP_SCATTER_STEP;
                map.addDroppedItem(new DroppedItem(
                        world.itemIdSeq().getAndIncrement(),
                        DroppedItem.MESO_ID, dropX, target.y(), DROP_TTL_MS, amount));
            }
        }
    }
}
