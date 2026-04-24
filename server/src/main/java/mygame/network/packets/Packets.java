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

    // Phase L — 인증. LOGIN/REGISTER 성공 후에만 JOIN 이 허용된다.
    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String username, String password) {}

    /**
     * 클라이언트 입장 요청. Phase L 이후에는 세션이 이미 인증된 상태여야 한다.
     * name 은 신규 캐릭터 생성용(계정에 캐릭터가 없을 때만 사용).
     */
    public record JoinRequest(String name) {}

    /** S→C 인증 결과. 실패 시 error 에 사유, 성공 시 accountId 반환. */
    public record AuthResponse(boolean success, String error, long accountId, String username) {}

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

    /** 스킬 메타(클라 HUD · 쿨다운 예측용). */
    public record SkillMetaEntry(String id, String name, int mpCost, long cooldownMs) {}

    /**
     * 정적 게임 메타데이터. 서버 레지스트리를 단일 진실 원천(SSoT)으로 삼기 위해
     * JOIN 직후 한 번 전송한다. 클라 HUD 의 장비 판별과 스킬 쿨다운 HUD 는
     * 이 패킷으로 초기화된다.
     */
    public record MetaPacket(
            java.util.List<String> equipmentIds,
            java.util.List<SkillMetaEntry> skills
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

    /** 현재 소지 메소(재화) 알림. 본인에게만 전송. gained 는 이번 변화량(획득 +, 소비 -). */
    public record MesoUpdatedPacket(long meso, long gained) {}

    /** 채팅 요청. scope="ALL"(맵 전체) 또는 "WHISPER"(target 이름 지정). */
    public record ChatRequest(String scope, String target, String message) {}

    /** 채팅 수신 브로드캐스트/개별 전달. */
    public record ChatMessage(String scope, String sender, String message) {}

    // Phase D — 인벤토리 상호작용

    /** 소비 아이템 사용 요청. 해당 아이템 1개를 소모하고 효과를 적용. */
    public record UseItemRequest(String templateId) {}

    /** 인벤 아이템을 월드에 떨어뜨리는 요청. amount 만큼 차감 후 DroppedItem 생성. */
    public record DropItemRequest(String templateId, int amount) {}

    // Phase I — 장비

    /** 인벤토리의 장비 아이템을 해당 슬롯에 장착 요청. */
    public record EquipRequest(String templateId) {}

    /** 슬롯 장비 해제 요청. slot 은 EquipSlot enum 이름("WEAPON" 등). */
    public record UnequipRequest(String slot) {}

    /**
     * 플레이어 장비 전체 스냅샷. 본인 갱신 · 타인에게 외형 동기화 모두에 쓰인다.
     * slots 는 slot enum 이름 → 아이템 템플릿 ID.
     */
    public record EquipmentPacket(int playerId, java.util.Map<String, String> slots) {}

    /** 최종 스탯(장비 포함) 알림. 본인에게만 전송. 현재 hp/mp 포함. */
    public record StatsPacket(int maxHp, int maxMp, int attack, int speed, int currentHp, int currentMp) {}

    // Phase M — 스킬

    /** 스킬 사용 요청. 방향은 공격 박스 판정용. */
    public record UseSkillRequest(String skillId, String dir) {}

    /** 스킬 발동 브로드캐스트. 맵 내 모든 클라가 이펙트 렌더링. */
    public record SkillUsedPacket(int playerId, String skillId, String dir) {}

    // Phase N — 피격

    /**
     * 플레이어 피격 브로드캐스트. 공격자(attackerId)가 몬스터이면 음수(-monsterId).
     * currentHp 는 남은 HP, dmg 는 이번 타격으로 들어간 피해량.
     */
    public record PlayerDamagedPacket(int playerId, int dmg, int currentHp, int maxHp, int attackerId) {}

    /** 플레이어 사망 브로드캐스트. 클라는 스프라이트를 회색/반투명으로 표시. */
    public record PlayerDiedPacket(int playerId) {}

    /**
     * 플레이어 리스폰 브로드캐스트. mapId 가 바뀔 수 있어 맵 이동과 유사하게 취급.
     * 본인에게는 HP/MP 가 찬 상태로 STATS 도 별도 송신한다.
     */
    public record PlayerRespawnedPacket(int playerId, String mapId, double x, double y, int hp) {}
}
