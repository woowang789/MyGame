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
/** 상점 [구매] 탭의 한 항목 — 서버 SHOP_OPENED 응답을 옮긴 형태. */
export interface ShopBuyItem {
  itemId: string;
  price: number;
  stockPerTransaction: number;
}

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

/**
 * 매입가 맵. itemId → 메소. 0 이거나 미수록이면 매입 불가.
 * META 패킷으로 1회 수신 후 상점 [팔기] 탭이 참조한다.
 */
export const SELL_PRICES: Record<string, number> = {};

/** META 패킷으로 받은 장비 · 스킬 · 매입가 메타를 클라 런타임 상수로 반영. */
export function applyMeta(meta: {
  equipmentIds: readonly string[];
  skills: readonly { id: string; name: string; mpCost: number; cooldownMs: number }[];
  sellPrices?: Readonly<Record<string, number>>;
}): void {
  equipmentIdSet.clear();
  for (const id of meta.equipmentIds) equipmentIdSet.add(id);
  for (const key of Object.keys(SKILL_META)) delete SKILL_META[key];
  for (const s of meta.skills) {
    SKILL_META[s.id] = { name: s.name, cooldownMs: s.cooldownMs, mpCost: s.mpCost };
  }
  for (const key of Object.keys(SELL_PRICES)) delete SELL_PRICES[key];
  if (meta.sellPrices) {
    for (const [k, v] of Object.entries(meta.sellPrices)) SELL_PRICES[k] = v;
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
    speed: number;
    currentHp: number;
    currentMp: number;
    baseAttack: number;
    baseMaxHp: number;
    baseMaxMp: number;
    baseSpeed: number;
  }): void {
    this.setText('eq-stats', `ATK ${stats.attack} · MAXHP ${stats.maxHp}`);
    this.setText('hp-text', `${stats.currentHp} / ${stats.maxHp}`);
    this.setWidthPct('hp-bar-fill', stats.maxHp > 0 ? (100 * stats.currentHp) / stats.maxHp : 0);
    this.setText('mp-text', `${stats.currentMp} / ${stats.maxMp}`);
    this.setWidthPct('mp-bar-fill', stats.maxMp > 0 ? (100 * stats.currentMp) / stats.maxMp : 0);
    // 스탯창: 베이스 · 장비 · 합계 3열을 표 형태로. 장비 = 합계 - 베이스(SSoT 는 서버).
    const renderRow = (
      key: 'attack' | 'hp' | 'mp' | 'spd',
      base: number,
      total: number
    ): void => {
      const equip = total - base;
      this.setText(`st-${key}-base`, String(base));
      this.setText(`st-${key}-equip`, equip > 0 ? `+${equip}` : equip < 0 ? String(equip) : '+0');
      this.setText(`st-${key}-total`, String(total));
    };
    renderRow('attack', stats.baseAttack, stats.attack);
    renderRow('hp', stats.baseMaxHp, stats.maxHp);
    renderRow('mp', stats.baseMaxMp, stats.maxMp);
    renderRow('spd', stats.baseSpeed, stats.speed);
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

    this.bindWindowDrag('inventory-window', 'inv-pos');
    this.bindWindowDrag('stat-window', 'stat-pos');
    this.bindWindowDrag('shop-window', 'shop-pos');

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
    bindEquipUnequip('eq-gloves', 'GLOVES');
    bindEquipUnequip('eq-shoes', 'SHOES');
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

  /** 스탯창 토글/닫기. 인벤토리와 동일한 토글 패턴 — 채팅 포커스 중 입력은 GameScene 측에서 차단. */
  toggleStat(): void {
    const el = document.getElementById('stat-window');
    if (!el) return;
    el.classList.toggle('hidden');
  }
  closeStat(): void {
    document.getElementById('stat-window')?.classList.add('hidden');
  }
  isStatOpen(): boolean {
    return document.getElementById('stat-window')?.classList.contains('hidden') === false;
  }

  /** 현재 열린 상점 컨텍스트. 인벤 갱신 시 [팔기] 탭 재렌더에 사용. */
  private shopCtx: {
    shopId: string;
    npcName: string;
    items: ShopBuyItem[];
    onBuy: (itemId: string, qty: number) => void;
    onSell: (itemId: string, qty: number) => void;
    tab: 'BUY' | 'SELL';
  } | null = null;

  /**
   * 상점 창 열기. [구매]/[팔기] 두 탭을 한 창에서 운용한다.
   * 같은 창에 여러 NPC 카탈로그가 갈마들 수 있으므로 매번 그리드를 재구성한다.
   */
  openShop(
    shopId: string,
    npcName: string,
    items: ShopBuyItem[],
    meso: number,
    onBuy: (itemId: string, qty: number) => void,
    onSell: (itemId: string, qty: number) => void
  ): void {
    const win = document.getElementById('shop-window');
    if (!win) return;
    const titleEl = document.getElementById('shop-title');
    if (titleEl) titleEl.textContent = `${npcName} (${shopId})`;

    this.shopCtx = { shopId, npcName, items, onBuy, onSell, tab: 'BUY' };
    this.bindShopTabs();
    this.renderShopBody();
    this.updateShopMeso(meso);
    win.classList.remove('hidden');
  }

  closeShop(): void {
    document.getElementById('shop-window')?.classList.add('hidden');
    this.shopCtx = null;
  }

  isShopOpen(): boolean {
    return document.getElementById('shop-window')?.classList.contains('hidden') === false;
  }

  /** 인벤이 바뀌면 호출됨. 현재 [팔기] 탭이 열려있다면 보유 아이템 그리드를 갱신. */
  refreshShopSellTabIfOpen(): void {
    if (!this.shopCtx || this.shopCtx.tab !== 'SELL') return;
    if (!this.isShopOpen()) return;
    this.renderShopBody();
  }

  /** SHOP_RESULT 처리. 메소 갱신과 인벤 변동에 따른 [팔기] 탭 재렌더. */
  handleShopResult(_ok: boolean, _reason: string | null, mesoAfter: number): void {
    this.updateShopMeso(mesoAfter);
    this.refreshShopSellTabIfOpen();
  }

  private bindShopTabs(): void {
    const tabs = document.querySelectorAll('#shop-tabs .tab');
    tabs.forEach((el) => {
      const tab = (el as HTMLElement).dataset.tab as 'BUY' | 'SELL' | undefined;
      // 매번 listener 가 누적되지 않도록 cloneNode 로 핸들러 리셋.
      const fresh = el.cloneNode(true);
      el.parentNode?.replaceChild(fresh, el);
      fresh.addEventListener('click', () => {
        if (!tab || !this.shopCtx) return;
        this.shopCtx.tab = tab;
        document.querySelectorAll('#shop-tabs .tab').forEach((t) =>
          t.classList.toggle('active', (t as HTMLElement).dataset.tab === tab)
        );
        this.renderShopBody();
      });
    });
    // 초기 활성 탭 갱신
    document.querySelectorAll('#shop-tabs .tab').forEach((t) =>
      t.classList.toggle('active', (t as HTMLElement).dataset.tab === this.shopCtx?.tab)
    );
  }

  private renderShopBody(): void {
    const list = document.getElementById('shop-list');
    if (!list || !this.shopCtx) return;
    list.innerHTML = '';
    if (this.shopCtx.tab === 'BUY') {
      this.renderBuyRows(list);
    } else {
      this.renderSellRows(list);
    }
  }

  private renderBuyRows(list: HTMLElement): void {
    if (!this.shopCtx) return;
    for (const it of this.shopCtx.items) {
      const row = this.shopRow(
        ITEM_NAMES[it.itemId] ?? it.itemId,
        `${it.price.toLocaleString()} 메소`,
        it.stockPerTransaction,
        '구매',
        (qty) => {
          const total = it.price * qty;
          this.confirm(
            `구매 확인`,
            `${ITEM_NAMES[it.itemId] ?? it.itemId} ×${qty} 을(를) ${total.toLocaleString()} 메소에 구매하시겠습니까?`,
            () => this.shopCtx!.onBuy(it.itemId, qty)
          );
        }
      );
      list.appendChild(row);
    }
  }

  private renderSellRows(list: HTMLElement): void {
    // 인벤토리 보유 × 매입가(>0) 교집합. 마지막 인벤 스냅샷을 사용한다.
    const inv = this.lastInventory;
    const owned = Object.entries(inv).filter(([, qty]) => qty > 0);
    const sellable = owned.filter(([id]) => (SELL_PRICES[id] ?? 0) > 0);
    if (sellable.length === 0) {
      const empty = document.createElement('div');
      empty.style.padding = '12px';
      empty.style.color = '#7a7a88';
      empty.style.textAlign = 'center';
      empty.textContent = '매입할 아이템이 없습니다';
      list.appendChild(empty);
      return;
    }
    for (const [itemId, owns] of sellable) {
      const price = SELL_PRICES[itemId];
      const row = this.shopRow(
        `${ITEM_NAMES[itemId] ?? itemId} ×${owns}`,
        `${price.toLocaleString()} 메소`,
        owns, // 보유 수량까지만 한 번에 매입
        '팔기',
        (qty) => {
          const total = price * qty;
          this.confirm(
            `매입 확인`,
            `${ITEM_NAMES[itemId] ?? itemId} ×${qty} 을(를) ${total.toLocaleString()} 메소에 판매하시겠습니까?`,
            () => this.shopCtx!.onSell(itemId, qty)
          );
        }
      );
      list.appendChild(row);
    }
  }

  /** 한 줄 row 빌더. 구매/매입 공통. */
  private shopRow(
    label: string, priceText: string, maxQty: number,
    btnLabel: string, onAct: (qty: number) => void
  ): HTMLElement {
    const row = document.createElement('div');
    row.className = 'shop-row';
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = label;
    const price = document.createElement('span');
    price.className = 'price';
    price.textContent = priceText;
    const qty = document.createElement('input');
    qty.className = 'qty';
    qty.type = 'number';
    qty.min = '1';
    qty.max = String(maxQty);
    qty.value = '1';
    const btn = document.createElement('button');
    btn.className = 'buy';
    btn.type = 'button';
    btn.textContent = btnLabel;
    btn.addEventListener('click', () => {
      const n = Math.max(1, Math.min(maxQty, parseInt(qty.value, 10) || 1));
      onAct(n);
    });
    row.append(name, price, qty, btn);
    return row;
  }

  private updateShopMeso(meso: number): void {
    const el = document.getElementById('shop-meso');
    if (el) el.textContent = `보유: ${meso.toLocaleString()} 메소`;
  }

  /**
   * 공통 확인 다이얼로그. Esc / 배경 클릭 / [취소] 모두 dismiss.
   * 같은 모달을 재사용하므로 동시에 둘이 뜨지 않는다.
   */
  confirm(title: string, message: string, onYes: () => void): void {
    const overlay = document.getElementById('confirm-overlay');
    const titleEl = document.getElementById('confirm-title');
    const msgEl = document.getElementById('confirm-message');
    const yes = document.getElementById('confirm-yes') as HTMLButtonElement | null;
    const no = document.getElementById('confirm-no') as HTMLButtonElement | null;
    if (!overlay || !titleEl || !msgEl || !yes || !no) return;
    titleEl.textContent = title;
    msgEl.textContent = message;
    overlay.classList.remove('hidden');

    const close = () => {
      overlay.classList.add('hidden');
      // listener 누적 방지 — clone 으로 갈아끼움.
      const yes2 = yes.cloneNode(true) as HTMLButtonElement;
      yes.parentNode?.replaceChild(yes2, yes);
      const no2 = no.cloneNode(true) as HTMLButtonElement;
      no.parentNode?.replaceChild(no2, no);
      window.removeEventListener('keydown', onKey);
      overlay.removeEventListener('click', onBackdrop);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
      else if (e.key === 'Enter') { close(); onYes(); }
    };
    const onBackdrop = (e: MouseEvent) => {
      if (e.target === overlay) close();
    };
    yes.addEventListener('click', () => { close(); onYes(); });
    no.addEventListener('click', close);
    window.addEventListener('keydown', onKey);
    overlay.addEventListener('click', onBackdrop);
    yes.focus();
  }

  /**
   * 창 헤더로 잡아 드래그 이동 가능하게 한다. 인벤토리·스탯창 등 같은 패턴 공유.
   *
   * <p>초기 CSS 는 `top:50%; left:50%; transform: translate(-50%,-50%)` 중앙 정렬.
   * 첫 드래그 시 transform 을 벗기고 실제 픽셀 좌표로 고정해 이후 left/top 만 갱신한다.
   * 위치는 계정별 localStorage 에 저장해 재접속 시 복원(탭 순서 저장 패턴과 동일).
   *
   * @param windowId  대상 창의 DOM id
   * @param storageKeyPrefix  localStorage 네임스페이스(예: 'inv-pos', 'stat-pos')
   */
  private bindWindowDrag(windowId: string, storageKeyPrefix: string): void {
    const win = document.getElementById(windowId);
    const header = win?.querySelector('.header') as HTMLElement | null;
    if (!win || !header) return;

    const storageKey = () => `${storageKeyPrefix}:${this.accountKey}`;
    this.restoreWindowPosition(win, storageKey());

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
      this.saveWindowPosition(win, storageKey());
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

  private saveWindowPosition(win: HTMLElement, key: string): void {
    try {
      window.localStorage.setItem(
        key,
        JSON.stringify({ left: win.style.left, top: win.style.top })
      );
    } catch {
      // localStorage 접근 실패(시크릿/쿼터)는 무시 — 위치 기억은 편의 기능.
    }
  }

  private restoreWindowPosition(win: HTMLElement, key: string): void {
    try {
      const raw = window.localStorage.getItem(key);
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
    render('eq-gloves', 'GLOVES');
    render('eq-shoes', 'SHOES');
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
