# MyGame

메이플스토리풍 2D MMO를 만들며 **Java 언어와 객체지향을 학습**하는 프로젝트입니다.

- **Server**: 순수 Java 21 + Java-WebSocket (Spring 미사용, 학습 목적)
- **Client**: Phaser 3 + TypeScript + Vite
- **통신**: WebSocket + JSON 패킷

자세한 방향성/로드맵은 [CLAUDE.md](./CLAUDE.md) 참고.

---

## 현재 Phase: **A — 에코 연결 + 캐릭터 움직이기**

## 실행 방법

### 1. 서버 실행
```bash
cd server
./gradlew run
```
서버가 `ws://localhost:9999`에서 대기합니다.

### 2. 클라이언트 실행
새 터미널에서:
```bash
cd client
npm install
npm run dev
```
브라우저에서 Vite가 안내하는 주소(기본 `http://localhost:5173`)로 접속.

### 동작 확인
- 방향키로 캐릭터(사각형)를 움직입니다.
- 이동 시 서버에 `MOVE` 패킷이 전송되고, 서버 콘솔에 로그가 찍힙니다.
- 서버는 에코로 응답하며, 클라이언트 콘솔에 응답 패킷이 찍힙니다.

---

## 디렉토리

```
server/   # 순수 Java 게임 서버
client/   # Phaser 3 웹 클라이언트
```
