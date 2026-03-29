# Phase 1 Research: Project Bootstrap & Infrastructure

**Phase:** 1 — Project Bootstrap & Infrastructure
**Goal:** Establish Maven + Vite monorepo, STOMP WebSocket wiring, and a working 20 Hz ping-pong tick loop before any simulation logic.
**Requirements:** INFR-01, INFR-02, INFR-03, SIM-08
**Researched:** 2026-03-27

---

## 1. Spring Boot 3.3.x pom.xml — Exact Dependencies

### Parent POM

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>
```

Use `3.3.5` (latest 3.3.x patch as of research date). Spring Boot BOM manages all transitive versions — do not specify `spring-framework`, `jackson`, or `slf4j` versions manually.

### Java Version Property

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

### Runtime Dependencies

```xml
<dependencies>
    <!-- WebSocket + STOMP broker (includes spring-messaging, spring-websocket) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>

    <!-- REST endpoints (for future control API + map loading) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Lombok (boilerplate reduction for DTOs and domain objects) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Test scope -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Jackson is **not listed separately** — `spring-boot-starter-web` and `spring-boot-starter-websocket` both pull in `jackson-databind` 2.17.x transitively via the Boot BOM. Adding it explicitly would risk version drift.

### Lombok Annotation Processor (required for Java 17+)

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Note: `${lombok.version}` is resolved by the Spring Boot BOM to `1.18.32` (the managed version for Boot 3.3.x). Do not hardcode it.

### What `spring-boot-starter-websocket` Includes

The starter transitively provides:
- `spring-websocket` — WebSocket server support and SockJS fallback
- `spring-messaging` — STOMP message broker (`@MessageMapping`, `SimpMessagingTemplate`)
- `spring-context` — `@Scheduled`, `TaskScheduler`, `ScheduledExecutorService` beans
- `tomcat-embed-websocket` — Tomcat's JSR-356 WebSocket implementation

No separate SockJS server dependency is needed — it is bundled in `spring-websocket`.

---

## 2. Vite 5 + React 18 + TypeScript 5.4 Project Setup

### Bootstrap Command (exact)

```bash
npm create vite@5 frontend -- --template react-ts
cd frontend
npm install
```

The `react-ts` template provides:
- React 18.3.x
- TypeScript 5.4.x with `tsconfig.json` already configured
- `@types/react` and `@types/react-dom` included
- Vite 5.x dev server with HMR

### Install Runtime Dependencies

```bash
npm install @stomp/stompjs@7 sockjs-client@1.6 zustand@4
```

### Install Dev Dependencies

```bash
npm install -D vitest@1 @testing-library/react@16 @testing-library/jest-dom jsdom
npm install -D @types/sockjs-client
```

Note: `@stomp/stompjs` 7.x includes its own TypeScript types — no `@types/stompjs` needed. `sockjs-client` requires `@types/sockjs-client` separately.

### TypeScript Strict Mode — tsconfig.json

The `react-ts` template produces a `tsconfig.json` that already enables `strict: true`. Confirm these are present:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

The key strict flags that matter for simulation state typing: `strict: true` (enables `strictNullChecks`, `strictFunctionTypes`, `strictPropertyInitialization`), `noUnusedLocals`, `noUnusedParameters`.

### Vite Dev Server Proxy Config — vite.config.ts

To proxy WebSocket and API calls from Vite's port 5173 to Spring Boot on port 8080:

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,          // WebSocket proxy — required for SockJS
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

The `ws: true` flag is critical — without it, Vite proxies HTTP but not the WebSocket upgrade handshake. SockJS will fall back to long-polling without it.

---

## 3. @stomp/stompjs 7.x + sockjs-client 1.6.x — React Hook Integration

### Key Design Decisions

**Problem with naive implementation:** Creating a STOMP `Client` inside a `useEffect` without cleanup leads to multiple active connections during React Strict Mode's double-invocation or hot-reload cycles.

**Correct pattern:** Use `useRef` to hold the STOMP client instance (not `useState`) so React's reconciliation does not re-create it on re-renders. Activate on mount, deactivate on cleanup.

### useWebSocket.ts — Production Pattern

```typescript
import { useEffect, useRef } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useSimulationStore } from './useSimulationStore';

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,       // auto-reconnect after 5 s
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        client.subscribe('/topic/simulation', (message: IMessage) => {
          const data = JSON.parse(message.body);
          useSimulationStore.getState().setTick(data);
        });
      },
      onDisconnect: () => {
        console.warn('[WS] Disconnected from simulation broker');
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP error', frame);
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
    };
  }, []); // empty deps — runs once on mount

  return clientRef;
}
```

