import Phaser from 'phaser';
import { showLogin } from './auth/LoginScreen';
import { SERVER_URL } from './config';
import { GameScene } from './scenes/GameScene';
import { WebSocketClient } from './network/WebSocketClient';

/**
 * 진입점. 게임은 로그인 성공 후에만 시작한다.
 *
 * 흐름: WS 연결 → HTML 로그인 폼 → AUTH 성공 → GameScene 시작.
 * 이미 인증된 소켓은 GameScene 으로 그대로 넘겨, JOIN 요청이 같은 세션에서 이루어진다.
 */
async function bootstrap(): Promise<void> {
  const network = new WebSocketClient(SERVER_URL);
  network.connect();
  // 연결이 되기 전에 로그인 폼이 submit 되면 send 가 무시되므로, 연결 대기 후 폼 활성화.
  await waitForOpen(network);

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

function waitForOpen(network: WebSocketClient): Promise<void> {
  if (network.isOpen) return Promise.resolve();
  return new Promise<void>((resolve) => network.onOpen(resolve));
}

bootstrap().catch((err) => console.error('[Boot] 실패:', err));
