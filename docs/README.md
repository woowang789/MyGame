# MyGame 구현 문서

메이플스토리풍 2D MMO 학습 프로젝트의 구현 상세 문서. 코드와 함께 읽도록 작성되었으며,
"무엇을(what)" 보다 "왜 그렇게(why)" 에 무게를 둔다.

## 문서 구성

| # | 문서 | 내용 |
|---|---|---|
| 01 | [아키텍처 개요](./01-architecture.md) | 전체 시스템·런타임·통신 흐름·디렉터리 구조 |
| 02 | [서버 — 네트워크 계층](./02-server-network.md) | WebSocket 서버, 패킷 디스패처, 핸들러 분리 |
| 03 | [서버 — 게임 도메인](./03-server-game-domain.md) | World/Map/Player/Monster/AI/Combat/Skill/Stat |
| 04 | [서버 — 영속성 · 인증](./04-server-persistence.md) | H2 + JDBC, Repository, AuthService, 세션 |
| 05 | [클라이언트 — 아키텍처](./05-client-architecture.md) | Phaser Scene, 모듈 분할, 타일맵 로드 |
| 06 | [클라이언트 — UI](./06-client-ui.md) | HUD, Inventory, ChatController, EffectFactory, PickupLog |
| 07 | [패킷 프로토콜 명세](./07-protocol.md) | 모든 패킷 타입 · 방향 · 필드 |
| 08 | [디자인 패턴 학습 노트](./08-design-patterns.md) | 적용된 패턴(Command/State/Observer/Factory/Decorator) |
| 09 | [개발 이력 · 리팩토링](./09-development-history.md) | Phase 진행 기록 + 최근 리팩토링 결과 |

## 빠른 시작

서버 ([CLAUDE.md](../CLAUDE.md) 참고):

```bash
cd server && ./gradlew run
```

클라이언트:

```bash
cd client
npm install
npm run dev
```

브라우저에서 Vite 가 안내하는 URL(기본 `http://localhost:5173`)로 접속 → 회원가입/로그인 후 입장.

## 학습 원칙

- Spring 의 마법(DI/AOP)을 직접 구현해 본질을 체감
- 라이브러리 도입 전 이유를 기록
- 성급한 추상화 금지 — Rule of Three
- 디자인 패턴 학습은 [08-design-patterns.md](./08-design-patterns.md) 에 누적

## 서버 ↔ 클라이언트 한눈에

```
[브라우저]                                       [JVM 21]
┌──────────────────────┐    JSON over WebSocket    ┌──────────────────────┐
│ Phaser 3 + TypeScript│  ─────────────────────▶  │ Java-WebSocket 서버  │
│ Scene · HUD · DOM    │  ◀─────────────────────  │ World · GameLoop · DB│
└──────────────────────┘                          └──────────────────────┘
```

상세 흐름은 [01-architecture.md](./01-architecture.md) 의 "런타임 토폴로지" 절 참조.
