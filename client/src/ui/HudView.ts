import { ITEM_NAMES } from '../entities/DroppedItemSprite';
import { formatBonus, getItemMeta, ItemType } from '../data/ItemMeta';
import { loadOrder, mergeOrder, saveOrder } from '../data/InventoryOrder';

/* ITEM_NAMES 가 HudView 내부에서 equipment 슬롯 렌더에만 쓰이므로 유지. */

/** 인벤토리 창의 탭별 기본 슬롯 수. 아이템이 더 많으면 스크롤에 맡긴다. */
const INVENTORY_MIN_SLOTS = 24;

const TAB_LABEL: Record<ItemType, string> = {
  EQUIPMENT: '장비',
  CONSUMABLE: '소비',
  ETC: '기타'
};

/** 인벤/장비 슬롯에서 GameScene 이 처리할 사용자 액션. */
export type InventoryAction =
  | { kind: 'equip'; templateId: string }
  | { kind: 'unequip'; slot: string }
  | { kind: 'use'; templateId: string }
  | { kind: 'drop'; templateId: string; amount: number };

/**
 * HUD DOM 조작 전담.
 *
 * GameScene 에 DOM API(document.getElementById 등)와 Phaser 로직이 뒤엉키지
 * 않도록 분리했다. DOM 의존성이 테스트·리팩터링을 어렵게 만드는 주범이므로
 * 한 경로로 집중.
 *
 * 사용처: GameScene 이 서버 패킷을 받아 파싱한 뒤 이 뷰의 update* 메서드로 전달.
 */
export interface SkillMeta {
  readonly name: string;
  readonly cooldownMs: number;
  readonly mpCost: number;
}

/**
 * 서버 SkillRegistry 에서 내려오는 메타를 저장. META 패킷 수신 전까지는 빈 객체.
 * 클라에서 직접 상수로 선언하지 않는 이유: 서버가 단일 진실 원천(SSoT)이 되어
 * 한쪽만 수정해도 자동으로 동기화되도록 하기 위함.
 */
export const SKILL_META: Record<string, SkillMeta> = {};

/** 서버 ItemRegistry EQUIPMENT 목록. META 패킷 수신 시 채워진다. */
const equipmentIdSet = new Set<string>();
export const EQUIPMENT_IDS: ReadonlySet<string> = equipmentIdSet;

/** META 패킷으로 받은 장비 · 스킬 메타를 클라 런타임 상수로 반영. */
export function applyMeta(meta: {
  equipmentIds: readonly string[];
  skills: readonly { id: string; name: string; mpCost: number; cooldownMs: number }[];
}): void {
  equipmentIdSet.clear();
  for (const id of meta.equipmentIds) equipmentIdSet.add(id);
  for (const key of Object.keys(SKILL_META)) delete SKILL_META[key];
  for (const s of meta.skills) {
    SKILL_META[s.id] = { name: s.name, cooldownMs: s.cooldownMs, mpCost: s.mpCost };
  }
}

export class HudView {
  private readonly respawnOverlayDurationMs = 3000;
  /** 현 인벤토리 탭. updateInventory 가 이 값으로 필터링해 렌더한다. */
  private inventoryTab: ItemType = 'EQUIPMENT';
  /** 마지막 인벤토리 스냅샷. 탭 전환 시 서버 패킷 없이 재렌더하기 위해 보관. */
  private lastInventory: Record<string, number> = {};
  /** 드래그 · 저장 키 구분용. setAccountKey 로 로그인 직후 주입. */
  private accountKey = 'default';
  /** 인벤 슬롯에서 발생한 사용자 액션을 GameScene 으로 전달. */
  private inventoryActionHandler: ((action: InventoryAction) => void) | null = null;

  updateStats(stats: {
    attack: number;
    maxHp: number;
    maxMp: number;
    currentHp: number;
    currentMp: number;
  }): void {
    this.setText('eq-stats', `ATK ${stats.attack} · MAXHP ${stats.maxHp}`);
    this.setText('hp-text', `${stats.currentHp} / ${stats.maxHp}`);
    this.setWidthPct('hp-bar-fill', stats.maxHp > 0 ? (100 * stats.currentHp) / stats.maxHp : 0);
    this.setText('mp-text', `${stats.currentMp} / ${stats.maxMp}`);
    this.setWidthPct('mp-bar-fill', stats.maxMp > 0 ? (100 * stats.currentMp) / stats.maxMp : 0);
  }

