package mygame.game.entity;

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

    private volatile double x;
    private volatile double y;
    private volatile double vx;
    private volatile double vy;

    public Player(int id, String name, WebSocket connection, double spawnX, double spawnY) {
        this.id = id;
        this.name = name;
        this.connection = connection;
        this.x = spawnX;
        this.y = spawnY;
    }

    public int id() { return id; }
    public String name() { return name; }
    public WebSocket connection() { return connection; }
    public double x() { return x; }
    public double y() { return y; }
    public double vx() { return vx; }
    public double vy() { return vy; }

    public void updatePosition(double x, double y, double vx, double vy) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
    }

    public PlayerState toState() {
        return new PlayerState(id, name, x, y);
    }
}