**Why `getState()` instead of hook inside callback:** STOMP message callbacks are not React render functions — calling `useSimulationStore()` inside them would violate Rules of Hooks. `getState()` bypasses React's hook machinery and directly mutates the Zustand store, which then triggers re-renders on subscribed components.

### SockJS ESM Compatibility with Vite 5

`sockjs-client` 1.6.x is CommonJS. Vite handles the CJS→ESM conversion automatically via its `optimizeDeps` step. No manual config needed. However, the import syntax matters:

```typescript
// Correct — default import for CJS-wrapped module
import SockJS from 'sockjs-client';

// Wrong — named import won't work with Vite's CJS transform
import { SockJS } from 'sockjs-client';
```

If Vite reports a type error on the import, add to `vite.config.ts`:
```typescript
optimizeDeps: {
  include: ['sockjs-client'],
}
```

---

## 4. Zustand 4.x Store Pattern for Real-Time Simulation State

### Performance Consideration: useRef vs useState

For tick data arriving at 20 Hz, the wrong pattern causes 20 full React re-render cascades per second:
- `useState` inside the STOMP callback: triggers re-render on every tick for the whole component tree
- `useRef`: no re-render, but data is not reactive — components can't subscribe

**Zustand is the correct answer.** It provides selective subscriptions — only components that read from the store re-render, and only if their subscribed slice changed. For the smoke test phase (Phase 1), a single `tick` slice is sufficient.

### useSimulationStore.ts — Phase 1 Skeleton

```typescript
import { create } from 'zustand';

// Phase 1 DTO shape — just the tick counter
// Will expand to full SimulationStateDto in later phases
export interface TickDto {
  tick: number;
  timestamp: number;  // server epoch millis for latency measurement
}

interface SimulationStore {
  lastTick: TickDto | null;
  tickCount: number;
  setTick: (tick: TickDto) => void;
}

export const useSimulationStore = create<SimulationStore>((set) => ({
  lastTick: null,
  tickCount: 0,
  setTick: (tick) =>
    set((state) => ({
      lastTick: tick,
      tickCount: state.tickCount + 1,
    })),
}));
```

### Selective Subscription Pattern (avoid over-rendering)

```typescript
// Component only re-renders when tickCount changes — not on every tick object reference change
const tickCount = useSimulationStore((s) => s.tickCount);
const lastTick = useSimulationStore((s) => s.lastTick);
```

Zustand uses shallow equality on the selected value. Selecting the whole store object (`useSimulationStore()`) causes re-renders on every tick. Always select the minimal slice needed.

---

## 5. Spring Boot Tick Loop — @Scheduled vs ScheduledExecutorService

### Phase 1 Approach: @Scheduled (simplest)

For Phase 1's smoke test, `@Scheduled` is sufficient and requires zero manual thread management:

```java
@Component
@RequiredArgsConstructor
public class TickEmitter {

    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicLong tickCounter = new AtomicLong(0);

    @Scheduled(fixedRate = 50) // 50 ms = 20 Hz
    public void emitTick() {
        long tick = tickCounter.incrementAndGet();
        TickDto payload = new TickDto(tick, System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/simulation", payload);
    }
}
```

Enable scheduling in the main application class:

```java
@SpringBootApplication
@EnableScheduling
public class TrafficSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficSimulatorApplication.class, args);
    }
}
```

### Phase 4 Approach: ScheduledExecutorService (for full engine)

Phase 4 will replace `@Scheduled` with `ScheduledExecutorService` for precise tick timing and command queue integration:

```java
ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
executor.scheduleAtFixedRate(this::tick, 0, 50, TimeUnit.MILLISECONDS);
```

`scheduleAtFixedRate` compensates for tick execution time (if a tick takes 10 ms, the next fires 40 ms later, maintaining 20 Hz). `@Scheduled(fixedRate)` does the same in Spring's task scheduler. For Phase 1, either works — `@Scheduled` has less boilerplate.

**Why not fixedDelay?** `fixedDelay` waits N ms *after* the previous execution completes. At 20 Hz with variable execution time, the actual tick rate drifts. `fixedRate` is correct for simulation loops.

---

## 6. SimpMessagingTemplate — Broadcasting to /topic/simulation

### Injection Pattern

`SimpMessagingTemplate` is auto-configured by `spring-boot-starter-websocket`. Inject via constructor:

```java
@Component
@RequiredArgsConstructor
public class TickEmitter {
    private final SimpMessagingTemplate messagingTemplate;
    // ...
}
```

### Send Pattern

```java
messagingTemplate.convertAndSend("/topic/simulation", payload);
```

`convertAndSend` serializes `payload` to JSON using Jackson (auto-configured by Spring Boot). The destination `/topic/simulation` matches the `enableSimpleBroker("/topic")` prefix registered in `WebSocketConfig`.

