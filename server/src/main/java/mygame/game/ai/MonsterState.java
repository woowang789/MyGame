package mygame.game.ai;

import mygame.game.entity.Monster;

/**
 * State 패턴의 상태 인터페이스.
 *
 * <p>각 상태는 진입 시 초기화({@link #onEnter})와 매 tick 갱신({@link #update})을
 * 정의한다. 전이는 상태 내부에서 {@link Monster#transitionTo(MonsterState)} 로
 * 다음 상태를 지정하는 방식(자가 전이)으로 표현한다.
 */
public interface MonsterState {
    void onEnter(Monster m);
    void update(Monster m, long dtMs);
}