  /** 피격 시 STATS 패킷 지연 대비. HP 숫자/바만 즉시 반영. */
  updateHpImmediate(currentHp: number, maxHp: number): void {
    this.setText('hp-text', `${currentHp} / ${maxHp}`);
    this.setWidthPct('hp-bar-fill', maxHp > 0 ? (100 * currentHp) / maxHp : 0);
  }

  /** 메소(재화) 표시 갱신. 숫자는 천 단위 구분 콤마. */
  updateMeso(meso: number): void {
    this.setText('meso-text', `${meso.toLocaleString('ko-KR')} 메소`);
  }

  /** 로그인 직후 1회: 플레이어 카드 상단 이름 표시. */
  setPlayerName(name: string): void {
    this.setText('pc-name', name);
  }

  /** 레벨 · EXP 바 갱신. toNext 가 0 이면 바 비움 처리. */
  updateExp(level: number, exp: number, toNext: number): void {
    this.setText('pc-level', `Lv ${level}`);
    this.setText('exp-text', `${exp} / ${toNext}`);
    this.setWidthPct('exp-bar-fill', toNext > 0 ? (100 * exp) / toNext : 0);
  }

  /**
   * 인벤토리 스냅샷을 그리드로 렌더. 현재 선택된 탭에 해당하는 아이템만 표시한다.
   * 서버는 타입 구분 없이 전체 맵을 주므로 클라(ItemMeta) 의 type 으로 필터링.
   */
  updateInventory(items: Record<string, number>): void {
    this.lastInventory = items;
    this.renderInventoryGrid();
  }

  /** 탭 변경. 서버 요청 없이 기존 스냅샷으로 재렌더. */
  setInventoryTab(tab: ItemType): void {
    if (this.inventoryTab === tab) return;
    this.inventoryTab = tab;
    // 탭 UI 상태 동기화.
    document.querySelectorAll('#inv-tabs .tab').forEach((el) => {
      const t = (el as HTMLElement).dataset.tab as ItemType | undefined;
      el.classList.toggle('active', t === tab);
    });
    this.renderInventoryGrid();
  }

  private renderInventoryGrid(): void {
    const grid = document.getElementById('inv-grid');
    if (!grid) return;

    // 현 탭의 아이템만 추려낸다. (meso 는 ETC 이지만 인벤토리 snapshot 에 포함되지 않음)
    const tabItems = Object.entries(this.lastInventory)
      .filter(([, n]) => n > 0)
      .filter(([id]) => getItemMeta(id).type === this.inventoryTab);

    // 저장된 슬롯 순서를 복원. 획득했지만 저장 시점에 없던 id 는 뒤에 붙는다.
    const currentIds = tabItems.map(([id]) => id);
    const orderedIds = mergeOrder(loadOrder(this.accountKey, this.inventoryTab), currentIds);
    const qtyOf = Object.fromEntries(tabItems);
    const slotCount = Math.max(INVENTORY_MIN_SLOTS, orderedIds.length);

    const frag = document.createDocumentFragment();
    for (let i = 0; i < slotCount; i++) {
      const slot = document.createElement('div');
      slot.className = 'inv-slot';
      slot.dataset.slotIndex = String(i);
      const id = orderedIds[i];
      if (id) {
        const qty = qtyOf[id] ?? 0;
        const meta = getItemMeta(id);
        slot.classList.add('filled');
        slot.dataset.itemId = id;
        slot.draggable = true;
        const icon = document.createElement('div');
        icon.className = 'inv-icon';
        icon.style.background = `#${meta.color.toString(16).padStart(6, '0')}`;
        slot.appendChild(icon);
        if (qty > 1) {
          const q = document.createElement('span');
          q.className = 'inv-qty';
          q.textContent = String(qty);
          slot.appendChild(q);
        }
      }
      frag.appendChild(slot);
    }
    grid.replaceChildren(frag);
  }

  /** GameScene 이 사용자 액션(장착/해제)을 받을 콜백을 등록. */
  onInventoryAction(handler: (action: InventoryAction) => void): void {
    this.inventoryActionHandler = handler;
  }

  /** 로그인 직후 저장소 네임스페이스를 계정 단위로 고정. */
  setAccountKey(key: string): void {
    this.accountKey = key;
  }

