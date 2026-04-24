import type { ItemType } from './ItemMeta';

/**
 * 인벤토리 탭별 아이템 순서를 클라 localStorage 에 저장.
 *
 * <p>현재 서버는 {@code LinkedHashMap} 삽입 순서를 반환하므로 "획득 순" 이
 * 기본값. 사용자가 드래그로 재정렬한 결과를 세션 간 유지하기 위해 클라에 둔다.
 *
 * <p>Phase D 에서 서버 권위로 이관 예정. 이관 시 이 모듈을 걷어내고 서버 응답
 * 순서를 그대로 쓰면 된다.
 */

const STORAGE_PREFIX = 'mygame:inv-order:';

function keyFor(accountKey: string, tab: ItemType): string {
  return `${STORAGE_PREFIX}${accountKey}:${tab}`;
}

/** 저장된 순서를 읽어 동일 id 만 필터링해 반환. 없거나 깨져있으면 null. */
export function loadOrder(accountKey: string, tab: ItemType): string[] | null {
  try {
    const raw = localStorage.getItem(keyFor(accountKey, tab));
    if (!raw) return null;
    const arr = JSON.parse(raw);
    return Array.isArray(arr) && arr.every((x) => typeof x === 'string') ? arr : null;
  } catch {
    return null;
  }
}

export function saveOrder(accountKey: string, tab: ItemType, ids: string[]): void {
  try {
    localStorage.setItem(keyFor(accountKey, tab), JSON.stringify(ids));
  } catch {
    // quota · private mode 등 — 순서 복원은 best-effort 이므로 조용히 무시.
  }
}

/**
 * 현재 아이템 id 집합을 {@code saved} 순서 기준으로 정렬.
 * saved 에 없는 신규 id 는 끝에 붙이고, saved 에만 있는 사라진 id 는 제거한다.
 */
export function mergeOrder(saved: string[] | null, currentIds: string[]): string[] {
  if (!saved) return currentIds;
  const set = new Set(currentIds);
  const ordered: string[] = [];
  for (const id of saved) {
    if (set.has(id)) {
      ordered.push(id);
      set.delete(id);
    }
  }
  for (const id of currentIds) {
    if (set.has(id)) {
      ordered.push(id);
      set.delete(id);
    }
  }
  return ordered;
}
