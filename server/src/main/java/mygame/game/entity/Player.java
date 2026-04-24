package mygame.game.entity;

import mygame.game.item.Equipment;
import mygame.game.item.Inventory;
import mygame.game.stat.BaseStats;
import mygame.game.stat.Stats;
import mygame.network.packets.Packets.PlayerState;
import org.java_websocket.WebSocket;

/**
 * 게임 내 한 명의 플레이어.
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
    /** DB primary key. 세션 ID 와 별개로 영속 식별자. */
    private volatile long dbId = -1;

    private volatile String mapId;
    private volatile double x;
    private volatile double y;
    private volatile double vx;
    private volatile double vy;
    /** "left" / "right" — 마지막 수평 이동 방향. 공격 박스 방향 계산에 쓰인다. */
    private volatile String facing = "right";
    private volatile int level = 1;
    private volatile int exp = 0;
    private volatile int mp = 0;
    private final Inventory inventory = new Inventory();
    private final Equipment equipment = new Equipment();
    /** 스킬 ID → 마지막 사용 시각(ms). 쿨다운 판정. 접근은 한 플레이어 스레드에서만. */
    private final java.util.Map<String, Long> skillLastUsedAt = new java.util.concurrent.ConcurrentHashMap<>();

    public Player(int id, String name, WebSocket connection, String mapId, double spawnX, double spawnY) {
        this.id = id;
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
    public void setDbId(long dbId) { this.dbId = dbId; }
    public String mapId() { return mapId; }
    public double x() { return x; }
    public double y() { return y; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public String facing() { return facing; }
    public int level() { return level; }
    public int exp() { return exp; }
    public int mp() { return mp; }
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

    /** 로그인/레벨업 시 MP 를 최대치로 채운다. 현재 단계에서는 사망 시스템이 없어 간단 처리. */
    public synchronized void fullHealMp() {
        mp = effectiveStats().maxMp();
    }

    /** 쿨다운 체크 + 통과 시 현재 시각 기록. 한 번의 원자 연산으로 중복 사용 방지. */
    public boolean tryActivateSkill(String skillId, long cooldownMs, long now) {
        Long last = skillLastUsedAt.get(skillId);
        if (last != null && now - last < cooldownMs) return false;
        skillLastUsedAt.put(skillId, now);
        return true;
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

    public int gainExp(int amount) {
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

    public void moveTo(String mapId, double x, double y) {
        this.mapId = mapId;
        this.x = x;
        this.y = y;
        this.vx = 0;
        this.vy = 0;
    }

    public PlayerState toState() {
        return new PlayerState(id, name, x, y);
    }
}
