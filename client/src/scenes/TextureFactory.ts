import Phaser from 'phaser';

/**
 * 절차적으로 생성한 플레이스홀더 텍스처.
 *
 * 실제 에셋(저작권 교체본) 이 준비되기 전까지 사용한다. 한 곳에 모아 놓아
 * 색/치수를 일괄 조정하기 쉽도록 분리.
 */
export const TILESET_NAME = 'tiles';
export const TILESET_TEXTURE = 'tiles-tex';
export const PLAYER_TEXTURE = 'player';
export const TILE_SIZE = 20;

const PLAYER_WIDTH = 20;
const PLAYER_HEIGHT = 32;

export function generateTilesetTexture(scene: Phaser.Scene): void {
  const cols = 4;
  const canvas = scene.textures.createCanvas(TILESET_TEXTURE, TILE_SIZE * cols, TILE_SIZE);
  if (!canvas) return;
  const ctx = canvas.getContext();

  // 0: 잔디 블록
  ctx.fillStyle = '#6b4a2b';
  ctx.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
  ctx.fillStyle = '#4caf50';
  ctx.fillRect(0, 0, TILE_SIZE, 5);

  // 1: 나무 판자
  ctx.fillStyle = '#8e6b3d';
  ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, TILE_SIZE);
  ctx.fillStyle = '#a37d47';
  ctx.fillRect(TILE_SIZE, 0, TILE_SIZE, 4);
  ctx.strokeStyle = '#5a4327';
  ctx.strokeRect(TILE_SIZE + 0.5, 0.5, TILE_SIZE - 1, TILE_SIZE - 1);

  // 2: 어두운 흙
  ctx.fillStyle = '#3e2b17';
  ctx.fillRect(TILE_SIZE * 2, 0, TILE_SIZE, TILE_SIZE);
  ctx.fillStyle = '#2a1d0f';
  for (let i = 0; i < 4; i++) {
    ctx.fillRect(TILE_SIZE * 2 + i * 5, i * 5, 3, 3);
  }

  // 3: 돌
  ctx.fillStyle = '#555566';
  ctx.fillRect(TILE_SIZE * 3, 0, TILE_SIZE, TILE_SIZE);

  canvas.refresh();
}

export function generatePlayerTexture(scene: Phaser.Scene): void {
  const gfx = scene.add.graphics();
  gfx.fillStyle(0xf1c40f, 1);
  gfx.fillRect(0, 0, PLAYER_WIDTH, PLAYER_HEIGHT);
  gfx.fillStyle(0x333333, 1);
  gfx.fillRect(13, 8, 3, 3);
  gfx.generateTexture(PLAYER_TEXTURE, PLAYER_WIDTH, PLAYER_HEIGHT);
  gfx.destroy();
}
