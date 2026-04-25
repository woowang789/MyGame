# 05 · 클라이언트 아키텍처

`client/` 디렉터리. Phaser 3 + TypeScript + Vite 빌드. 게임 렌더는 Phaser, HUD 는 DOM(인덱스 HTML)
하이브리드. 두 영역을 격리해 각각이 무거워지지 않게 한다.

## 1. 부트스트랩

```
main.ts
  ├─ LoginScreen  ← DOM 화면. 로그인/회원가입 패킷 처리.
  └─ 인증 성공 시 GameScene 으로 전환 (Phaser.Game 인스턴스 생성)
```

`LoginScreen` 은 Phaser 가 시작되기 *전* 의 DOM 단독 화면이다. 인증이 끝나면 `AuthedSession` 객체와
열려 있는 `WebSocketClient` 를 GameScene 의 생성자에 넘긴다.

## 2. WebSocketClient

[`WebSocketClient.ts`](../client/src/network/WebSocketClient.ts) 는 단순한 라우터:

```ts
class WebSocketClient {
  on(type: string, handler: (p: Packet) => void): void
  send(packet: Packet): void
  onClose(handler: () => void): void
  isOpen: boolean
}
```

수신 시 `JSON.parse(event.data).type` 을 키로 등록된 핸들러를 호출. 등록된 핸들러가 없는 패킷은 콘솔 경고만 남기고 게임은 계속 진행.

## 3. GameScene

`scenes/GameScene.ts` 가 메인 씬. 책임:

- 입력 처리 (키 → 서버 패킷)
- 서버 패킷 수신 → 적절한 모듈에 위임
- 카메라/플레이어 스프라이트 관리
- 자동 픽업 / MOVE 패킷 주기 송신

리팩토링 후 `update()` 는 5개 보조 메서드로 분할:

```ts
override update(time: number, delta: number): void {
  if (this.chat.isFocused() || this.isDead) { /* 입력 차단 */ return; }
  this.handleMovement();
  this.handleActions();        // Space/Q/E/I/Esc
  this.handleSkills(time);     // 1/2/3 + 쿨다운 HUD
  this.handlePortalOrJump();   // ↑ 키 이중 용도
  for (const r of this.remotes.values()) r.update(delta);
  for (const m of this.monsters.values()) m.update(delta);
  this.sendNetworkUpdates(time);
}
```

큰 분기·매직 넘버는 상수로:

```ts
const MOVE_SPEED = 200;
const JUMP_VELOCITY = -450;
const MOVE_PACKET_INTERVAL_MS = 100;     // 10Hz 송신
const KNOCKBACK_SPEED_X = 220;
const KNOCKBACK_VELOCITY_Y = -180;
const KNOCKBACK_DURATION_MS = 220;
```

## 4. 서브 모듈

`GameScene` 에서 떼어낸 모듈은 [06-client-ui.md](./06-client-ui.md) 에서 자세히 다룬다. 요약:

| 모듈 | 책임 |
|---|---|
| `MapController` | Tiled 타일맵 로드 · 포털 좌표 · `findOverlappingPortalIndex()` |
| `HudView` | DOM HUD 갱신 (HP/MP/EXP/스킬바/인벤토리/장비) |
| `ChatController` | 채팅 입력창 · 로그 · ghost 노출 |
| `EffectFactory` | 이펙트 트윈 (slash/damage/skill/exp/level-up/flash) |
| `PickupLog` | 좌하단 획득 알림 |
| `TextureFactory` | 런타임 캔버스로 플레이어/타일 텍스처 생성 (에셋 미사용 모드) |

## 5. 엔티티 스프라이트

`entities/` 의 세 클래스는 동일 패턴을 따른다 — *서버에서 받은 권위 좌표를 부드럽게 추적*:

```ts
class RemotePlayer {
  setTarget(x: number, y: number) { this.targetX = x; this.targetY = y; }
  update(delta: number) {
    const lerp = 1 - Math.exp(-delta / 80);     // 시간 비례 감쇠
    this.sprite.x += (this.targetX - this.sprite.x) * lerp;
    this.sprite.y += (this.targetY - this.sprite.y) * lerp;
  }
}
```

지수 감쇠 보간이라 패킷 주기와 프레임레이트가 달라도 자연스럽게 따라간다.

## 6. 서버 메타 수신

JOIN 직후 서버가 보내는 `META` 패킷은 클라의 정적 상수를 채운다:

```ts
this.network.on('META', (p) => {
  applyMeta({
    equipmentIds: (p.equipmentIds ?? []) as string[],
    skills: (p.skills ?? []) as { id, name, mpCost, cooldownMs }[]
  });
});
```

`HudView` 의 `EQUIPMENT_IDS`(Set), `SKILL_META`(Record) 가 이 시점에 채워진다. 그 전까지 빈 객체로 안전하게 작동하도록 모든 호출처에서 가드되어 있다 — 패킷 도착 전 키 입력이 와도 크래시 없음.

이로써 **서버 `ItemRegistry` / `SkillRegistry` 가 단일 진실 원천(SSoT)** 이 된다. 서버에 새 장비를 추가하면 클라 코드 수정 없이 자동으로 인식.

## 7. 입력 — 채팅 충돌 회피

채팅창 포커스 중에는 Phaser 의 글로벌 키 캡처를 끄고, blur 시 다시 켠다.
방향키 입력은 채팅에서 의도적으로 blur 시켜 즉시 게임으로 흘러가게 한다.

```ts
input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { /* 전송 후 blur */ }
  else if (arrowKeys.has(e.key)) { input.blur(); }    // ← 핵심
});
input.addEventListener('focus', () => this.scene.input.keyboard?.disableGlobalCapture());
input.addEventListener('blur',  () => {
  this.scene.input.keyboard?.enableGlobalCapture();
  this.scene.input.keyboard?.resetKeys();             // 잔류 keydown 클리어
});
```

`resetKeys()` 가 빠지면 채팅 중 눌렸던 Q/E 가 blur 직후 `JustDown` 으로 흘러들어가 의도치 않게 장비를 착용/해제하는 버그가 발생한다.

## 8. 사망 / 부활 / 넉백

- `PLAYER_DAMAGED` 수신 시 `effects.flashSpriteDamage(player)` + `applyPlayerKnockback(attackerId)`
- 넉백 중에는 `update()` 가 좌우 속도를 덮어쓰지 않아 자연스럽게 밀려난다
- `PLAYER_DIED` 수신 시 `isDead = true` → 모든 입력 차단, 스프라이트 회색·반투명
- `PLAYER_RESPAWN` 으로 복귀 시 `clearTint`/`alpha=1`/위치 초기화

## 9. 자동 픽업

```ts
if (time - this.lastPickupAttemptAt >= 200 && this.droppedItems.size > 0) {
  for (const it of this.droppedItems.values()) {
    if (Math.abs(it.body.x - px) <= 36 && Math.abs(it.body.y - py) <= 36) {
      this.lastPickupAttemptAt = time;
      this.network.send({ type: 'PICKUP' });
      break;
    }
  }
}
```

200ms 간격, 거리 36px 이내면 PICKUP 송신. 서버가 인벤 가득 차면 `ERROR` 응답 → 채팅창 sys.

## 10. 다음 문서

- UI 모듈 상세: [06-client-ui.md](./06-client-ui.md)
- 패킷 풀 명세: [07-protocol.md](./07-protocol.md)
