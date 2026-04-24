import type { Packet, WebSocketClient } from '../network/WebSocketClient';

/**
 * Phase L — HTML 기반 로그인/회원가입 폼.
 *
 * 로그인 성공 시 프리-Phaser 단계에서 resolve 되어, 이후 GameScene 이
 * 이미 인증된 WebSocket 을 이어서 사용한다.
 */
export interface AuthedSession {
  readonly accountId: number;
  readonly username: string;
}

export function showLogin(network: WebSocketClient): Promise<AuthedSession> {
  const root = document.getElementById('login-root')!;
  const usernameInput = document.getElementById('login-username') as HTMLInputElement;
  const passwordInput = document.getElementById('login-password') as HTMLInputElement;
  const loginBtn = document.getElementById('btn-login') as HTMLButtonElement;
  const registerBtn = document.getElementById('btn-register') as HTMLButtonElement;
  const msg = document.getElementById('login-msg')!;

  usernameInput.focus();

  return new Promise<AuthedSession>((resolve) => {
    let awaiting: 'login' | 'register' | null = null;

    const setMsg = (text: string, ok = false) => {
      msg.textContent = text;
      msg.className = ok ? 'ok' : '';
    };

    network.on('AUTH', (p: Packet) => {
      const success = p.success as boolean;
      const error = (p.error as string) ?? '';
      if (!success) {
        setMsg(error || '인증 실패');
        awaiting = null;
        return;
      }
      // 회원가입 응답이면 자동으로 로그인 요청 전송
      if (awaiting === 'register') {
        setMsg('회원가입 완료. 로그인 중...', true);
        awaiting = 'login';
        network.send({
          type: 'LOGIN',
          username: usernameInput.value.trim(),
          password: passwordInput.value
        });
        return;
      }
      // 로그인 성공
      const accountId = p.accountId as number;
      const username = (p.username as string) ?? usernameInput.value.trim();
      root.style.display = 'none';
      resolve({ accountId, username });
    });

    network.on('ERROR', (p: Packet) => {
      setMsg((p.message as string) ?? '오류');
      awaiting = null;
    });

    const submit = (kind: 'login' | 'register') => {
      const username = usernameInput.value.trim();
      const password = passwordInput.value;
      if (!username || !password) {
        setMsg('아이디/비밀번호를 입력하세요.');
        return;
      }
      awaiting = kind;
      setMsg(kind === 'login' ? '로그인 중...' : '회원가입 중...', true);
      network.send({
        type: kind === 'login' ? 'LOGIN' : 'REGISTER',
        username,
        password
      });
    };

    loginBtn.addEventListener('click', () => submit('login'));
    registerBtn.addEventListener('click', () => submit('register'));
    // Enter 키로 로그인 제출
    for (const input of [usernameInput, passwordInput]) {
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          submit('login');
        }
      });
    }
  });
}
