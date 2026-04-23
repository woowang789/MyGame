package mygame.game.item;

/**
 * 맵에 떨어져 있는 아이템. 누군가 픽업할 때까지 일정 시간 존재한다.
 */
public final class DroppedItem {

    private final int id;
    private final String templateId;
    private final double x;
    private final double y;
    private final long expireAtMs;

    public DroppedItem(int id, String templateId, double x, double y, long ttlMs) {
        this.id = id;
        this.templateId = templateId;
        this.x = x;
        this.y = y;
        this.expireAtMs = System.currentTimeMillis() + ttlMs;
    }

    public int id() { return id; }
    public String templateId() { return templateId; }
    public double x() { return x; }
    public double y() { return y; }
    public boolean isExpired(long now) { return now >= expireAtMs; }
}
