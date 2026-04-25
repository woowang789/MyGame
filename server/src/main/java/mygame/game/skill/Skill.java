package mygame.game.skill;

/**
 * 스킬(Command 패턴의 Command).
 *
 * <p>학습 포인트:
 * <ul>
 *   <li>각 스킬은 {@code apply(ctx)} 를 통해 "호출 가능한 행동" 으로 캡슐화된다.
 *       호출자(GameServer)는 분기 없이 {@code skill.apply(ctx)} 한 줄로 실행.
 *   <li>{@code sealed} + {@code permits} 로 구현체 집합을 컴파일 타임에 고정.
 *       전직 Phase 에서 {@code permits} 에 직업 스킬을 추가하는 방식으로 확장.
 *   <li>{@link #job()} 은 "이 스킬이 속한 직업 태그". 현재는 {@code "BEGINNER"} 만.
 *       전직 후 해당 직업 태그의 스킬만 사용 가능하도록 검증한다.
 * </ul>
 */
public sealed interface Skill permits BasicAttack, PowerStrike, TripleBlow, Recovery {

    /** 초보자 공용 스킬 태그. 전직 전 모든 캐릭터가 사용 가능. */
    String JOB_BEGINNER = "BEGINNER";

    /** 고유 ID. 패킷·DB·클라 단축키 매핑의 키. */
    String id();

    /** 표시용 한국어 이름. */
    String name();

    /** 소속 직업 태그. 현재는 BEGINNER 만. */
    String job();

    /** 사용 시 소모 MP. 0 가능(패시브/무료). */
    int mpCost();

    /** 재사용 대기 시간(ms). */
    long cooldownMs();

    /**
     * 실제 효과 적용. MP 차감·쿨다운 체크는 호출자(SkillService)가 사전에 수행.
     * 구현체는 여기서 데미지·치유·브로드캐스트만 담당.
     */
    void apply(SkillContext ctx);
}
