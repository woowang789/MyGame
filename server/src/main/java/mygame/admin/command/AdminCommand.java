package mygame.admin.command;

import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 관리자 mutating 명령의 공통 추상.
 *
 * <p>execute 안에서 도메인 변경 + audit 기록을 한 흐름으로 묶어, 핸들러가 audit 호출을
 * 빠뜨리는 사고를 구조적으로 차단한다. Command 패턴 학습 사례 — 추후 KickPlayer,
 * ResetPassword 같은 명령이 붙으면 동일 인터페이스로 확장.
 */
public interface AdminCommand {

    /** 감사 로그에 기록될 액션 키워드 (예: {@code FORCE_SAVE}). */
    String name();

    /**
     * 명령 실행. 구현체는 도메인 변경 후 {@code audit.append(...)} 를 직접 호출한다.
     *
     * @param actor   호출 admin 세션 — audit 의 actor 정보를 채우는 데 사용
     * @param audit   감사 로그 저장소
     * @return 화면에 표시할 결과 메시지
     */
    String execute(Session actor, AuditLogRepository audit);
}
