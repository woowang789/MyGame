import { ITEM_NAMES } from '../entities/DroppedItemSprite';
import { formatBonus, getItemMeta, ItemType } from '../data/ItemMeta';

/* ITEM_NAMES 가 HudView 내부에서 equipment 슬롯 렌더에만 쓰이므로 유지. */

/** 인벤토리 창의 탭별 기본 슬롯 수. 아이템이 더 많으면 스크롤에 맡긴다. */
const INVENTORY_MIN_SLOTS = 24;

const TAB_LABEL: Record<ItemType, string> = {
  EQUIPMENT: '장비',
  CONSUMABLE: '소비',
  ETC: '기타'
};

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

/** 클라/서버가 공유하는 초보자 스킬 메타. 서버 SkillRegistry 와 값이 같아야 한다. */
export const SKILL_META: Record<string, SkillMeta> = {
  power_strike: { name: '파워 스트라이크', cooldownMs: 1500, mpCost: 8 },
  triple_blow: { name: '트리플 블로우', cooldownMs: 4000, mpCost: 18 },
  recovery: { name: '리커버리', cooldownMs: 10000, mpCost: 0 }
};

/** 인벤토리 아이템 ID 중 장비로 취급하는 것. 서버 ItemRegistry EQUIPMENT 와 동기 유지. */
export const EQUIPMENT_IDS: ReadonlySet<string> = new Set([
  'wooden_sword',
  'iron_sword',
  'leather_cap',
  'cloth_armor'
]);

export class HudView {
  private readonly respawnOverlayDurationMs = 3000;
  /** 현 인벤토리 탭. updateInventory 가 이 값으로 필터링해 렌더한다. */
  private inventoryTab: ItemType = 'EQUIPMENT';
  /** 마지막 인벤토리 스냅샷. 탭 전환 시 서버 패킷 없이 재렌더하기 위해 보관. */
  private lastInventory: Record<string, number> = {};

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
    const entries = Object.entries(this.lastInventory)
      .filter(([, n]) => n > 0)
      .filter(([id]) => getItemMeta(id).type === this.inventoryTab);

    const slotCount = Math.max(INVENTORY_MIN_SLOTS, entries.length);

    const frag = document.createDocumentFragment();
    for (let i = 0; i < slotCount; i++) {
      const slot = document.createElement('div');
      slot.className = 'inv-slot';
      const entry = entries[i];
      if (entry) {
        const [id, qty] = entry;
        const meta = getItemMeta(id);
        slot.classList.add('filled');
        slot.dataset.itemId = id;
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

    grid.addEventListener('mouseover', (e) => {
      const slot = (e.target as HTMLElement).closest('.inv-slot.filled') as HTMLElement | null;
      if (slot) show(slot, e as MouseEvent);
    });
    grid.addEventListener('mousemove', (e) => {
      if (!tooltip.classList.contains('hidden')) this.positionTooltip(tooltip, e as MouseEvent);
    });
    grid.addEventListener('mouseout', (e) => {
      const related = (e as MouseEvent).relatedTarget as HTMLElement | null;
      if (!related || !grid.contains(related)) tooltip.classList.add('hidden');
    });
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
