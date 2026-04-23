/**
 * 클라이언트 런타임 설정.
 *
 * Vite 환경변수(VITE_*)를 우선 사용하고, 없으면 로컬 개발 기본값을 쓴다.
 * `.env.local` 에 `VITE_WS_URL=ws://somehost:9999` 형태로 오버라이드 가능.
 */
export const SERVER_URL: string =
  (import.meta.env.VITE_WS_URL as string | undefined) ?? 'ws://localhost:9999';
