# 06 · 클라이언트 UI

`client/src/ui/` + `client/index.html` 의 DOM 영역. Phaser 캔버스 위에 절대 좌표로 떠 있는 HUD,
채팅, 인벤토리 창, 픽업 로그가 공존한다.

## 1. 왜 DOM HUD 인가

게임 렌더(Phaser) 와 인터랙티브 텍스트/버튼 UI 는 다른 스택을 쓰는 게 효율적이다:

- 텍스트 입력(채팅) · 드래그(인벤토리) · 더블클릭(아이템 사용) 은 DOM 이 압도적으로 단순
- HP 바 같이 자주 갱신되지 않는 요소는 DOM/CSS 의 변화 추적이 GPU 트윈보다 가볍다
- 시각 디버깅·a11y 는 DOM 측 도구가 풍부

대신 충돌(키 입력 가로채기, 마우스 통과) 처리에 신경을 써야 한다 — pointer-events 와 keyboard capture 토글이 곳곳에 등장.

## 2. 레이아웃 한눈에

```
┌──────────────────────────────────────────────────────────────────┐
│ [좌상단 HUD]                                                     │
│   캐릭터명 / 레벨 · HP 바 · MP 바 · EXP 바 · ATK MAXHP 텍스트   │
│   스킬 슬롯 (1/2/3 · 쿨다운 카운트다운)                         │
│                                                                  │
│ [Phaser 캔버스]                                                  │
│                                                                  │
│ ┌─ 인벤토리 창(드래그 가능, 위치 저장) ────────┐                 │
│ │ [헤더: 인벤토리 · hint · 닫기]              │                 │
│ │ [탭: 장비 / 소비 / 기타]                    │                 │
│ │ [그리드: 6×4 슬롯, hover 툴팁]              │                 │
│ └──────────────────────────────────────────────┘                 │
│                                                                  │
│ [좌하단 픽업 로그]            [중앙하단 채팅]                    │
│   +120 EXP                       chat-log (ghost/expanded)      │
│   +50 메소                       chat-input (Enter 포커스)      │
│   + 빨간 포션 ×2                                                │
└──────────────────────────────────────────────────────────────────┘
```

## 3. HudView

[`HudView.ts`](../client/src/ui/HudView.ts) 가 좌상단 HUD + 인벤토리 창 DOM 의 "유일한" 진입점.

### 3.1 갱신 메서드

| 메서드 | 트리거 패킷 | 효과 |
|---|---|---|
| `updateStats({attack,maxHp,maxMp,currentHp,currentMp})` | `STATS` | HP/MP 텍스트·바 |
| `updateHpImmediate(currentHp,maxHp)` | `PLAYER_DAMAGED` | STATS 패킷 지연 대비 즉시 반영 |
| `updateExp(level, exp, toNext)` | `PLAYER_EXP` | EXP 바 + 레벨 텍스트 |
| `updateMeso(meso)` | `MESO` | 우측 메소 표시 |
| `updateInventory(items)` | `INVENTORY` | 그리드 재렌더 (현 탭 필터) |
| `updateEquipment(slots)` | `EQUIPMENT` | 슬롯 별 아이템 렌더 |
| `updateSkillCooldowns(now, until)` | (60Hz GameScene 호출) | `cd-<id>` 카운트다운 |

### 3.2 인벤토리 창

- `bindInventoryInteractions()` — 탭 클릭 + hover 툴팁 + 드래그 + 사용자 액션 이벤트 위임
- `onInventoryAction((action) => ...)` — `equip / unequip / use / drop` 4종을 `GameScene` 으로 콜백
- `setAccountKey(name)` — localStorage 네임스페이스 (정렬·드래그 위치를 계정 단위로 분리 저장)

#### 드래그 이동

헤더를 잡아 창을 이동시키고, 위치를 `localStorage('inv-pos:<계정>')` 에 보관:

```ts
header.addEventListener('mousedown', (e) => {
  if ((e.target as HTMLElement).closest('.close-btn')) return;
  const rect = win.getBoundingClientRect();
  win.style.transform = 'none';                 // transform 중앙 정렬을 한 번만 벗긴다
  win.style.left = `${rect.left}px`;
  win.style.top  = `${rect.top}px`;
  // ...mousemove 로 left/top 갱신, mouseup 시 saveInventoryPosition
});
```

화면 밖으로 완전히 빠지지 않도록 `nx = max(40 - rect.width, originLeft + dx)` 와 같이 클램핑.

#### 슬롯 드래그 정렬

슬롯끼리 드래그하면 `InventoryOrder` 에 저장된 순서가 갱신되어 다음 렌더에 반영. 이 로직은 `data/InventoryOrder.ts` 에 있다 — Set 같은 의도지만 인덱스 보존이 핵심.

### 3.3 인벤토리 위치/순서 저장 키

| 키 | 내용 |
|---|---|
| `inv-pos:<account>` | 인벤토리 창 left/top |
| `inv-order:<account>:<tab>` | 탭 별 슬롯 순서 |

## 4. ChatController

[`ChatController.ts`](../client/src/ui/ChatController.ts) — 채팅 DOM 전담.

