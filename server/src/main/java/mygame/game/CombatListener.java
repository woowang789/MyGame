package mygame.game;

import mygame.game.entity.Monster;
import mygame.game.entity.Player;

/**
 * 전투 이벤트 알림 인터페이스.
 *
 * <p>도메인(GameMap/GameLoop)이 네트워크 계층(GameServer)에 직접 의존하지 않도록
 * 간단한 콜백으로 분리한다. GameServer 가 이를 구현해 패킷 송신·DB 저장을 담당.
 */
public interface CombatListener {

    /** 플레이어가 몬스터에게 피격당했을 때. dmgApplied 는 실제 적용된 피해량. */
    void onPlayerDamaged(Player target, Monster attacker, int dmgApplied);

    /** 플레이어가 HP 0 이 된 시점. 호출 후 호출자(GameMap tick)는 더 이상 피격 처리 X. */
    void onPlayerDied(Player target, Monster killer);
}
