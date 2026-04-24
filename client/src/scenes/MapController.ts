import Phaser from 'phaser';
import { TILESET_NAME, TILESET_TEXTURE, TILE_SIZE } from './TextureFactory';

/**
 * 타일맵 · 포털 관리 전담.
 *
 * Tiled JSON 로드 + 포털 오브젝트 파싱 + 플레이어 충돌 설정을 한 곳에서.
 * GameScene 은 `loadMap(mapId)` 와 포털 겹침 조회만 호출한다.
 */
export interface PortalDef {
  readonly zone: Phaser.GameObjects.Zone;
  readonly targetMap: string;
  readonly targetX: number;
  readonly targetY: number;
  readonly label: Phaser.GameObjects.Text;
  readonly visual: Phaser.GameObjects.Rectangle;
}

export class MapController {
  private tilemap: Phaser.Tilemaps.Tilemap | null = null;
  private portals: PortalDef[] = [];
  /**
   * 현재 맵의 지면 collider. 다음 loadMap 호출 시 반드시 destroy 해야 한다.
   * 이전 collider 가 파괴된 ground layer 를 참조한 채 남아있으면 물리 스텝에서
   * 크래시가 나 Phaser 씬 전체가 멈춘다.
   */
  private groundCollider: Phaser.Physics.Arcade.Collider | null = null;

  constructor(
    private readonly scene: Phaser.Scene,
    private readonly player: Phaser.Physics.Arcade.Sprite
  ) {}

  loadMap(mapId: string): void {
    this.groundCollider?.destroy();
    this.groundCollider = null;
    this.tilemap?.destroy();
    for (const p of this.portals) {
      p.zone.destroy();
      p.label.destroy();
      p.visual.destroy();
    }
    this.portals = [];

    const map = this.scene.make.tilemap({ key: mapId });
    const tileset = map.addTilesetImage(TILESET_NAME, TILESET_TEXTURE, TILE_SIZE, TILE_SIZE);
    if (!tileset) throw new Error(`타일셋 로드 실패: ${TILESET_NAME}`);

    const ground = map.createLayer('Ground', tileset, 0, 0);
    if (!ground) throw new Error(`Ground 레이어를 찾을 수 없습니다. map=${mapId}`);
    ground.setCollisionByProperty({ collides: true });

    this.scene.physics.world.setBounds(0, 0, map.widthInPixels, map.heightInPixels);
    this.scene.cameras.main.setBounds(0, 0, map.widthInPixels, map.heightInPixels);
    this.groundCollider = this.scene.physics.add.collider(this.player, ground);

    const portalLayer = map.getObjectLayer('Portals');
    if (portalLayer) {
      for (const obj of portalLayer.objects) {
        this.addPortalFromObject(obj);
      }
    }

    this.tilemap = map;
  }

  /** 플레이어와 겹치는 포털 인덱스. 없으면 -1. 자동 진입 트리거 용. */
  findOverlappingPortalIndex(): number {
    const playerRect = this.player.getBounds();
    for (let i = 0; i < this.portals.length; i++) {
      if (Phaser.Geom.Rectangle.Overlaps(this.portals[i].zone.getBounds(), playerRect)) return i;
    }
    return -1;
  }

  portalAt(index: number): PortalDef | undefined {
    return this.portals[index];
  }

  private addPortalFromObject(obj: Phaser.Types.Tilemaps.TiledObject): void {
    const x = obj.x ?? 0;
    const y = obj.y ?? 0;
    const w = obj.width ?? TILE_SIZE;
    const h = obj.height ?? TILE_SIZE;
    const props = propsToMap(obj.properties);
    const targetMap = String(props.targetMap ?? '');
    const targetX = Number(props.targetX ?? 0);
    const targetY = Number(props.targetY ?? 0);
    if (!targetMap) return;

    const zone = this.scene.add.zone(x + w / 2, y + h / 2, w, h);
    const visual = this.scene.add
      .rectangle(x + w / 2, y + h / 2, w, h, 0x7fd3ff, 0.25)
      .setStrokeStyle(2, 0x7fd3ff);
    const label = this.scene.add
      .text(x + w / 2, y - 4, `▲ ${targetMap}`, { fontSize: '10px', color: '#7fd3ff' })
      .setOrigin(0.5, 1);
    this.portals.push({ zone, targetMap, targetX, targetY, label, visual });
  }
}

function propsToMap(props: unknown): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {};
  if (!Array.isArray(props)) return out;
  for (const p of props as Array<{ name: string; value: string | number | boolean }>) {
    out[p.name] = p.value;
  }
  return out;
}
