package mygame.network.packets;

/**
 * 서버 ↔ 클라이언트 패킷 DTO.
 *
 * <p>모두 {@code record} 로 선언해 불변성을 강제한다.
 * Jackson 이 {@code record} 를 네이티브 지원하므로 추가 설정 불필요.
 *
 * <p>직렬화 시 최상위에 {@code type} 필드를 함께 포함시키기 위해,
 * 이 파일의 record 들은 "payload" 역할만 하고, 포장은 송신 측에서
 * {@code PacketEnvelope} 로 감싼다.
 */
public final class Packets {

    private Packets() {}

    // C→S

    /** 클라이언트 입장 요청. 닉네임은 비어 있으면 서버가 기본값을 부여한다. */
    public record JoinRequest(String name) {}

    /** 클라이언트 이동 갱신. 좌표와 속도를 같이 보내 예측/검증 기반을 마련. */
    public record MoveRequest(double x, double y, double vx, double vy) {}

    // S→C

    /** 접속 완료 응답. 자기 자신의 정보와 맵에 이미 있던 다른 플레이어 · 몬스터 목록을 준다. */
    public record WelcomePacket(
            int playerId,
            PlayerState self,
            java.util.List<PlayerState> others,
            java.util.List<MonsterState> monsters,
            java.util.List<DroppedItemState> items
    ) {}

    /** 다른 플레이어가 맵에 들어왔음을 알리는 브로드캐스트. */
    public record PlayerJoinedPacket(PlayerState player) {}

    /** 다른 플레이어가 이동했음. 고빈도(10Hz+) 이므로 필드 수를 최소화. */
    public record PlayerMovedPacket(int id, double x, double y, double vx, double vy) {}

    /** 다른 플레이어가 나갔음. */
    public record PlayerLeftPacket(int id) {}

    /** 플레이어의 공개 상태(신규 접속자에게 현재 스냅샷을 줄 때 사용). */
    public record PlayerState(int id, String name, double x, double y) {}

    /** 클라이언트가 포털을 통해 다른 맵으로 가기를 요청. 목적지 좌표는 포털 정의에서 받는다. */
    public record ChangeMapRequest(String targetMap, double targetX, double targetY) {}

    /**
     * 맵 전환 성공 응답. 수신자에게 새 맵 정보와 해당 맵의 현재 다른 플레이어 목록을 준다.
     * WELCOME 과 유사하지만 playerId 는 이미 알고 있으므로 포함하지 않는다.
     */
    public record MapChangedPacket(
            String mapId, double x, double y,
            java.util.List<PlayerState> others,
            java.util.List<MonsterState> monsters,
            java.util.List<DroppedItemState> items
    ) {}

    /** 몬스터의 공개 상태. 스폰/스냅샷 시 클라이언트에게 전달된다. */
    public record MonsterState(int id, String template, double x, double y, int hp, int maxHp) {}

    /** 몬스터 이동 브로드캐스트. 저빈도 송신(상태 변경 시 + 주기적 샘플). */
    public record MonsterMovedPacket(int id, double x, double vx) {}

    /** 공격 요청. 대상은 서버가 플레이어 공격 박스로 판정. */
    public record AttackRequest(String dir) {}

    /** 몬스터가 피격당했음을 알리는 브로드캐스트. */
    public record MonsterDamagedPacket(int id, int dmg, int hp, int attackerId) {}

    /** 몬스터 사망 브로드캐스트. 클라이언트는 스프라이트 제거. */
    public record MonsterDiedPacket(int id) {}

    /** 새 몬스터 스폰(리스폰 등). */
    public record MonsterSpawnedPacket(MonsterState monster) {}

    /** EXP 획득/현재 상태 알림. 수신자(본인)에게만 전송. */
    public record ExpUpdatedPacket(int exp, int level, int toNextLevel, int gained) {}

    /** 레벨업 알림. 본인 + 주변에 이펙트용으로 브로드캐스트. */
    public record LevelUpPacket(int playerId, int level) {}

    /** 드롭 아이템 공개 상태. */
    public record DroppedItemState(int id, String templateId, double x, double y) {}

    /** 맵 위에 새 아이템이 떨어졌음. */
    public record ItemDroppedPacket(DroppedItemState item) {}

    /** 맵 위 아이템이 집어졌거나 만료되어 사라졌음. */
    public record ItemRemovedPacket(int id) {}

    /** 플레이어 인벤토리 전체 스냅샷(단순화: 증분 대신 풀 싱크). */
    public record InventoryPacket(java.util.Map<String, Integer> items) {}
}
