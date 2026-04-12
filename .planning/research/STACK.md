# Stack Research

**Domain:** Traffic simulation web application
**Researched:** 2026-03-27 (v1.0) / 2026-04-10 (v2.0 additions)
**Confidence:** HIGH (v1.0 stack) / MEDIUM-HIGH (v2.0 additions)

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

---

---

# v2.0 Milestone Additions: Map Screenshot to Simulation

**Researched:** 2026-04-10
**Scope:** Only new dependencies. Existing stack unchanged.

---

## New Frontend Addition — Google Maps Embed

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `@vis.gl/react-google-maps` | 1.8.x | Embed interactive Google Maps in React, expose center lat/lng/zoom | Google-sponsored library (vis.gl team), endorsed as the official React wrapper for Maps JavaScript API. First-class TypeScript. `APIProvider` + `Map` component. Version 1.0 GA released, now at 1.8.2 (published April 2026). Replaces all community forks. |

Google Maps JavaScript API itself has no npm package — it is loaded dynamically by `APIProvider` using the developer's API key.

**Installation:**
```bash
npm install @vis.gl/react-google-maps@1.8
```

**Usage pattern:**
```tsx
import { APIProvider, Map } from '@vis.gl/react-google-maps';

<APIProvider apiKey={import.meta.env.VITE_GOOGLE_MAPS_KEY}>
  <Map defaultCenter={{ lat: 52.23, lng: 21.01 }} defaultZoom={16} />
</APIProvider>
```

---

## Screenshot Capture Strategy: Static API (server-side, not html2canvas)

**Decision:** Use Google Maps Static API via backend call, NOT html2canvas on the frontend.

**Why not html2canvas:**
html2canvas has persistent, unresolved CORS failures with Google Maps tiles. Issues open on GitHub since 2014, still broken in Chrome/Safari in 2024 for Maps canvas layers. Custom overlay tiles are missing, WebGL renders black. Unreliable in production.

**How Static API works:**
1. User navigates the embedded map to the area of interest
2. Frontend reads current `center` (lat/lng) and `zoom` from `@vis.gl/react-google-maps` map state
3. Frontend sends `POST /api/map/capture { lat, lng, zoom }` to backend
4. Backend calls `https://maps.googleapis.com/maps/api/staticmap?center={lat},{lng}&zoom={zoom}&size=640x640&maptype=roadmap&key={API_KEY}` using Spring `RestClient`
5. Backend receives PNG bytes, passes to AI endpoint

No new libraries needed — Spring `RestClient` (built into Boot 3.2+) handles the HTTP call.

**Static API pricing (as of 2025 changes):** 10,000 free monthly requests, then $2/1,000. Sufficient for development and moderate use.

---

## New Backend Addition — AI Road Detection

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `spring-ai-openai-spring-boot-starter` | 1.0.3 | Spring AI integration — GPT-4o multimodal calls, structured JSON output | Spring AI 1.0 GA (May 2025). Native `Media` type for image bytes. `BeanOutputConverter<T>` returns typed Java DTOs — zero manual JSON parsing. Auto-configured from `application.properties`. Compatible with Spring Boot 3.3+. |

**AI model:** OpenAI `gpt-4o` with structured outputs (`response_format: json_schema`). GPT-4o achieves 100% schema adherence on structured output benchmarks. Has demonstrated road and lane detection capability (Grab case study: 20% improvement in lane count accuracy). Cost: ~$5/1M input tokens.

**pom.xml addition:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

**application.properties:**
```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o
```

**Spring AI usage pattern (multimodal + structured output):**
```java
@Service
public class RoadDetectionService {
    private final ChatClient chatClient;

    public RoadNetworkDto detect(byte[] mapImageBytes) {
        var converter = new BeanOutputConverter<>(RoadNetworkDto.class);
        String base64 = Base64.getEncoder().encodeToString(mapImageBytes);

        return chatClient.prompt()
            .user(u -> u
                .text("Analyze this road map image. " + converter.getFormat())
                .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(mapImageBytes)))
            .call()
            .entity(converter);
    }
}
```

No additional libraries needed. `java.util.Base64` is JDK 17 built-in. `ByteArrayResource` is Spring Core (already in classpath).

---

## Integration Points with Existing Stack

