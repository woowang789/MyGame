import Phaser from 'phaser';
import { DroppedItemSprite } from '../entities/DroppedItemSprite';
import { MonsterSprite } from '../entities/MonsterSprite';
import { NpcSprite } from '../entities/NpcSprite';
import { RemotePlayer } from '../entities/RemotePlayer';
import { PLAYER_TEXTURE } from '../scenes/TextureFactory';

export interface RemoteSnapshot {
  readonly id: number;
  readonly name: string;
  readonly x: number;
  readonly y: number;
}

export interface MonsterSnapshot {
  readonly id: number;
  readonly template: string;
  readonly x: number;
  readonly y: number;
  readonly hp: number;
  readonly maxHp: number;
}

export interface DroppedItemSnapshot {
  readonly id: number;
  readonly templateId: string;
  readonly x: number;
  readonly y: number;
}

export interface NpcSnapshot {
  readonly id: number;
  readonly name: string;
  readonly x: number;
  readonly y: number;
  readonly shopId: string;
}

/**
 * 4종 엔티티(원격 플레이어 / 몬스터 / 드롭 아이템 / NPC) 의 인메모리 레지스트리.
 *
 * <p>GameScene 이 직접 들고 있던 4개 Map 과 spawn/update/destroy/clearAll 책임을
 * 한 곳에 모았다. 씬은 좌표·이펙트·HUD 같은 자기 책임에만 집중하고,
 * "다른 플레이어 좌표 한 명 갱신하기" 같은 단순 위임은 본 클래스에 맡긴다.
 */
export class EntityRegistry {
  private readonly remotes = new Map<number, RemotePlayer>();
  private readonly monsters = new Map<number, MonsterSprite>();
  private readonly droppedItems = new Map<number, DroppedItemSprite>();
  private readonly npcs = new Map<number, NpcSprite>();

  constructor(private readonly scene: Phaser.Scene) {}

  // --- 원격 플레이어 ---

  spawnRemote(snap: RemoteSnapshot): void {
    if (this.remotes.has(snap.id)) return;
    this.remotes.set(
      snap.id,
      new RemotePlayer(this.scene, snap.id, snap.name, snap.x, snap.y, PLAYER_TEXTURE)
    );
  }

  moveRemote(id: number, x: number, y: number): void {
    this.remotes.get(id)?.setTarget(x, y);
  }

  removeRemote(id: number): void {
    const r = this.remotes.get(id);
    if (!r) return;
    r.destroy();
    this.remotes.delete(id);
  }

  remotePosition(id: number): { x: number; y: number } | null {
    const r = this.remotes.get(id);
    return r ? { x: r.sprite.x, y: r.sprite.y } : null;
  }

  remote(id: number): RemotePlayer | undefined {
    return this.remotes.get(id);
  }

  remoteValues(): IterableIterator<RemotePlayer> {
    return this.remotes.values();
  }

  // --- 몬스터 ---

  spawnMonster(snap: MonsterSnapshot): void {
    if (this.monsters.has(snap.id)) return;
    this.monsters.set(
      snap.id,
      new MonsterSprite(this.scene, snap.id, snap.template, snap.x, snap.y, snap.hp, snap.maxHp)
    );
  }

  moveMonster(id: number, x: number, vx: number): void {
    this.monsters.get(id)?.setTarget(x, vx);
  }

  damageMonster(id: number, hp: number, dmg: number): void {
    this.monsters.get(id)?.applyDamage(hp, this.scene, dmg);
  }

  removeMonster(id: number): void {
    const m = this.monsters.get(id);
    if (!m) return;
    m.destroy();
    this.monsters.delete(id);
  }

  monster(id: number): MonsterSprite | undefined {
    return this.monsters.get(id);
  }

  monsterValues(): IterableIterator<MonsterSprite> {
    return this.monsters.values();
  }

  // --- 드롭 아이템 ---

  spawnDroppedItem(snap: DroppedItemSnapshot): void {
    if (this.droppedItems.has(snap.id)) return;
    this.droppedItems.set(
      snap.id,
      new DroppedItemSprite(this.scene, snap.id, snap.templateId, snap.x, snap.y)
    );
  }

  removeDroppedItem(id: number): void {
    this.droppedItems.get(id)?.destroy();
    this.droppedItems.delete(id);
  }

  droppedItemCount(): number {
    return this.droppedItems.size;
  }

  droppedItemValues(): IterableIterator<DroppedItemSprite> {
    return this.droppedItems.values();
  }

  // --- NPC ---

  spawnNpc(snap: NpcSnapshot, onClick: (npc: NpcSprite) => void): void {
    if (this.npcs.has(snap.id)) return;
    const sprite = new NpcSprite(
      this.scene,
      snap.id,
      snap.name,
      snap.shopId,
      snap.x,
      snap.y,
      onClick
    );
    this.npcs.set(snap.id, sprite);
  }

  /** 좌표(px, py) 기준 가장 가까운 NPC. {@code maxRange} 안에 없으면 null. */
  nearestNpc(px: number, py: number, maxRange: number): NpcSprite | null {
    let best: NpcSprite | null = null;
    let bestDist = maxRange;
    for (const n of this.npcs.values()) {
      const dx = n.sprite.x - px;
      const dy = n.sprite.y - py;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d <= bestDist) {
        bestDist = d;
        best = n;
      }
    }
    return best;
  }

  /** 매 프레임 모든 NPC 의 「F: 대화」 힌트 갱신. */
  updateNpcHints(active: NpcSprite | null): void {
    for (const n of this.npcs.values()) n.setNearby(n === active);
  }

  // --- 일괄 갱신/정리 ---

  /** 매 프레임 원격 플레이어·몬스터 보간 갱신. */
  updateAll(delta: number): void {
    for (const r of this.remotes.values()) r.update(delta);
    for (const m of this.monsters.values()) m.update(delta);
  }

  /** 맵 전환 시 호출. 모든 엔티티를 즉시 폐기 — 새 스냅샷이 곧 도착한다. */
  clearAll(): void {
    for (const r of this.remotes.values()) r.destroy();
    this.remotes.clear();
    for (const m of this.monsters.values()) m.destroy();
    this.monsters.clear();
    for (const d of this.droppedItems.values()) d.destroy();
    this.droppedItems.clear();
    for (const n of this.npcs.values()) n.destroy();
    this.npcs.clear();
  }
}