  /**
   * 인벤토리 창의 탭 클릭 핸들러와 슬롯 툴팁을 최초 1회 바인딩한다.
   * GameScene.create 에서 호출. 탭 요소는 고정이고 슬롯은 동적으로 재렌더되므로
   * 툴팁은 그리드 컨테이너에 이벤트 위임(delegation) 방식으로 연결.
   */
  bindInventoryInteractions(): void {
    // 탭 클릭
    document.querySelectorAll('#inv-tabs .tab').forEach((el) => {
      el.addEventListener('click', () => {
        const t = (el as HTMLElement).dataset.tab as ItemType | undefined;
        if (t) this.setInventoryTab(t);
      });
    });

    this.bindInventoryDrag();

    // 슬롯 hover 툴팁 (이벤트 위임)
    const grid = document.getElementById('inv-grid');
    const tooltip = document.getElementById('item-tooltip');
    if (!grid || !tooltip) return;

    const show = (target: HTMLElement, event: MouseEvent) => {
      const id = target.dataset.itemId;
      if (!id) return;
      const meta = getItemMeta(id);
      (tooltip.querySelector('.tt-name') as HTMLElement).textContent = meta.name;
      (tooltip.querySelector('.tt-type') as HTMLElement).textContent = TAB_LABEL[meta.type];
      const bonusEl = tooltip.querySelector('.tt-bonus') as HTMLElement;
      if (meta.bonus) {
        bonusEl.textContent = formatBonus(meta.bonus);
        bonusEl.style.display = '';
      } else {
        bonusEl.style.display = 'none';
      }
      (tooltip.querySelector('.tt-desc') as HTMLElement).textContent = meta.description;
      tooltip.classList.remove('hidden');
      this.positionTooltip(tooltip, event);
    };

    // mouseover 가 매 슬롯 경계에서 발생하므로, 현재 커서 아래가 채워진 슬롯이 아니면
    // 툴팁을 즉시 숨긴다. 빈 슬롯·그리드 여백에서 잔존하던 툴팁이 제거된다.
    grid.addEventListener('mouseover', (e) => {
      const slot = (e.target as HTMLElement).closest('.inv-slot.filled') as HTMLElement | null;
      if (slot) show(slot, e as MouseEvent);
      else tooltip.classList.add('hidden');
    });
    grid.addEventListener('mousemove', (e) => {
      if (!tooltip.classList.contains('hidden')) this.positionTooltip(tooltip, e as MouseEvent);
    });
    grid.addEventListener('mouseleave', () => tooltip.classList.add('hidden'));

    // 더블클릭 동작:
    //   Shift + 더블클릭 → 해당 슬롯 1개 드롭(확인 후)
    //   장비 탭 더블클릭 → 장착
    //   소비 탭 더블클릭 → 사용
    //   기타 탭 더블클릭 → 없음
    grid.addEventListener('dblclick', (e) => {
      const slot = (e.target as HTMLElement).closest('.inv-slot.filled') as HTMLElement | null;
      if (!slot) return;
      const id = slot.dataset.itemId;
      if (!id) return;
      const meta = getItemMeta(id);

      if ((e as MouseEvent).shiftKey) {
        if (window.confirm(`"${meta.name}" 1개를 버리시겠습니까?`)) {
          this.inventoryActionHandler?.({ kind: 'drop', templateId: id, amount: 1 });
        }
        return;
      }

      if (meta.type === 'EQUIPMENT') {
        this.inventoryActionHandler?.({ kind: 'equip', templateId: id });
      } else if (meta.type === 'CONSUMABLE') {
        this.inventoryActionHandler?.({ kind: 'use', templateId: id });
      }
      // ETC 는 일반 더블클릭으로 할 일이 없다.
    });

    // 드래그 재정렬: 현재 탭에서 source→target 슬롯의 id 순서 swap 후 저장·재렌더.
    let dragSrcIndex: number | null = null;
    grid.addEventListener('dragstart', (e) => {
      const slot = (e.target as HTMLElement).closest('.inv-slot.filled') as HTMLElement | null;
      if (!slot) { e.preventDefault(); return; }
      dragSrcIndex = Number(slot.dataset.slotIndex);
      // 커스텀 데이터 없이도 동작하지만 일부 브라우저는 dataTransfer 가 필요.
      e.dataTransfer?.setData('text/plain', slot.dataset.itemId ?? '');
      e.dataTransfer!.effectAllowed = 'move';
      tooltip.classList.add('hidden');
    });
    grid.addEventListener('dragover', (e) => {
      if (dragSrcIndex === null) return;
      const slot = (e.target as HTMLElement).closest('.inv-slot') as HTMLElement | null;
      if (!slot) return;
      e.preventDefault();
      e.dataTransfer!.dropEffect = 'move';
    });
    grid.addEventListener('drop', (e) => {
      if (dragSrcIndex === null) return;
      const slot = (e.target as HTMLElement).closest('.inv-slot') as HTMLElement | null;
      if (!slot) return;
      e.preventDefault();
      const destIndex = Number(slot.dataset.slotIndex);
      this.reorderTabSlots(dragSrcIndex, destIndex);
      dragSrcIndex = null;
    });
    grid.addEventListener('dragend', () => {
      dragSrcIndex = null;
    });

    // 장비 카드의 슬롯 더블클릭: 해당 슬롯 해제.
    const bindEquipUnequip = (elId: string, slot: string) => {
      document.getElementById(elId)?.addEventListener('dblclick', () => {
        this.inventoryActionHandler?.({ kind: 'unequip', slot });
      });
    };
    bindEquipUnequip('eq-weapon', 'WEAPON');
    bindEquipUnequip('eq-hat', 'HAT');
    bindEquipUnequip('eq-armor', 'ARMOR');
  }

