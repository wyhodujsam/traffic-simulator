# Stack Research

**Domain:** Traffic simulation web application
**Researched:** 2026-03-27
**Confidence:** HIGH

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|---|---|---|---|
| Java | 17 LTS | Backend runtime | LTS, Spring Boot 3.x minimum requirement, record types for simulation DTOs |
| Spring Boot | 3.3.x | Backend framework | Latest stable 3.x, native WebSocket + STOMP support, autoconfiguration |
| Maven | 3.9.x | Backend build | Standard Java build tool, Spring Initializr default |
| React | 18.3.x | Frontend UI framework | Concurrent rendering, stable hooks API, strong TypeScript support |
| TypeScript | 5.4.x | Frontend type safety | Full JSX support, strict mode for simulation state types |
| Vite | 5.x | Frontend build + dev server | Fast HMR, native ESM, replaces CRA — standard in 2025 |
| Node.js | 20 LTS | Frontend tooling runtime | LTS, compatible with Vite 5 and all React 18 tooling |

---

### Supporting Libraries

#### Backend (Maven / Spring)

| Library | Version | Purpose | Notes |
|---|---|---|---|
| `spring-boot-starter-websocket` | 3.3.x (managed) | WebSocket + STOMP broker | Includes SockJS fallback support, in-memory broker |
| `spring-boot-starter-web` | 3.3.x | REST endpoints (control API, map config) | For start/stop/config REST calls alongside WS |
| `jackson-databind` | 2.17.x (managed) | JSON serialization of simulation state | Spring Boot auto-configures; use `@JsonProperty` on DTOs |
| `lombok` | 1.18.32 | Reduce boilerplate on simulation entities | `@Data`, `@Builder` for Car, Road, Tick snapshot records |
| `spring-boot-starter-test` | 3.3.x | JUnit 5, Mockito, AssertJ | Full test stack, no extras needed |

#### Frontend (npm)

| Library | Version | Purpose | Notes |
|---|---|---|---|
| `@stomp/stompjs` | 7.x | STOMP protocol over WebSocket | Pure STOMP client, no jQuery dependency |
| `sockjs-client` | 1.6.x | SockJS transport fallback | Required when server uses SockJS endpoint |
| `zustand` | 4.x | Simulation state management | Minimal boilerplate, ideal for high-frequency real-time updates; avoids Redux re-render overhead |
| `react-konva` / `konva` | 9.x / 9.x | Canvas 2D scene graph | Optional — use only if DOM-managed canvas objects are needed |
| `vitest` | 1.x | Frontend unit testing | Vite-native, same config as build, replaces Jest |
| `@testing-library/react` | 16.x | Component testing | Standard React Testing Library |

> **Canvas strategy decision:** For this simulation (hundreds of moving rectangles at 10–30 fps), raw HTML5 Canvas API (`useRef` + `requestAnimationFrame`) outperforms react-konva because konva re-creates scene objects per frame. Use raw Canvas unless you need hit-testing or drag-and-drop map editing (v2 scope).

---

### Development Tools

| Tool | Version | Purpose |
|---|---|---|
| Spring Initializr | — | Project bootstrap at start.spring.io |
| ESLint | 9.x | Frontend linting (flat config era) |
| Prettier | 3.x | Code formatting frontend |
| Checkstyle / SpotBugs | latest | Backend static analysis (optional) |
| Docker Compose | 3.x | Local dev orchestration (backend + frontend together) |
| Postman / Bruno | latest | WebSocket and REST manual testing |

---

## Installation

### Backend — `pom.xml` key dependencies

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Backend — WebSocket STOMP configuration skeleton

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");       // outbound: simulation ticks
        registry.setApplicationDestinationPrefixes("/app"); // inbound: commands
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // SockJS fallback for older environments
    }
}
```

### Frontend — npm install

```bash
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install @stomp/stompjs sockjs-client zustand
npm install -D vitest @testing-library/react @testing-library/jest-dom
```

### Frontend — STOMP client connection pattern

```typescript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    onConnect: () => {
        client.subscribe('/topic/simulation', (message) => {
            const tick = JSON.parse(message.body);
            useSimulationStore.getState().setTick(tick);
        });
    },
});
client.activate();
```

### Frontend — Zustand store pattern for tick data

```typescript
import { create } from 'zustand';

interface SimulationStore {
    tick: SimulationTick | null;
    setTick: (tick: SimulationTick) => void;
}

