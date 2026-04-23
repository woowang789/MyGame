package mygame.game.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 단순한 인-프로세스 이벤트 버스 (Observer 패턴의 Subject).
 *
 * <p>{@link CopyOnWriteArrayList} 로 구독자 목록을 관리해,
 * 발행 도중 구독자가 변경되어도 순회가 안전하다.
 * 발행은 동기(publisher 스레드)에서 실행되므로 구독자는 가벼워야 한다.
 */
public final class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final List<Consumer<GameEvent>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<GameEvent> handler) {
        subscribers.add(handler);
    }

    public void publish(GameEvent event) {
        for (Consumer<GameEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (Exception e) {
                log.error("이벤트 구독자 오류 [{}]", event.getClass().getSimpleName(), e);
            }
        }
    }
}
