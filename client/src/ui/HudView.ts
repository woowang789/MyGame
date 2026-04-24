import { ITEM_NAMES } from '../entities/DroppedItemSprite';

/* ITEM_NAMES 가 HudView 내부에서 equipment 슬롯 렌더에만 쓰이므로 유지. */

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
