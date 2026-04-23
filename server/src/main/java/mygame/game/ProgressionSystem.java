package mygame.game;

import mygame.game.entity.Player;
import mygame.game.event.EventBus;
import mygame.game.event.GameEvent;
import mygame.game.event.GameEvent.ExpGained;
import mygame.game.event.GameEvent.LeveledUp;
import mygame.game.event.GameEvent.MonsterKilled;

/**
 * EXP/레벨업 처리 구독자.
 *
 * <p>{@link MonsterKilled} 이벤트를 받으면 가해자 {@link Player} 에 EXP 를 부여하고,
 * 레벨 상승이 발생한 만큼 {@link LeveledUp} 이벤트를 재발행한다.
 * 네트워크 브로드캐스트는 별도 구독자가 담당하도록 책임을 분리했다 — 단일 책임 원칙.
 */
public final class ProgressionSystem {

    private final EventBus bus;

    public ProgressionSystem(EventBus bus) {
        this.bus = bus;
        bus.subscribe(this::onEvent);
    }

    private void onEvent(GameEvent event) {
        if (!(event instanceof MonsterKilled mk)) return;

        Player killer = mk.killer();
        int before = killer.level();
        int levelUps = killer.gainExp(mk.expReward());

        bus.publish(new ExpGained(killer, mk.expReward()));
        if (levelUps > 0) {
            bus.publish(new LeveledUp(killer, before + levelUps));
        }
    }
}
