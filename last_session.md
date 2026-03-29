# Last Session — 2026-03-28/29

## Co zrobiono

### Fazy zaimplementowane (1-9, 11-12)
- **Phase 1-2**: Bootstrap + domain model (Vehicle, Lane, Road, WebSocket STOMP)
- **Phase 3**: IDM physics engine — car-following z 5 edge-case guards
- **Phase 4**: 20 Hz tick loop, command queue, StatePublisher, REST API
- **Phase 5**: Canvas rendering — 2-layer canvas, 60fps interpolation, controls + stats
- **Phase 6**: Live obstacles — click placement/removal, IDM braking, zipper merge
- **Phase 7**: MOBIL lane changes — two-phase update, road narrowing, zipper merge 1-za-1
- **Phase 8**: Traffic signals — configurable cycles, box-blocking, deadlock watchdog, priority intersections
- **Phase 9**: JSON map config — 5 predefined scenariuszy, frontend map selector, LOAD_MAP pipeline
- **Phase 11**: Architecture refactoring (8 kroków z ar-improvement.md):
  - Enkapsulacja kolekcji Lane/RoadNetwork
  - Interfejsy silników (DI)
  - SnapshotBuilder wydzielony z TickEmitter
  - CommandDispatcher wydzielony z SimulationEngine
  - Vehicle domain model enrichment
  - Projekcja pikseli przeniesiona na frontend
  - O(log n) sorted vehicle lookup
  - ReentrantReadWriteLock synchronization
- **Phase 12**: Intersection rendering refactor — clip roads at intersection boundary, draw intersection boxes, parallel in/out roads via perpendicular offset

### Bugi naprawione w trakcie
- Canvas za mały (computeCanvasSize używał bounding box zamiast absolutnych coords)
- Pojazdy nachodziły na przeszkody → position clamp (Guard 6)
- MOBIL nie uwzględniał przeszkód w target lane → obstacle-aware MOBIL
- Zipper merge nie działał → relaxed safety, skip gap-behind, rate-limit 1/2s/obstacle
- Pojazdy zmieniały pas tuż przed przeszkodą → speed-dependent gap check
- Yield zone blokowała wolny pas → usunięta, zastąpiona prostszym podejściem
- Skrzyżowanie: drogi nakładały się → perpendicular offset w MapLoader + clip rendering

### Testy
- **133 backend** (JUnit 5)
- **33 frontend** (Vitest)
- Dump State button do debugowania (GET /api/debug/dump)
- REST /api/command do skryptowego testowania

### Infrastruktura
- GitHub repo: wyhodujsam/traffic-simulator
- PR #1: feature/phases-1-8
- Backend: port 8085, frontend: port 5173 (Vite proxy)

## Co zostało

### Phase 10: Roundabouts, Priority Intersections & Polish
- Roundabout yield-on-entry
- Entry gating >80% capacity
- 60fps interpolation hardening
- WebSocket disconnect indicator

### Znane problemy
- `global is not defined` — SockJS/Vite runtime error (testy przechodzą, przeglądarka czasem nie)
- Intersection rendering — drogi są teraz równoległe ale wizualnie nadal wymaga dopracowania (kwadrat skrzyżowania, światła)
- construction-zone mapa: closedLanes działa ale spawner nie respektuje zamkniętych pasów (spawn rate musi być niski)
- highway-merge mapa: merge point intersection needs testing

## Preferencje użytkownika
- Pracuje z telefonu przez claude-remote
- Java/Spring Boot preferowany stack
- "Po prostu rób" — nie pytać "które naprawić?"
- Nie pushować do master bezpośrednio — feature branch + PR
- Zbijać drobne edycje w jedno Write zamiast wielu Edit calls
- Dump State button do szybkiego debugowania
