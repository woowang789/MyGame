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
