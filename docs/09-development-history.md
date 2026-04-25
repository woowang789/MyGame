# 09 · 개발 이력 · 리팩토링

## 1. Phase 진행 (CLAUDE.md 로드맵)

| Phase | 목표 | 학습 주제 | 상태 |
|---|---|---|---|
| A | Java-WebSocket 에코 + Phaser 캐릭터 | 클래스/콜백 | 완료 |
| B | 중력·점프, Tiled 타일맵 | JSON 파싱 | 완료 |
| C | 멀티 플레이어 위치 동기화 | ConcurrentHashMap, 브로드캐스트 | 완료 |
| D | 다중 맵 + 포털 | 캡슐화, 컬렉션 설계 | 완료 |
| E | 몬스터 스폰 + AI | **State 패턴**, 게임 루프 | 완료 |
| F | 공격/HP/데미지 | 다형성 (스킬), Command 패턴 | 완료 |
| G | EXP/레벨업 | **Observer 패턴**, EventBus | 완료 |
| H | 아이템 드롭 + 인벤토리 | **Factory 패턴**, 제네릭 | 완료 |
| I | 장비 + 스탯 계산 | **Decorator 패턴**, 불변 | 완료 |
| J | 채팅 (ALL/귓속말) | 라우팅, 패킷 확장 | 완료 |
| K | DB 연동 (JDBC) | Repository 패턴, 트랜잭션 | 완료 (HP/MP 영속화는 보류) |
| L | 인증 + 로그인 서버 | PBKDF2, 세션 | 완료 (단일 프로세스) |
| M | (선택) SpringBoot 마이그레이션 | DI, JPA | 미진행 |

## 2. UI 개선 작업 (2026-04-24)

### 2.1 인벤토리 창

- I 키로 토글, Esc/X 닫기
- 24슬롯 그리드, 탭(장비/소비/기타)
- 드래그 정렬 (`InventoryOrder` 에 저장, 계정별 네임스페이스)
- 더블클릭: 장비 → 장착, 소비 → 사용, Shift+더블클릭 → 버리기
- 호버 툴팁: 빈 슬롯 제외

### 2.2 추가 개선

- 포션 툴팁 설명 갱신 ("사용 미구현" → 실제 효과값)
- 인벤토리 헤더 레이아웃 정리 (title flex-shrink, hint ellipsis)

## 3. 리팩토링 (2026-04-24~25)

코드 리뷰에서 식별된 Top 10 중 7건 처리. 자세한 식별 결과는 별도 리뷰 보고서에 있다.

### 3.1 동시성 버그 수정

| 위치 | 문제 | 수정 |
|---|---|---|
| `Player.gainExp()` | exp/level 갱신 사이 중간 상태 노출 | `synchronized` 추가 |
| `Player.tryActivateSkill()` | get → put 사이 두 스레드 동시 통과 | `ConcurrentHashMap.compute()` 단일 원자 연산 |
| `GameServer.handleDropItem` 의 `60_000` 리터럴 | DROP_TTL_MS 상수와 중복 | 상수 참조로 통일 |

### 3.2 파일 분리

**클라**:
- `GameScene.ts` 813줄 → 622줄 (-23%)
  - `ChatController.ts` (130줄) 추출 — 채팅 입력·로그·ghost
  - `EffectFactory.ts` (129줄) 추출 — 모든 일회성 이펙트
- `GameScene.update()` 116줄 → 5개 메서드로 분할
  - `handleMovement` / `handleActions` / `handleSkills` / `handlePortalOrJump` / `sendNetworkUpdates`

**서버**:
- `GameServer.java` 619줄 → 359줄 (-42%)
  - `ChatHandler.java` (59줄) — CHAT
  - `InventoryHandler.java` (160줄) — PICKUP/EQUIP/UNEQUIP/USE_ITEM/DROP_ITEM
  - `CombatHandler.java` (104줄) — ATTACK/USE_SKILL
  - `SessionNotifier.java` (48줄) — 공통 송신 헬퍼

### 3.3 메타 SSoT 화

서버에 `META` 패킷 신설. JOIN 직후 한 번 전송:

```json
{
  "type": "META",
  "equipmentIds": ["wooden_sword", "iron_sword", "leather_cap", "cloth_armor"],
  "skills": [
    {"id": "power_strike", "name": "파워 스트라이크", "mpCost": 8, "cooldownMs": 1500},
    ...
  ]
}
```

클라 `HudView.applyMeta()` 가 이 데이터로 `EQUIPMENT_IDS`/`SKILL_META` 를 채운다. 서버에 새 장비 추가 시 클라 수정 0 — 단일 진실 원천.

### 3.4 보류한 항목

