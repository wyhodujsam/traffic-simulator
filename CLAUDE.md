<!-- GSD:project-start source:PROJECT.md -->
## Project

**Traffic Simulator**

Aplikacja webowa symulujaca ruch uliczny w widoku z gory (top-down). Samochody przedstawione jako prostokaty poruszaja sie po wielopasmowych drogach, przyspieszajac i zwalniajac z indywidualnymi parametrami fizycznymi. Celem jest badanie powstawania korkow — phantom traffic jams, propagacja fali hamowania, wplyw przeszkod i zwezen na plynnosc ruchu. Stack: Java 17 + Spring Boot (backend), React + TypeScript (frontend), WebSocket.

**Core Value:** Wierna symulacja fizyki ruchu drogowego — samochody realistycznie przyspieszaja, hamuja i reaguja na otoczenie, umozliwiajac obserwacje emergentnych zjawisk korkowych.

### Constraints

- **Tech stack**: Java 17 + Spring Boot 3.x (backend), React 18 + TypeScript + Vite (frontend) — preferencja uzytkownika
- **Komunikacja**: WebSocket (STOMP over SockJS) — real-time wymagane
- **Rendering**: HTML5 Canvas — wydajnosc dla setek pojazdow
- **Build**: Maven (backend), Vite (frontend) — standardowy tooling
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

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
### Development Tools
| Tool | Version | Purpose |
|---|---|---|
| Spring Initializr | — | Project bootstrap at start.spring.io |
| ESLint | 9.x | Frontend linting (flat config era) |
| Prettier | 3.x | Code formatting frontend |
| Checkstyle / SpotBugs | latest | Backend static analysis (optional) |
| Docker Compose | 3.x | Local dev orchestration (backend + frontend together) |
| Postman / Bruno | latest | WebSocket and REST manual testing |
## Installation
### Backend — `pom.xml` key dependencies
### Backend — WebSocket STOMP configuration skeleton
### Frontend — npm install
### Frontend — STOMP client connection pattern
### Frontend — Zustand store pattern for tick data
### Frontend — Canvas rendering with requestAnimationFrame
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
## Sources
- Spring Boot 3.3 Release Notes — https://spring.io/blog/2024/05/23/spring-boot-3-3-0-available-now
- Spring WebSocket Documentation — https://docs.spring.io/spring-framework/reference/web/websocket.html
- @stomp/stompjs v7 Migration Guide — https://stomp-js.github.io/stomp-js/
- Zustand README — https://github.com/pmndrs/zustand
- Vite 5 Migration Guide — https://vitejs.dev/guide/migration
- HTML5 Canvas Performance Best Practices — MDN Web Docs
- Intelligent Driver Model (IDM) — Treiber et al., 2000 (simulation physics reference)
- Nagel-Schreckenberg Model — cellular automaton traffic model reference
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

See `.planning/CONVENTIONS.md` for coding conventions (loaded by GSD executor before implementation).
Gdy z analizy błędów (SonarQube, review, testy) wyniknie nowa reguła kodowania — dopisz ją do `.planning/CONVENTIONS.md`.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