### DTO Design for Phase 1

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TickDto {
    private long tick;
    private long timestamp;  // System.currentTimeMillis() for latency measurement
}
```

Jackson will serialize this to `{"tick": 42, "timestamp": 1711500000000}`. The `@Data` annotation from Lombok generates getters (required by Jackson for serialization), setters, equals/hashCode, and toString.

---

## 7. CORS Configuration for Vite Dev Server (port 5173)

### Two Approaches — Choose Based on Setup

**Approach A (recommended for Phase 1): Vite Proxy**

Configure Vite to proxy `/ws` to `localhost:8080` (covered in section 2). When using the proxy, the browser sends requests to port 5173, and Vite forwards them to 8080. From Spring Boot's perspective, requests come from `localhost:5173` but the `Origin` header is `localhost:5173`. The Vite proxy sets `changeOrigin: true`, which rewrites the `Host` header but **not** the `Origin` header for WebSocket upgrades.

Result: Spring Boot still sees `Origin: http://localhost:5173` and must allow it.

**Approach B: Spring Boot CORS + setAllowedOriginPatterns**

Already handled in `WebSocketConfig`:
```java
registry.addEndpoint("/ws")
    .setAllowedOriginPatterns("*")
    .withSockJS();
```

`setAllowedOriginPatterns("*")` is the Spring 5.3+ replacement for `setAllowedOrigins("*")`. The old `setAllowedOrigins("*")` was removed because it conflicted with `allowCredentials`. The pattern form allows wildcard matching without that restriction.

For CORS on REST endpoints (the `/api` prefix added in Phase 4+), add:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173")
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowedHeaders("*");
    }
}
```

Or use `@CrossOrigin` on individual controllers.

**For Phase 1, only the WebSocket CORS matters.** The `setAllowedOriginPatterns("*")` in `WebSocketConfig` covers it without additional configuration.

---

## 8. Maven Monorepo Structure for Combined Backend + Frontend Build

### Recommended Directory Layout

```
traffic-simulator/           <- Maven root module (aggregator)
├── pom.xml                  <- parent/aggregator POM
├── backend/                 <- Spring Boot module
│   ├── pom.xml
│   └── src/
│       └── main/java/...
└── frontend/                <- Vite project (NOT a Maven module)
    ├── package.json
    ├── vite.config.ts
    └── src/
```

### Option A: Frontend as Separate Process (Phase 1 approach — simplest)

Run backend and frontend independently during development:

```bash
# Terminal 1
cd backend && mvn spring-boot:run

# Terminal 2
cd frontend && npm run dev
```

No Maven integration of the frontend build. This is appropriate for Phase 1. The Vite proxy handles the connection.

### Option B: Maven + Frontend Maven Plugin (for production build later)

For a single `mvn package` that produces one deployable JAR (Phase 10 polish scope):

```xml
<!-- In backend/pom.xml -->
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.0</version>
    <configuration>
        <workingDirectory>../frontend</workingDirectory>
        <nodeVersion>v20.11.0</nodeVersion>
    </configuration>
    <executions>
        <execution>
            <id>install-node-and-npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
        </execution>
        <execution>
            <id>npm-install</id>
            <goals><goal>npm</goal></goals>
        </execution>
        <execution>
            <id>npm-build</id>
            <goals><goal>npm</goal></goals>
            <configuration>
                <arguments>run build</arguments>
            </configuration>
            <phase>generate-resources</phase>
        </execution>
    </executions>
</plugin>

<!-- Copy Vite dist/ to Spring Boot static resources -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-frontend</id>
            <phase>process-resources</phase>
            <goals><goal>copy-resources</goal></goals>
            <configuration>
                <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                <resources>
                    <resource>
                        <directory>../frontend/dist</directory>
                    </resource>
                </resources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Phase 1 decision:** Use Option A. Option B is complex setup that adds no value until the app is ready for deployment. Document Option B in the architecture notes for Phase 10.

---

## 9. WebSocket Config — Complete WebSocketConfig.java

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker for /topic destinations (server → client broadcasts)
        registry.enableSimpleBroker("/topic");
        // Prefix for messages routed to @MessageMapping methods (client → server)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // allow Vite dev server
                .withSockJS();                   // SockJS fallback transport
    }
}
```

**Note on SockJS transport negotiation:** SockJS tries transports in order: WebSocket → xhr-streaming → xhr-polling. In a standard Chrome/Firefox dev environment, it uses native WebSocket. `withSockJS()` is still included for robustness and future environments (corporate proxies that block WebSocket upgrade).

---

## 10. Project Directory Structure for Phase 1

```
traffic-simulator/
├── CLAUDE.md
├── .planning/
│   └── phases/01-project-bootstrap-infrastructure/
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/trafficsimulator/
│       ├── TrafficSimulatorApplication.java
│       ├── config/
│       │   └── WebSocketConfig.java
│       ├── dto/
│       │   └── TickDto.java
│       └── scheduler/
│           └── TickEmitter.java
└── frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── hooks/
        │   └── useWebSocket.ts
        └── store/
            └── useSimulationStore.ts