  /**
   * 현재 탭의 슬롯 순서에서 src → dest 로 이동.
   * 두 인덱스 중 하나가 빈 슬롯이면 id 를 밀어넣기/빼기 조합으로 처리하고,
   * 둘 다 채워진 경우 src 앞으로 dest 가 오도록 splice.
   */
  private reorderTabSlots(src: number, dest: number): void {
    if (src === dest) return;
    const tabItems = Object.entries(this.lastInventory)
      .filter(([, n]) => n > 0)
      .filter(([id]) => getItemMeta(id).type === this.inventoryTab);
    const currentIds = tabItems.map(([id]) => id);
    const ordered = mergeOrder(loadOrder(this.accountKey, this.inventoryTab), currentIds);
    // 빈 뒤쪽 슬롯까지 포함해 길이를 확보.
    const slotCount = Math.max(INVENTORY_MIN_SLOTS, ordered.length);
    const arr: (string | null)[] = [];
    for (let i = 0; i < slotCount; i++) arr.push(ordered[i] ?? null);
    const [moved] = arr.splice(src, 1);
    arr.splice(dest, 0, moved);
    // null 뒤에 아이템이 남아있을 수 있으므로 압축하지 않고 그대로 저장.
    const compact = arr.filter((x): x is string => x !== null);
    saveOrder(this.accountKey, this.inventoryTab, compact);
    this.renderInventoryGrid();
  }

  /** 툴팁을 커서 근처에 배치하되 화면 밖으로 나가지 않게 clamp. */
  private positionTooltip(tooltip: HTMLElement, event: MouseEvent): void {
    const margin = 14;
    const rect = tooltip.getBoundingClientRect();
    let x = event.clientX + margin;
    let y = event.clientY + margin;
    if (x + rect.width > window.innerWidth)  x = event.clientX - rect.width - margin;
    if (y + rect.height > window.innerHeight) y = event.clientY - rect.height - margin;
    tooltip.style.left = `${x}px`;
    tooltip.style.top  = `${y}px`;
  }

  /** 인벤토리 창 열기/닫기/토글. 채팅 입력 중 I 를 눌러도 창이 뜨는 일을 피하려 외부에서 호출. */
  openInventory(): void { this.setInventoryHidden(false); }
  closeInventory(): void { this.setInventoryHidden(true); }
  toggleInventory(): void {
    const el = document.getElementById('inventory-window');
    if (!el) return;
    this.setInventoryHidden(!el.classList.contains('hidden'));
  }
  isInventoryOpen(): boolean {
    return document.getElementById('inventory-window')?.classList.contains('hidden') === false;
  }
  private setInventoryHidden(hidden: boolean): void {
    document.getElementById('inventory-window')?.classList.toggle('hidden', hidden);
    if (hidden) document.getElementById('item-tooltip')?.classList.add('hidden');
  }

