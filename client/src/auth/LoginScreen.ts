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
    /** 연결 시도 중 중복 클릭 방지 — 버튼 disable 과 함께 사용. */
    let connecting = false;

    const setMsg = (text: string, ok = false) => {
      msg.textContent = text;
      msg.className = ok ? 'ok' : '';
    };

    const setBusy = (busy: boolean) => {
      connecting = busy;
      loginBtn.disabled = busy;
      registerBtn.disabled = busy;
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

    const submit = async (kind: 'login' | 'register') => {
      if (connecting) return;
      const username = usernameInput.value.trim();
      const password = passwordInput.value;
      if (!username || !password) {
        setMsg('아이디/비밀번호를 입력하세요.');
        return;
      }
      // 1) 서버 연결 보장. 페이지 로드 후 처음이거나 이전 연결이 끊겼으면 새로 연결.
      if (!network.isOpen) {
        setMsg('서버에 연결 중...', true);
        setBusy(true);
        try {
          await network.ensureOpen();
        } catch {
          setMsg('서버에 연결할 수 없습니다. 서버 상태를 확인 후 다시 시도해 주세요.');
          setBusy(false);
          return;
        }
        setBusy(false);
      }
      // 2) 인증 패킷 송신
      awaiting = kind;
      setMsg(kind === 'login' ? '로그인 중...' : '회원가입 중...', true);
      network.send({
        type: kind === 'login' ? 'LOGIN' : 'REGISTER',
        username,
        password
      });
    };

    loginBtn.addEventListener('click', () => {
      void submit('login');
    });
    registerBtn.addEventListener('click', () => {
      void submit('register');
    });
    // Enter 키로 로그인 제출
    for (const input of [usernameInput, passwordInput]) {
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          void submit('login');
        }
      });
    }
  });
}
