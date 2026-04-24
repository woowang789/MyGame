/**
 * 아이템 메타데이터 — 클라 전용.
 *
 * <p>서버 {@code mygame.game.item.ItemRegistry} 와 값이 일치해야 한다.
 * 현재는 클라 하드코딩으로 유지하지만, 추후 Phase D 에서 서버
 * {@code ItemTemplate.description} 필드를 추가하고 카탈로그 패킷으로
 * 대체하기 위한 연결 지점이다.
 */

export type ItemType = 'EQUIPMENT' | 'CONSUMABLE' | 'ETC';

export interface StatBonus {
  readonly maxHp?: number;
  readonly maxMp?: number;
  readonly attack?: number;
  readonly speed?: number;
}

export interface ItemMeta {
  readonly id: string;
  readonly name: string;
  readonly color: number;
  readonly type: ItemType;
  readonly description: string;
  readonly bonus?: StatBonus;
}

/** 템플릿 ID → 메타. 서버 ItemRegistry 와 1:1. */
export const ITEM_META: Record<string, ItemMeta> = {
  red_potion: {
    id: 'red_potion',
    name: '빨간 포션',
    color: 0xe74c3c,
    type: 'CONSUMABLE',
    description: '체력을 회복한다. (사용 미구현)'
  },
  blue_potion: {
    id: 'blue_potion',
    name: '파란 포션',
    color: 0x3498db,
    type: 'CONSUMABLE',
    description: '마나를 회복한다. (사용 미구현)'
  },
  snail_shell: {
    id: 'snail_shell',
    name: '달팽이 껍질',
    color: 0xb36836,
    type: 'ETC',
    description: '달팽이에서 나오는 잡템. 상점에 팔 수 있다. (상점 미구현)'
  },
  wooden_sword: {
    id: 'wooden_sword',
    name: '나무 검',
    color: 0x8b5a2b,
    type: 'EQUIPMENT',
    description: '기본적인 나무 검.',
    bonus: { attack: 10 }
  },
  iron_sword: {
    id: 'iron_sword',
    name: '철 검',
    color: 0xbfc7d5,
    type: 'EQUIPMENT',
    description: '단단한 철 검.',
    bonus: { attack: 25 }
  },
  leather_cap: {
    id: 'leather_cap',
    name: '가죽 모자',
    color: 0x6a4e2a,
    type: 'EQUIPMENT',
    description: '평범한 가죽 모자.',
    bonus: { maxHp: 15, maxMp: 5 }
  },
  cloth_armor: {
    id: 'cloth_armor',
    name: '천 갑옷',
    color: 0xcfa16a,
    type: 'EQUIPMENT',
    description: '가벼운 천 갑옷.',
    bonus: { maxHp: 25 }
  },
  meso: {
    id: 'meso',
    name: '메소',
    color: 0xffd84a,
    type: 'ETC',
    description: '게임 내 재화. 자동으로 지갑에 들어간다.'
  }
};

/** 주어진 id 의 메타를 안전하게 조회. 없으면 플레이스홀더 반환. */
export function getItemMeta(id: string): ItemMeta {
  return (
    ITEM_META[id] ?? {
      id,
      name: id,
      color: 0x666666,
      type: 'ETC',
      description: '알 수 없는 아이템'
    }
  );
}

/** 스탯 보너스를 사람이 읽을 수 있는 짧은 문자열로. (예: "ATK +10 · HP +15") */
export function formatBonus(bonus: StatBonus): string {
  const parts: string[] = [];
  if (bonus.attack)  parts.push(`ATK +${bonus.attack}`);
  if (bonus.maxHp)   parts.push(`HP +${bonus.maxHp}`);
  if (bonus.maxMp)   parts.push(`MP +${bonus.maxMp}`);
  if (bonus.speed)   parts.push(`SPD +${bonus.speed}`);
  return parts.join(' · ');
}