- Zod 패킷 스키마: 의존성 추가 + 모든 패킷 스키마 정의 비용. YAGNI 원칙으로 미도입.
- HudView 분리: 412줄로 800줄 한계 내. "성급한 추상화 금지" 원칙으로 보류.

## 4. 신규 기능 (2026-04-24~25)

### 4.1 인벤토리 창 헤더 드래그

- `cursor: move` 헤더로 잡고 이동
- localStorage `inv-pos:<account>` 에 위치 저장 → 재접속 복원
- 화면 바깥으로 완전히 빠지지 않도록 클램핑(헤더 일부는 항상 잡힘)
- 닫기 버튼 영역은 드래그 시작에서 제외

### 4.2 픽업 로그 분리

EXP/메소/아이템 획득을 채팅창에서 분리해 좌하단 전용 영역(`#pickup-log`)으로 이동.

| 종류 | 색상 | 이전 동작 | 변경 후 |
|---|---|---|---|
| EXP | 파랑 (`#aee1ff`) | 플레이어 위 플로팅 텍스트 | 플로팅 + 좌하단 로그 |
| 메소 | 금 (`#ffe27a`) | 채팅 sys | 좌하단 로그 전용 |
| 아이템 | 녹 (`#c7ffb0`) | 알림 없음 | 좌하단 로그 (인벤 diff) |

아이템 획득 감지는 서버 프로토콜 변경 없이 클라 측 인벤 스냅샷 diff 로 해결. JOIN 직후 복원분은 `inventoryInitialized` 플래그로 첫 호출만 건너뛴다.

각 라인은 4초 표시 후 0.6초 페이드아웃, 최대 6줄 유지(초과 시 가장 오래된 것부터 제거).

## 4.3 전투 진입점 통일 (2026-04-25)

기본 공격을 별도 핸들러로 두지 않고 **스킬 시민**으로 합류시켜 전투 경로를 단일화.

### 변화

- `BasicAttack` 스킬 신설 (`mpCost=0`, `cooldownMs=350`). `Skill.permits` · `SkillRegistry` 등록
- 모든 스킬이 `CombatService.damageMonster` 한 진입점으로 데미지 적용 — 패킷 송출 + 넉백 + 사망 마무리가 자동
- `SkillContext` 에 `CombatService` 추가, `SkillOutcome` 제거 (호출자 외부에서 결과를 다시 순회할 이유가 없어짐)
- `CombatHandler.handleAttack` 제거. `Packets.AttackRequest` 제거. `ATTACK` 패킷 디스패처 등록 제거
- 클라 Space 입력 → `USE_SKILL{skillId:"basic_attack"}` 송신
- 클라 `onSkillUsed` 가 본인의 basic_attack SKILL_USED 는 무시(입력 즉시 그린 슬래시와 중복 방지), 타인의 것만 슬래시 렌더

### 효과

- 핸들러 1개 감소, `handleUseSkill` 도 outcome 순회 블록이 사라져 ~50줄 → ~30줄
- 자동 공격 속도 제한이 자연스럽게 도입(350ms 쿨다운)
- 다른 플레이어의 기본 공격 시각화가 가능해짐(SKILL_USED 브로드캐스트 경유)
- META 패킷에 basic_attack 도 자동 포함되어 SSoT 일관성 유지

## 5. 검증 방식

리팩토링과 기능 추가 모두 다음을 통과시켰다:

| 검증 | 명령 |
|---|---|
| 서버 컴파일 | `cd server && ./gradlew compileJava` |
| 클라 타입체크 | `cd client && ./node_modules/.bin/tsc --noEmit` |
| 클라 정적 빌드 | `./node_modules/.bin/vite build` |

런타임 회귀 테스트는 본 학습 프로젝트에 자동화된 E2E 가 없는 상태라 수동 진행. 향후 Phase 에서
서버 단위 테스트(JUnit 5 + AssertJ)와 클라 시각 회귀(Playwright) 도입 여지가 있다.

## 6. 알려진 한계 / TODO

- HP/MP 영속화 없음 — 세션마다 풀 충전
- 게임 루프가 권위 좌표 검증을 하지 않음 — 클라 보고 좌표를 그대로 신뢰
- 단일 채널 구조 — `Channel` 클래스가 미사용
- 다른 계정으로 같은 캐릭터 이름 거부는 `players.name UNIQUE` 로 처리. 별도 닉네임 검증 규칙(특수문자/길이) 미구현
- 클라 패킷 캐스팅(`p.x as number` 등) 런타임 검증 없음 — 서버 측 record 변경 시 컴파일러가 잡지 못함
- 자동화 테스트(서버 JUnit, 클라 Playwright) 미구축

## 7. 참고

- 현재 머지 이력: `git log --oneline main` 으로 시각화
- 리팩토링 커밋 핵심: `8aaca48 refactor:` , 머지 `0ad376f`, `153c7c8`, `2805602`
