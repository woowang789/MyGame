package mygame.game.event;

import mygame.game.entity.Monster;
import mygame.game.entity.Player;

/**
 * 게임 내 주요 이벤트. Observer 패턴의 "주제(Subject) → 관찰자(Observer)" 를
 * 매개하는 불변 메시지로 사용한다.
 *
 * <p>sealed 로 변형을 명시하면, 처리 측에서 switch pattern matching 으로
 * 누락 없이 분기할 수 있다.
 */
public sealed interface GameEvent {

    /** 플레이어가 몬스터를 처치했을 때 발행. */
    record MonsterKilled(Player killer, Monster monster, int expReward) implements GameEvent {}

    /** 플레이어의 EXP 가 변경되었을 때. */
    record ExpGained(Player player, int gained) implements GameEvent {}

    /** 레벨이 올랐을 때. */
    record LeveledUp(Player player, int newLevel) implements GameEvent {}
}
