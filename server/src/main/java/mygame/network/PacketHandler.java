package mygame.network;

/**
 * 단일 패킷 타입을 처리하는 전략.
 *
 * <p>함수형 인터페이스라 {@code dispatcher.register("MOVE", ctx -> ...)}
 * 처럼 람다로 등록 가능. 상태가 필요한 핸들러는 별도 클래스로 분리한다.
 */
@FunctionalInterface
public interface PacketHandler {
    void handle(PacketContext ctx) throws Exception;
}