```

---

## Validation Architecture

### What "Phase 1 Complete" Means

Phase 1 is complete when a message originating from a `@Scheduled` method on the backend traverses the full path to a browser console log without errors, at 20 Hz, with latency under 100 ms.

### Validation Layer 1: Backend Tick Loop Isolated

**How to verify:**
- Run `mvn spring-boot:run` from `backend/`
- Check logs for "Tick #N" entries appearing every ~50 ms
- Use Spring Actuator (optional) or manual log timestamp verification

**What passes:** Log lines appear at ~50 ms intervals. No `NullPointerException` on `SimpMessagingTemplate` injection. No `NoSuchBeanDefinitionException` for `@Scheduled`.

**Common failure:** Forgetting `@EnableScheduling` on the main application class. Symptom: no log output, no errors.

### Validation Layer 2: WebSocket Endpoint Reachable

**How to verify:**
- Open browser DevTools → Network → WS filter
- Navigate to `http://localhost:5173` (Vite running)
- Look for a WebSocket connection to `/ws/...` (SockJS handshake URL pattern)
- Connection status should be `101 Switching Protocols`

**What passes:** WS connection established. SockJS transport type shows as `websocket` (not `xhr-polling`).

**Common failure:** CORS rejection. Symptom: browser shows `Connection refused` or `403 Forbidden` in Network tab. Fix: verify `setAllowedOriginPatterns("*")` in `WebSocketConfig`.

### Validation Layer 3: STOMP Subscription Receiving Messages

**How to verify:**
- Add a `console.log` in the STOMP `onConnect` callback and in the subscription handler
- Browser console should show:
  1. `[WS] Connected` on STOMP connect
  2. `{tick: 1, timestamp: ...}` messages arriving every 50 ms

**What passes:** Messages arrive. `tick` field increments monotonically. No `SyntaxError: Unexpected token` (malformed JSON). No `ReferenceError` on Zustand import.

**Common failure 1:** `sockjs-client` import error in Vite. Fix: add `sockjs-client` to `optimizeDeps.include` in vite.config.ts.

**Common failure 2:** STOMP client activates before Vite proxy is ready. Symptom: `connect` failure on first page load, then reconnects successfully. Fix: `reconnectDelay: 5000` in Client config handles this automatically.

### Validation Layer 4: Latency Measurement

**How to verify:**
- `TickDto` includes `timestamp: System.currentTimeMillis()` (server send time)
- Frontend logs `Date.now() - message.timestamp` per tick
- Expected: < 100 ms on localhost (typically 1–5 ms)

**What passes:** All measured latencies < 100 ms. No spikes > 200 ms in the first 60 seconds of operation.

**Common failure:** Large initial spike (100–300 ms) on first few ticks while SockJS handshake completes. Acceptable — only steady-state latency matters.

### Validation Layer 5: Zustand Store Receiving Updates

**How to verify:**
- In `App.tsx`, add a component that reads `tickCount` from the store and displays it
- The counter should increment at ~20 Hz
- Optionally use React DevTools to inspect the store state

**What passes:** `tickCount` increments continuously. No React warning about updates outside `act()` (which would indicate incorrect hook usage in the STOMP callback).

### End-to-End Smoke Test Checklist

```
[ ] 1. mvn spring-boot:run exits with "Started TrafficSimulatorApplication"
[ ] 2. Log shows "Tick #N" lines at 20 Hz (count 20 lines per second)
[ ] 3. npm run dev exits with "Local: http://localhost:5173/"
[ ] 4. Browser DevTools WS tab shows active connection to /ws
[ ] 5. Browser console shows tick JSON objects arriving every ~50 ms
[ ] 6. tick.tick field increments from 1 upward without gaps
[ ] 7. Measured latency (Date.now() - tick.timestamp) < 100 ms steady-state
[ ] 8. No console errors in browser (red entries)
[ ] 9. No exceptions in Spring Boot log
[ ] 10. Refreshing the browser page reconnects cleanly (STOMP reconnect works)
```

### Tools for Manual Inspection

- **Chrome DevTools → Network → WS tab**: inspect every STOMP frame in real-time
- **WebSocket King** (browser extension): alternative STOMP frame inspector
- **Spring Boot log level**: set `logging.level.org.springframework.web.socket=DEBUG` in `application.properties` to see every frame the broker processes
- **Zustand DevTools**: install `zustand/middleware` `devtools` wrapper to inspect store in Redux DevTools extension

---

## RESEARCH COMPLETE