export const useSimulationStore = create<SimulationStore>((set) => ({
    tick: null,
    setTick: (tick) => set({ tick }),
}));
```

### Frontend — Canvas rendering with requestAnimationFrame

```typescript
const canvasRef = useRef<HTMLCanvasElement>(null);
const tick = useSimulationStore((s) => s.tick);

useEffect(() => {
    const ctx = canvasRef.current?.getContext('2d');
    if (!ctx || !tick) return;
    ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);
    tick.vehicles.forEach(v => {
        ctx.save();
        ctx.translate(v.x, v.y);
        ctx.rotate(v.angle);
        ctx.fillRect(-v.width / 2, -v.height / 2, v.width, v.height);
        ctx.restore();
    });
}, [tick]);
```

---

## Alternatives Considered

| Alternative | Considered For | Why Rejected |
|---|---|---|
| Redux Toolkit | State management | Excessive boilerplate for real-time data; re-render patterns worse than Zustand for high-frequency ticks |
| react-konva | Canvas rendering | Scene graph overhead per frame; fine for editors, bad for 30 fps simulation with 500+ vehicles |
| PixiJS | Canvas rendering | WebGL-based — overkill for 2D rectangles, adds complexity without benefit at this scale |
| Native WebSocket (no STOMP) | Backend comms | STOMP adds pub/sub routing, client subscription management, and reconnect handling — worth the small overhead |
| Javalin / Vert.x | Backend framework | Valid, but Spring Boot is user constraint and has excellent WebSocket support |
| Valtio / Jotai | State management | Less popular, smaller ecosystem; Zustand more adopted in 2025 for real-time use cases |
| SVG rendering | Visualization | Poor performance beyond ~100 animated elements; Canvas is correct choice |
| Server-Sent Events (SSE) | Real-time comms | One-directional only — cannot send commands (pause, add obstacle) from frontend |

---

## What NOT to Use

| Technology | Reason |
|---|---|
| Create React App (CRA) | Deprecated, unmaintained since 2023 — use Vite |
| jQuery + SockJS | Old pattern; `@stomp/stompjs` 7.x is standalone, no jQuery |
| Redux (classic) | Too verbose for real-time simulation state |
| WebFlux / Project Reactor | Reactive stack adds complexity not needed here; classic Spring MVC + WebSocket is simpler and sufficient |
| D3.js | SVG-based data viz library — wrong tool for animated simulation rendering |
| Hibernate / JPA / any database | Out of scope — simulation is pure in-memory state |
| Spring Security | Not needed (single-user, no auth); adds configuration overhead |
| RxJS | Overkill for this use case; Zustand + STOMP subscription is simpler |
| Three.js | 3D WebGL library — simulation is 2D top-down |

---

## Version Compatibility

| Constraint | Detail |
|---|---|
| Spring Boot 3.x requires Java 17+ | Java 17 is the minimum — confirmed compatible |
| Spring Boot 3.3.x uses Jackson 2.17.x | No manual Jackson version needed in pom.xml |
| `@stomp/stompjs` 7.x drops IE11 support | Acceptable — modern browser target |
| `sockjs-client` 1.6.x is ESM-compatible | Works with Vite 5 without polyfill hacks |
| Vite 5.x requires Node 18+ | Use Node 20 LTS |
| React 18.3.x + TypeScript 5.4.x | `@types/react` 18.3.x needed — included in vite react-ts template |
| Zustand 4.x requires React 16.8+ | No issue with React 18 |
| Vitest 1.x + Vite 5.x | Same Vite config shared — no Jest config needed |
| Lombok 1.18.32 + Java 17 | Fully compatible; use `annotationProcessorPaths` in Maven |

---

## Sources

- Spring Boot 3.3 Release Notes — https://spring.io/blog/2024/05/23/spring-boot-3-3-0-available-now
- Spring WebSocket Documentation — https://docs.spring.io/spring-framework/reference/web/websocket.html
- @stomp/stompjs v7 Migration Guide — https://stomp-js.github.io/stomp-js/
- Zustand README — https://github.com/pmndrs/zustand
- Vite 5 Migration Guide — https://vitejs.dev/guide/migration
- HTML5 Canvas Performance Best Practices — MDN Web Docs
- Intelligent Driver Model (IDM) — Treiber et al., 2000 (simulation physics reference)
- Nagel-Schreckenberg Model — cellular automaton traffic model reference
