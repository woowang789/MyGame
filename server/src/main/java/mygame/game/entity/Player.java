package mygame.game.entity;

import mygame.game.item.Equipment;
import mygame.game.item.Inventory;
import mygame.game.stat.BaseStats;
import mygame.game.stat.Stats;
import mygame.network.packets.Packets.PlayerState;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 게임 내 한 명의 플레이어.
 *
 * <p><b>두 종류의 식별자</b>
 * <ul>
 *   <li>{@link #id()} — <b>세션 ID</b>. 접속할 때마다 새로 발급되는 짧은 정수.
 *       네트워크 패킷의 {@code playerId} 필드, 같은 맵 안의 다른 클라가 이 플레이어를
 *       지칭할 때 쓰인다. 재접속하면 값이 바뀐다.
 *   <li>{@link #dbId()} — <b>영속 식별자</b>(players 테이블 PK). 캐릭터 수명 동안
 *       불변. DB 저장·로드, Repository 호출에서만 쓰인다. 네트워크에 노출되지 않는다.
 * </ul>
 * 두 ID 를 섞어 쓰면 사고가 나기 쉬워, 서로 타입(int vs long) 과 명칭으로 분리했다.
 *
 * <p>상태 변경은 {@link #updatePosition(double, double, double, double)} 을 통해서만
 * 일어난다. 서버 측 게임 루프(Phase E 이후)가 권위를 갖게 되면 이 setter 도
 * 검증 로직이 추가될 예정이다.
 *
 * <p>동시성: 플레이어 상태는 여러 WebSocket 스레드에서 접근될 수 있다.
 * 현재는 개별 필드 갱신이 원자적이도록 {@code volatile} 로 충분하다.
 */
public final class Player {

    private final int id;
    private final String name;
    private final WebSocket connection;
    /**
     * DB primary key (players 테이블). 생성자에서 한 번만 주입되어 final.
     * 0 이하 값은 invariant 위반으로 거부한다 — "Player 인스턴스가 존재한다 = dbId 가 유효하다".
     */
    private final long dbId;

    private volatile String mapId;
    private volatile double x;
    private volatile double y;
    private volatile double vx;
    private volatile double vy;
    /** "left" / "right" — 마지막 수평 이동 방향. 공격 박스 방향 계산에 쓰인다. */
    private volatile String facing = "right";
    private volatile int level = 1;
    private volatile int exp = 0;
    private volatile int hp = 0;
    private volatile int mp = 0;
    /** 소지 메소(게임 내 재화). 음수로 가지 않도록 변경은 반드시 synchronized 로. */
    private volatile long meso = 0;
    /** 무적 시간 종료 시각(ms). 이 값보다 과거 시각 피격만 유효. */
    private volatile long invulnerableUntil = 0;
    /** 마지막 MOVE 패킷을 검증·반영한 시각(ms). 0 이면 첫 이동 — 검증 스킵. */
    private volatile long lastMoveAt = 0;
    private final Inventory inventory = new Inventory();
    private final Equipment equipment = new Equipment();
    /** 스킬 ID → 마지막 사용 시각(ms). 쿨다운 판정. 접근은 한 플레이어 스레드에서만. */
    private final Map<String, Long> skillLastUsedAt = new ConcurrentHashMap<>();

    public Player(int id, long dbId, String name, WebSocket connection,
                  String mapId, double spawnX, double spawnY) {
        if (dbId <= 0) {
            // 신규 캐릭터든 기존 캐릭터든 Repository 가 양수 PK 를 보장한 뒤에야 호출돼야 한다.
            throw new IllegalArgumentException("dbId 는 양수여야 합니다: " + dbId);
        }
        this.id = id;
        this.dbId = dbId;
        this.name = name;
        this.connection = connection;
        this.mapId = mapId;
        this.x = spawnX;
        this.y = spawnY;
    }

    public int id() { return id; }
    public String name() { return name; }
    public WebSocket connection() { return connection; }
    public long dbId() { return dbId; }
    public String mapId() { return mapId; }
    public double x() { return x; }
    public double y() { return y; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public String facing() { return facing; }
    public int level() { return level; }
    public int exp() { return exp; }
    public int hp() { return hp; }
    public int mp() { return mp; }
    public long meso() { return meso; }

    /** 메소 획득. amount 가 0 이하면 무시. */
    public synchronized void addMeso(long amount) {
        if (amount <= 0) return;
        meso += amount;
    }

    /** 메소 소비. 부족하면 {@code false}(변경 없음). */
    public synchronized boolean spendMeso(long amount) {
        if (amount <= 0) return true;
        if (meso < amount) return false;
        meso -= amount;
        return true;
    }

    /** DB 복원 시 사용. 유효성 검증은 Repository 계층이 담당. */
    public void restoreMeso(long amount) { this.meso = Math.max(0, amount); }

    /**
     * 운영자(admin) 가 메소를 보정. 양수/음수 모두 허용. 결과는 0 이하로 떨어지지 않도록
     * 클램프. {@code addMeso} / {@code spendMeso} 와 다르게 부족분에 대해 false 를 돌려주지
     * 않고 강제로 0 으로 깎는다 — 음수 페널티 시나리오를 admin 측에서 의도적으로 사용.
     *
     * @return 적용 후의 새 메소 잔고
     */
    public synchronized long adjustMeso(long delta) {
        long next = meso + delta;
        meso = Math.max(0, next);
        return meso;
    }

    /**
     * 운영자(admin) 가 EXP 를 보정. 양수/음수 모두 허용. 0 이하로 클램프.
     *
     * <p>의도적으로 자동 레벨업 cascade 를 트리거하지 않는다 — 보상/페널티 의미를 명확히
     * 하고, 레벨 변경이 필요한 경우는 별도 명령으로 다루는 게 사고 위험이 작기 때문.
     */
    public synchronized int adjustExp(int delta) {
        int next = exp + delta;
        exp = Math.max(0, next);
        return exp;
    }
    public boolean isDead() { return hp <= 0; }
    public boolean isInvulnerable(long now) { return now < invulnerableUntil; }
    public Inventory inventory() { return inventory; }
    public Equipment equipment() { return equipment; }

    /**
     * MP 를 소모. 부족하면 {@code false} 반환(변경 없음). 스킬 사용 시 호출.
     * 최대 MP 는 스탯이 바뀌어도 runtime 에서 즉시 재계산되므로 여기서는 단순 차감만.
     */
    public synchronized boolean spendMp(int amount) {
        if (amount <= 0) return true;
        if (mp < amount) return false;
        mp -= amount;
        return true;
    }

    /** MP 회복. 최대치는 effectiveStats().maxMp() 로 상한을 건다. */
    public synchronized void restoreMp(int amount) {
        if (amount <= 0) return;
        int max = effectiveStats().maxMp();
        mp = Math.min(max, mp + amount);
    }

    /** HP 회복. 사망 상태에서는 회복하지 않는다(부활 경로로만 HP 가 채워져야 함). */
    public synchronized void restoreHp(int amount) {
        if (amount <= 0) return;
        if (hp <= 0) return;
        int max = effectiveStats().maxHp();
        hp = Math.min(max, hp + amount);
    }

    /** 로그인/레벨업 시 MP 를 최대치로 채운다. 현재 단계에서는 사망 시스템이 없어 간단 처리. */
    public synchronized void fullHealMp() {
        mp = effectiveStats().maxMp();
    }

    /** 로그인/부활 시 HP 를 최대치로 복구. */
    public synchronized void fullHealHp() {
        hp = effectiveStats().maxHp();
    }

    /**
     * DB 복원 시 사용. 저장된 값이 음수(sentinel) 거나 현재 maxHp 초과면 max 로 클램프.
     * 0 도 그대로 허용 — "사망 상태 저장" 시나리오를 위해. 호출자가 부활/스폰 처리 책임.
     */
    public synchronized void restoreHpMp(int savedHp, int savedMp) {
        int maxHp = effectiveStats().maxHp();
        int maxMp = effectiveStats().maxMp();
        this.hp = (savedHp < 0 || savedHp > maxHp) ? maxHp : savedHp;
        this.mp = (savedMp < 0 || savedMp > maxMp) ? maxMp : savedMp;
    }

    /**
     * 몬스터 접촉 등으로 피해를 받는다.
     *
     * @return 실제 적용된 피해량. 무적/사망 상태면 0.
     */
    public synchronized int takeDamage(int amount, long now, long iframeMs) {
        if (amount <= 0) return 0;
        if (hp <= 0) return 0;
        if (now < invulnerableUntil) return 0;
        int applied = Math.min(hp, amount);
        hp -= applied;
        invulnerableUntil = now + iframeMs;
        return applied;
    }

    /**
     * 쿨다운 체크 + 통과 시 현재 시각 기록. 한 번의 원자 연산으로 중복 사용 방지.
     *
     * <p>"활성화 여부" 는 compute 의 반환값(= 새 매핑값) 채널로는 정확히 표현할 수
     * 없다 — {@code now == last} 인 동시 호출에서 동치 비교가 거짓 양성을 만든다.
     * 따라서 활성화 신호는 1-요소 배열이라는 사이드 채널로 람다 밖으로 빼낸다.
     * 람다가 캡처하는 건 배열 참조(불변)이고, 슬롯 변경은 effectively-final 제약과
     * 무관해 합법.
     */
    public boolean tryActivateSkill(String skillId, long cooldownMs, long now) {
        // compute 는 키에 대해 단일 원자 연산을 보장하므로 두 스레드가 동시에 통과하는 레이스를 차단한다.
        boolean[] activated = {false};
        skillLastUsedAt.compute(skillId, (k, last) -> {
            if (last != null && now - last < cooldownMs) return last;
            activated[0] = true;
            return now;
        });
        return activated[0];
    }

    /** 남은 쿨다운 ms (0 이면 즉시 사용 가능). HUD 용. */
    public long skillCooldownRemaining(String skillId, long cooldownMs, long now) {
        Long last = skillLastUsedAt.get(skillId);
        if (last == null) return 0;
        long elapsed = now - last;
        return elapsed >= cooldownMs ? 0 : cooldownMs - elapsed;
    }

    /**
     * 레벨 기본 스탯 + 장비 보너스(Decorator 체인)를 합산한 최종 스탯.
     * 데미지 계산, 최대 HP 산정 등 "게임 로직이 참조해야 하는 값" 은 반드시 이쪽을 쓴다.
     */
    public Stats effectiveStats() {
        return equipment.decorate(new BaseStats(level)).stats();
    }

    /**
     * 장비를 모두 벗은 상태의 레벨 기본 스탯. 게임 로직은 {@link #effectiveStats()} 만
     * 쓰고, 본 메서드는 스탯창 UI 의 "베이스 vs 장비 보너스" 분리 표시 용도다.
     */
    public Stats baseStats() {
        return new BaseStats(level).stats();
    }

    /** 현 레벨 기준 다음 레벨까지 필요한 누적 EXP. 간단 선형식. */
    public int expToNextLevel() {
        return 50 * level;
    }

    /** EXP 획득. 반환값은 이 호출로 달성한 레벨 상승 횟수(0 이상). */
    /** DB 에서 복원할 때 사용. 신규 플레이어는 level=1, exp=0 으로 그대로. */
    public void restoreProgress(int level, int exp) {
        this.level = level;
        this.exp = exp;
    }

    public synchronized int gainExp(int amount) {
        if (amount <= 0) return 0;
        int levelUps = 0;
        exp += amount;
        while (exp >= expToNextLevel()) {
            exp -= expToNextLevel();
            level++;
            levelUps++;
        }
        return levelUps;
    }

    public void updatePosition(double x, double y, double vx, double vy) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        if (vx > 0.5) this.facing = "right";
        else if (vx < -0.5) this.facing = "left";
    }

    /** MOVE 패킷 검증 시점을 갱신. 다음 패킷의 dt 계산 기준. */
    public void markMoved(long now) {
        this.lastMoveAt = now;
    }

    /** 직전 검증 이후 경과 시각(ms). 0 이면 첫 이동. */
    public long lastMoveAt() {
        return lastMoveAt;
    }

    public void moveTo(String mapId, double x, double y) {
        this.mapId = mapId;
        this.x = x;
        this.y = y;
        this.vx = 0;
        this.vy = 0;
        // 맵 이동/리스폰 직후 첫 MOVE 는 큰 좌표 점프가 정상이므로 검증 스킵.
        this.lastMoveAt = 0;
    }

    public PlayerState toState() {
        return new PlayerState(id, name, x, y);
    }
}