### 4.1 상호작용 흐름

```
게임 중 Enter ─▶ 입력창 포커스 (input.focus)
입력창에서:
   Enter   ─▶ 텍스트 trim → CHAT 패킷 송신 → blur
   Escape  ─▶ value 클리어 + blur
   ↑↓←→    ─▶ blur (다음 키부터 게임 입력으로)
포커스 ON  ─▶ Phaser 글로벌 키 캡처 OFF, .expanded 클래스
포커스 OFF ─▶ 캡처 ON, resetKeys(), .expanded 제거
```

### 4.2 ghost

새 메시지 도착 시 `box.classList.add('ghost')` 로 4초간 반투명 표시. 포커스 중이면 건너뜀.

### 4.3 라우팅

```ts
send(text) {
  if (text.startsWith('/w ') || text.startsWith('/귓 ')) {
    // /w nickname message → WHISPER
  } else {
    // ALL
  }
}
```

`onReceive(scope, sender, msg)` 가 `WHISPER:<name>` 접두사를 파싱해 `[귓] sender → partner: msg` 형태로 표시.

### 4.4 시스템 메시지

`append('sys', text)` 가 파란색 sys 라인을 추가. 현재 사용처:

- 서버 `ERROR` 패킷 (`[오류] ...`)
- `/w` 사용법 안내

> 메소·EXP·아이템 획득 알림은 더 이상 채팅에 들어가지 않는다 — `PickupLog` 로 분리.

## 5. EffectFactory

[`EffectFactory.ts`](../client/src/ui/EffectFactory.ts) — Phaser 트윈 기반 일회성 이펙트.

| 메서드 | 용도 |
|---|---|
| `spawnDamageNumber(x, y, dmg)` | 빨간 -N 숫자 상승+페이드 |
| `spawnAttackSlash(x, y, facing)` | Space 기본 공격 슬래시 |
| `spawnSkillEffect(skillId, x, y, dir)` | 스킬별 색상/형태(슬래시 또는 회복 원) |
| `spawnExpGain(x, y, gained)` | "+N EXP" 위로 떠오르는 텍스트 |
| `spawnLevelUp(x, y, level)` | "LEVEL UP! Lv N" 큰 텍스트 |
| `flashSpriteDamage(sprite)` | iframe 1500ms 동안 4회 깜빡임 |

모든 객체는 트윈 `onComplete` 에서 `destroy()` 되어 누수 없음.

## 6. PickupLog (좌하단)

[`PickupLog.ts`](../client/src/ui/PickupLog.ts) — 채팅과 분리된 획득 알림 패널.

```ts
push(kind: 'exp' | 'meso' | 'item', text: string) {
  const div = document.createElement('div');
  div.className = `entry ${kind}`;
  div.textContent = text;
  container.appendChild(div);
  while (container.children.length > MAX_ENTRIES) container.firstChild?.remove();
  setTimeout(() => div.classList.add('fading'), HOLD_MS);   // 4s
  setTimeout(() => div.remove(),                HOLD_MS + FADE_MS);
}
```

### 6.1 색상 코딩

| kind | CSS color | 사례 |
|---|---|---|
| `exp` | `#aee1ff` (파랑) | "+120 EXP" |
| `meso` | `#ffe27a` (금) | "+50 메소" |
| `item` | `#c7ffb0` (녹) | "+ 빨간 포션 ×2" |

### 6.2 호출 시점

- `onMeso` (gained > 0) — 메소 획득
- `onPlayerExp` (gained > 0) — EXP 획득. 동시에 `effects.spawnExpGain` 도 호출(플로팅 강조).
- `onInventory` — 이전 스냅샷과 diff 해 양수 delta 인 아이템 ID 마다 push

### 6.3 인벤 diff 의 함정

`onInventory` 는 JOIN 직후에도 호출된다. 그때의 "복원된 인벤토리" 를 모두 "획득" 으로 보내면
사용자가 로그인할 때마다 알림 폭격을 맞는다. 그래서 `inventoryInitialized` 플래그로 첫 호출은 건너뛴다.

```ts
if (this.inventoryInitialized) {
  this.reportInventoryGains(this.lastInventorySnapshot, items);
}
this.lastInventorySnapshot = { ...items };
this.inventoryInitialized = true;
```

장비 착용/드롭으로 인한 **감소** 는 알리지 않는다. 언장착(unequip) 으로 인한 증가는 정상적으로 알림 — 이건 사용자가 직접 의도한 행동이라 거슬리지 않는다.

## 7. TextureFactory

`scenes/TextureFactory.ts` 가 런타임에 캔버스로 단순 도형(사각형/원형) 텍스처를 생성한다.
외부 에셋이 없어도 동작하게 만든 학습 보조 장치 — 메이플스토리 실제 에셋은 저작권 이슈로
공개 배포가 불가하므로 자체 에셋(또는 CC0) 으로 전환할 자리를 마련해 둔 셈.

## 8. 다음 문서

- 패킷 명세: [07-protocol.md](./07-protocol.md)
- 패턴 학습 노트: [08-design-patterns.md](./08-design-patterns.md)
