import Phaser from 'phaser';
import { showLogin } from './auth/LoginScreen';
import { SERVER_URL } from './config';
import { GameScene } from './scenes/GameScene';
import { WebSocketClient } from './network/WebSocketClient';

/**
 * 진입점. 게임은 로그인 성공 후에만 시작한다.
 *
 * 흐름: HTML 로그인 폼 → (사용자 클릭) WS 지연 연결 → AUTH 성공 → GameScene 시작.
 * 페이지 로드 시점에는 WS 를 만들지 않는다 — 서버가 떠 있지 않은 상태에서 페이지를
 * 열어도 사용자가 로그인 버튼을 누르는 시점에 다시 시도할 수 있도록 지연 연결.
 */
async function bootstrap(): Promise<void> {
  const network = new WebSocketClient(SERVER_URL);

  const session = await showLogin(network);

  const config: Phaser.Types.Core.GameConfig = {
    type: Phaser.AUTO,
    parent: 'game-root',
    width: 800,
    height: 600,
    backgroundColor: '#2d2d3a',
    physics: {
      default: 'arcade',
      arcade: {
        gravity: { x: 0, y: 800 },
        debug: false
      }
    },
    scene: [new GameScene(network, session)]
  };

  new Phaser.Game(config);
}

bootstrap().catch((err) => console.error('[Boot] 실패:', err));
