package mygame.game.item;

/**
 * 맵에 떨어져 있는 아이템. 누군가 픽업할 때까지 일정 시간 존재한다.
 *
 * <p>{@code amount} 는 일반 아이템의 경우 1 이며, 메소 드롭(templateId="meso")의
 * 경우 떨어진 금액을 나타낸다. 한 엔티티가 여러 용도를 겸하도록 둔 이유는
 * 별도 MesoPile 타입을 두면 GameMap · 패킷 경로가 모두 둘로 갈라지기 때문.
 */
public final class DroppedItem {

    /** 메소 드롭임을 나타내는 예약 템플릿 ID. */
    public static final String MESO_ID = "meso";

    private final int id;
    private final String templateId;
    private final double x;
    private final double y;
    private final long expireAtMs;
    private final int amount;

    public DroppedItem(int id, String templateId, double x, double y, long ttlMs) {
        this(id, templateId, x, y, ttlMs, 1);
    }

    public DroppedItem(int id, String templateId, double x, double y, long ttlMs, int amount) {
        this.id = id;
        this.templateId = templateId;
        this.x = x;
        this.y = y;
        this.expireAtMs = System.currentTimeMillis() + ttlMs;
        this.amount = Math.max(1, amount);
    }

    public int id() { return id; }
    public String templateId() { return templateId; }
    public double x() { return x; }
    public double y() { return y; }
    public int amount() { return amount; }
    public boolean isMeso() { return MESO_ID.equals(templateId); }
    public boolean isExpired(long now) { return now >= expireAtMs; }
}