| New Capability | Integrates With | How |
|----------------|-----------------|-----|
| Google Maps embed | New `MapPage` React component | `APIProvider` wraps the route; React Router adds `/map` route alongside existing simulator |
| Screenshot capture | New `MapAnalysisController` (Spring) | Frontend sends `{lat, lng, zoom}` via `fetch`; controller calls Static API via `RestClient` |
| AI road detection | New `RoadDetectionService` (Spring) | Receives PNG bytes, calls GPT-4o via `ChatClient`, returns `RoadNetworkDto` |
| Simulation conversion | Existing `MapLoader` / `MapConfig` | `RoadNetworkDto` -> `RoadNetworkConverter` -> `MapConfig` JSON -> `MapLoader.load()` |
| Running the result | Existing `SimulationEngine` + WebSocket | No changes — `MapLoader` already accepts `MapConfig` at runtime |

---

## What NOT to Add for v2.0

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `html2canvas` | Persistent CORS failures with Google Maps — broken in Chrome/Safari for tile layers (GitHub issues unresolved since 2014) | Google Maps Static API (server-side HTTP call) |
| `@react-google-maps/api` | Community fork, not Google-sponsored. Google endorsed `@vis.gl/react-google-maps` as the official wrapper | `@vis.gl/react-google-maps` 1.8.x |
| Custom OpenAI HTTP client (OkHttp/RestTemplate) | Manual JSON schema, manual response parsing. Spring AI handles this cleanly. | `spring-ai-openai-spring-boot-starter` |
| OpenCV / Roboflow / custom ML model | No pretrained model for Google Maps roadmap tile road-segment extraction. GPT-4o needs zero training. | GPT-4o via Spring AI |
| Google Cloud Vision API | Labels objects — does not understand road topology or lane structure | GPT-4o structured outputs |
| Separate image-processing microservice | Overengineering — existing Spring Boot backend can call Static API and GPT-4o in a single request handler | Single `POST /api/map/analyze` endpoint |

---

## v2.0 Version Compatibility

| Constraint | Detail |
|------------|--------|
| `@vis.gl/react-google-maps` 1.8.x requires React 17+ | Compatible with project's React 18.3.x |
| Spring AI 1.0.x requires Spring Boot 3.x | Compatible with project's Spring Boot 3.3.5 |
| Spring AI 1.0.x requires Java 17+ | Compatible with project's Java 17 |
| Spring AI 1.0 is in Maven Central | No snapshot/milestone repository needed — add dependency directly |
| GPT-4o structured outputs require `gpt-4o-2024-08-06` or newer | Model name `gpt-4o` resolves to latest stable automatically |
| Google Static API returns max 640×640 px (standard) | Sufficient for road topology; add `scale=2` param for 1280×1280 if lane detail insufficient |

---

## Open Questions / Low-Confidence Areas

- **Spring AI 1.0 + Spring Boot 3.3.5 exact compatibility:** Spring AI 1.0 GA examples show Boot 3.4.x. Spring AI requires Boot 3.x minimum — 3.3.5 should work. Verify at implementation time. If compilation fails, upgrade Boot to 3.4.x (non-breaking for this project). MEDIUM confidence.
- **GPT-4o road detection accuracy on first prompt:** GPT-4o has demonstrated lane counting from map imagery but exact prompt engineering for simulation-ready road segments will require iteration. Expect 2-3 prompt refinement cycles before output quality is acceptable. LOW-MEDIUM confidence on first-pass accuracy.
- **Static API 640px resolution for dense urban areas:** May miss narrow streets or ambiguous lane counts. Mitigate with `scale=2` parameter (returns 1280×1280 at same cost). Evaluate after initial testing.

---

## v2.0 Sources

- `@vis.gl/react-google-maps` npm (version 1.8.2, published ~April 2026): https://www.npmjs.com/package/@vis.gl/react-google-maps
- Google Maps React official docs: https://developers.google.com/maps/documentation/javascript/examples/rgm-basic-map
- Spring AI 1.0 GA release announcement: https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/
- Spring AI multimodality + images (Piotr Minkowski, March 2025): https://piotrminkowski.com/2025/03/04/spring-ai-with-multimodality-and-images/
- Spring AI image data extraction (Baeldung): https://www.baeldung.com/spring-ai-extract-data-from-images
- OpenAI structured outputs with vision: https://developers.openai.com/api/docs/guides/structured-outputs
- Google Static API docs: https://developers.google.com/maps/documentation/maps-static/start
- Google Maps Platform pricing 2025: https://developers.google.com/maps/documentation/maps-static/usage-and-billing
- html2canvas Google Maps CORS issue (unresolved, 2024): https://github.com/niklasvh/html2canvas/issues/3038
- GPT-4o lane detection (Grab case study): https://openai.com/index/introducing-vision-to-the-fine-tuning-api/