  /**
   * 인벤토리 창을 헤더로 잡아 드래그 이동 가능하게 한다.
   *
   * 초기 CSS 는 `top:50%; left:50%; transform: translate(-50%,-50%)` 중앙 정렬.
   * 첫 드래그 시 transform 을 벗기고 실제 픽셀 좌표로 고정해 이후 left/top 만 갱신한다.
   * 위치는 계정별 localStorage 에 저장해 재접속 시 복원(탭 순서 저장 패턴과 동일).
   */
  private bindInventoryDrag(): void {
    const win = document.getElementById('inventory-window');
    const header = win?.querySelector('.header') as HTMLElement | null;
    if (!win || !header) return;

    // 저장된 위치 복원
    this.restoreInventoryPosition(win);

    let startX = 0;
    let startY = 0;
    let originLeft = 0;
    let originTop = 0;
    let dragging = false;

    const onMove = (e: MouseEvent) => {
      if (!dragging) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;
      // 화면 밖으로 완전히 나가지 않게 클램핑. 헤더 일부만 남으면 다시 잡을 수 있다.
      const rect = win.getBoundingClientRect();
      const maxX = window.innerWidth - 40;
      const maxY = window.innerHeight - 24;
      const nx = Math.min(maxX, Math.max(40 - rect.width, originLeft + dx));
      const ny = Math.min(maxY, Math.max(0, originTop + dy));
      win.style.left = `${nx}px`;
      win.style.top = `${ny}px`;
    };

    const onUp = () => {
      if (!dragging) return;
      dragging = false;
      win.classList.remove('dragging');
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      this.saveInventoryPosition(win);
    };

    header.addEventListener('mousedown', (e) => {
      // 닫기 버튼 등 헤더 내부 컨트롤 클릭은 드래그 시작에서 제외.
      if ((e.target as HTMLElement).closest('.close-btn')) return;
      // transform 중앙 정렬을 실제 픽셀 좌표로 변환하고 한 번만 벗긴다.
      const rect = win.getBoundingClientRect();
      win.style.transform = 'none';
      win.style.left = `${rect.left}px`;
      win.style.top = `${rect.top}px`;
      originLeft = rect.left;
      originTop = rect.top;
      startX = e.clientX;
      startY = e.clientY;
      dragging = true;
      win.classList.add('dragging');
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
      e.preventDefault();
    });
  }

  private inventoryPosKey(): string {
    return `inv-pos:${this.accountKey}`;
  }

  private saveInventoryPosition(win: HTMLElement): void {
    try {
      window.localStorage.setItem(
        this.inventoryPosKey(),
        JSON.stringify({ left: win.style.left, top: win.style.top })
      );
    } catch {
      // localStorage 접근 실패(시크릿/쿼터)는 무시 — 위치 기억은 편의 기능.
    }
  }

  private restoreInventoryPosition(win: HTMLElement): void {
    try {
      const raw = window.localStorage.getItem(this.inventoryPosKey());
      if (!raw) return;
      const { left, top } = JSON.parse(raw) as { left?: string; top?: string };
      if (!left || !top) return;
      win.style.transform = 'none';
      win.style.left = left;
      win.style.top = top;
    } catch {
      // 파싱 실패 시 기본 중앙 정렬 유지.
    }
  }

  updateEquipment(slots: Record<string, string>): void {
    const render = (elId: string, slot: string) => {
      const el = document.getElementById(elId);
      if (!el) return;
      const itemId = slots[slot];
      el.textContent = itemId ? (ITEM_NAMES[itemId] ?? itemId) : '-';
      el.style.color = itemId ? '#ffeb8a' : '#666';
    };
    render('eq-weapon', 'WEAPON');
    render('eq-hat', 'HAT');
    render('eq-armor', 'ARMOR');
  }

  updateSkillCooldowns(now: number, cooldownUntil: Map<string, number>): void {
    for (const skillId of Object.keys(SKILL_META)) {
      const el = document.getElementById(`cd-${skillId}`);
      if (!el) continue;
      const row = el.parentElement;
      const remaining = Math.max(0, (cooldownUntil.get(skillId) ?? 0) - now);
      if (remaining === 0) {
        el.textContent = '';
        row?.classList.remove('cooling');
      } else {
        el.textContent = `${(remaining / 1000).toFixed(1)}s`;
        row?.classList.add('cooling');
      }
    }
  }

  showDeathOverlay(): void {
    const overlay = document.getElementById('death-overlay');
    const cd = document.getElementById('respawn-countdown');
    if (!overlay || !cd) return;
    overlay.classList.add('active');
    const startAt = performance.now();
    const total = this.respawnOverlayDurationMs;
    const tick = () => {
      if (!overlay.classList.contains('active')) return;
      const remaining = Math.max(0, total - (performance.now() - startAt));
      cd.textContent = (remaining / 1000).toFixed(1);
      if (remaining > 0) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }

  hideDeathOverlay(): void {
    document.getElementById('death-overlay')?.classList.remove('active');
  }

  private setText(id: string, text: string): void {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  }

  private setWidthPct(id: string, pct: number): void {
    const el = document.getElementById(id);
    if (el) el.style.width = `${pct}%`;
  }
}
