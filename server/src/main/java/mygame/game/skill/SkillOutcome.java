package mygame.game.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 스킬 실행 결과. 구현체가 채워 넣고, 호출자(GameServer)가 브로드캐스트할 때 읽는다.
 *
 * <p>직접 네트워크 API 를 쓰지 않는 이유: 스킬 도메인이 패킷 포맷에 결합되면
 * 테스트가 어려워진다. 결과만 리포트하고 네트워크 계층이 이를 패킷으로 변환한다.
 */
public final class SkillOutcome {

    public record MonsterHit(int monsterId, int damage, int remainingHp, boolean killed) {}

    private final List<MonsterHit> hits = new ArrayList<>();
    private int mpRestored = 0;

    public void addHit(int monsterId, int damage, int remainingHp, boolean killed) {
        hits.add(new MonsterHit(monsterId, damage, remainingHp, killed));
    }

    public void setMpRestored(int amount) {
        this.mpRestored = amount;
    }

    public List<MonsterHit> hits() { return Collections.unmodifiableList(hits); }
    public int mpRestored() { return mpRestored; }
}
