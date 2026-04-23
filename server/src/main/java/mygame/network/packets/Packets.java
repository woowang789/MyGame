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

    /** 접속 완료 응답. 자기 자신의 정보와 맵에 이미 있던 다른 플레이어 목록을 준다. */
    public record WelcomePacket(int playerId, PlayerState self, java.util.List<PlayerState> others) {}

    /** 다른 플레이어가 맵에 들어왔음을 알리는 브로드캐스트. */
    public record PlayerJoinedPacket(PlayerState player) {}

    /** 다른 플레이어가 이동했음. 고빈도(10Hz+) 이므로 필드 수를 최소화. */
    public record PlayerMovedPacket(int id, double x, double y, double vx, double vy) {}

    /** 다른 플레이어가 나갔음. */
    public record PlayerLeftPacket(int id) {}

    /** 플레이어의 공개 상태(신규 접속자에게 현재 스냅샷을 줄 때 사용). */
    public record PlayerState(int id, String name, double x, double y) {}
}
