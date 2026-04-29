package mygame.admin.audit;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 행위 감사 로그 저장소.
 *
 * <p>모든 mutating 명령(강제 저장, 향후 정지/메소 조정 등)은 실행 전후로 1 건씩
 * append-only 로 기록된다. UI 의 "감사" 탭과 사고 조사용. 삭제·수정 API 는 두지 않음.
 */
public interface AuditLogRepository {

    record Entry(long id, Long adminId, String adminUsername, String action,
                 String payload, Instant createdAt) {}

    /** payload 는 JSON 문자열 권장(자유 텍스트 가능). null 허용. */
    void append(Long adminId, String adminUsername, String action, String payload);

    /** 최신순으로 limit 개. 화면에는 페이지네이션이 거의 필요 없을 정도의 빈도. */
    List<Entry> recent(int limit);
}
