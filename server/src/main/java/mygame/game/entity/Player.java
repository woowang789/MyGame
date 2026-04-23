package mygame.game.entity;

import mygame.game.item.Inventory;
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

    private volatile String mapId;
    private volatile double x;
    private volatile double y;
    private volatile double vx;
    private volatile double vy;
    /** "left" / "right" — 마지막 수평 이동 방향. 공격 박스 방향 계산에 쓰인다. */
    private volatile String facing = "right";
    private volatile int level = 1;
    private volatile int exp = 0;
    private final Inventory inventory = new Inventory();

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
    public String mapId() { return mapId; }
    public double x() { return x; }
    public double y() { return y; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public String facing() { return facing; }
    public int level() { return level; }
    public int exp() { return exp; }
    public Inventory inventory() { return inventory; }

    /** 현 레벨 기준 다음 레벨까지 필요한 누적 EXP. 간단 선형식. */
    public int expToNextLevel() {
        return 50 * level;
    }

    /** EXP 획득. 반환값은 이 호출로 달성한 레벨 상승 횟수(0 이상). */
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
