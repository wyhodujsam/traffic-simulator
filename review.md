# Code Review — Traffic Simulator

**Data:** 2026-03-29
**Zakres:** Architektura, kod (backend + frontend), piramida testow
**Stan projektu:** 7/10 faz, 34/35 planow, 56 testow backend + 30 frontend

---

## 1. Architektura

### 1.1 Backend — pakiety i warstwy

```
com.trafficsimulator
├── model/          Vehicle, Lane, Road, RoadNetwork, Intersection, TrafficLight
├── engine/         SimulationEngine, TickEmitter, PhysicsEngine, LaneChangeEngine,
│                   TrafficLightController, IntersectionManager, VehicleSpawner,
│                   ObstacleManager, StatePublisher
├── controller/     SimulationController (REST), CommandHandler (STOMP)
├── config/         MapLoader, MapValidator, MapConfig, WebSocketConfig
├── dto/            VehicleDto, SimulationStateDto, TrafficLightDto, ...
└── TrafficSimulatorApplication
```

**Kierunek zaleznosci:** Brak cykli — `controller/ → engine/ → model/`, `dto/` niezalezne. Ale wewnatrz `engine/` jest problem — patrz nizej.

### 1.2 Frontend — drzewo komponentow

```
App
├── useWebSocket()              hook — STOMP + REST /api/roads
├── SimulationCanvas            dual-canvas (static roads + dynamic vehicles)
│   ├── drawRoads()             statyczna warstwa
│   ├── drawVehicles()          kolor wedlug predkosci
│   ├── drawObstacles()         czerwone X
│   ├── drawTrafficLights()     3-lampowe sygnalizatory
│   └── interpolateVehicles()   interpolacja 20Hz→60fps
├── ControlsPanel               start/pause/stop, slidery
└── StatsPanel                  pojazdy, predkosc, gestosc, przepustowosc

Store: Zustand (prev/curr snapshot, vehicleMap, sendCommand)
```

**Ocena frontendu:** Czysta architektura — hooki, renderery (pure functions), store Zustand. Dual-canvas + interpolacja daje plynne 60fps z 20Hz tickow. Tutaj problemow strukturalnych nie ma.

### 1.3 Tick pipeline (kluczowy flow)

```
1. drainCommands()                    przetworz komendy z kolejki
2. TrafficLightController.tick()      przesun sygnalizacje
3. IntersectionManager.stopLines()    wirtualne linie stopu
4. VehicleSpawner.tick()              spawn nowych pojazdow
5. PhysicsEngine.tick() [×substeps]   IDM + integracja Eulera
6. LaneChangeEngine.tick()            MOBIL + zmiany pasow
7. IntersectionManager.transfers()    transfer przez skrzyzowania
8. VehicleSpawner.despawn()           usun pojazdy z konca drogi
9. buildSnapshot() + broadcast()      wyslij stan do frontendu
```

### 1.4 Problemy architektoniczne

#### A1. TickEmitter jest god-classem (HIGH)

`TickEmitter.java` (~260 linii) laczy **8 odpowiedzialnosci** w jednej klasie:

| Odpowiedzialnosc | Linie | Gdzie powinno byc |
|-------------------|-------|-------------------|
| Orkiestracja tick pipeline | 57-126 | TickOrchestrator |
| Budowanie snapshot DTO | 128-194 | SnapshotBuilder |
| Projekcja Vehicle→piksele | 224-262 | Frontend (!) |
| Projekcja Obstacle→piksele | 199-216 | Frontend (!) |
| Budowanie TrafficLightDto | 152-171 | SnapshotBuilder |
| Obliczanie statystyk | 131-183 | StatsCalculator |
| Monitoring wydajnosci | 117-125 | Metryki (Micrometer) |
| Zarzadzanie sub-steppingiem | 76-97 | PhysicsEngine |

Zalezy od **7 klas engine**, calego modelu domenowego i wszystkich DTO. Kazda zmiana w projekcji, statystykach lub pipeline wymaga modyfikacji tej klasy.

**Fix:** Wydzielic `SnapshotBuilder` (projekcja + DTO), przeniesc projekcje pikseli na frontend, wydzielic orkiestracje do `TickOrchestrator`.

#### A2. Anemiczny model domenowy (MEDIUM)

**Vehicle.java** — 17 pol, zero metod. Czyste `@Data` (gettery/settery). Logika biznesowa rozproszona po 5 klasach:

| Kto mutuje Vehicle | Jakie pola | Plik |
|--------------------|-----------|------|
| PhysicsEngine | position, speed, acceleration | PhysicsEngine.java:145-147 |
| LaneChangeEngine | lane, laneChangeProgress, sourceIndex, forceLaneChange, zipperCandidate | LaneChangeEngine.java:371-396 |
| IntersectionManager | position, lane | IntersectionManager.java:246-250 |
| SimulationEngine | forceLaneChange | SimulationEngine.java:185 |
| VehicleSpawner | tworzy i usuwa | VehicleSpawner.java:77, 134 |

Vehicle to **publiczny mutowalny obiekt** bez wlasciciela. 5 klas modyfikuje go bezposrednio bez synchronizacji.

**Lane.java** — lista `vehicles` modyfikowana przez 5 roznych klas (add/remove/removeIf). Brak enkapsulacji — `lane.getVehicles().add(v)` zamiast `lane.addVehicle(v)`.

**RoadNetwork.java** — czysty kontener danych, zero metod query. Wszyscy iteruja `network.getRoads().values()` bezposrednio.

**Fix:** Dodac metody biznesowe do modelu (`Vehicle.updatePhysics()`, `Lane.addVehicle()`, `Lane.removeVehicle()`). Enkapsulowac kolekcje — nie eksponowac surowych list.

#### A3. Brak interfejsow dla silnikow (MEDIUM)

Wszystkie silniki sa konkretnymi klasami `@Component`:

```java
// TickEmitter zalezy od konkretnych implementacji
private final PhysicsEngine physicsEngine;        // brak IPhysicsEngine
private final LaneChangeEngine laneChangeEngine;  // brak ILaneChangeEngine
```

**Konsekwencje:**
- Nie mozna podmienic PhysicsEngine na alternatywna implementacje (np. Nagel-Schreckenberg zamiast IDM) bez modyfikacji TickEmitter
- Testy musza uzywac prawdziwych klas, nie mockow (bo brak interfejsow)
- LaneChangeEngine bezposrednio wywoluje `physicsEngine.computeAcceleration()` — scisle sprzezenie

**Fix:** Interfejsy `IPhysicsEngine`, `ILaneChangeEngine` — TickEmitter zalezy od abstrakcji, nie implementacji (Dependency Inversion).

#### A4. SimulationEngine — command processor + state container (MEDIUM)

`SimulationEngine.applyCommand()` to **150-liniowy switch** z 12 branchami. Kazdy branch mutuje model domenowy bezposrednio:

- `SetMaxSpeed` — iteruje wszystkie lane i nadpisuje maxSpeed (linie 147-151)
- `CloseLane` — ustawia `lane.active=false` + flaguje wszystkie pojazdy `forceLaneChange=true` (linie 181-186)
- `SetLightCycle` — rekonstruuje fazy swiatel z hardcoded GREEN/YELLOW/ALL_RED (linie 201-220)

SimulationEngine jest jednoczesnie **kontenerem stanu** (trzyma roadNetwork, status, spawnRate, speedMultiplier) i **procesorem komend**. Powinien delegowac do domain services.

**Fix:** Wydzielic `CommandDispatcher` z handlerami per komenda, lub przynajmniej delegowac mutacje do odpowiednich serwisow (`LaneService.closeLane()`, `TrafficLightService.setCycle()`).

#### A5. Backend zna piksele frontendu (MEDIUM)

`TickEmitter.java:43` — `LANE_WIDTH_PX = 14.0` hardcoded. Backend projektuje Vehicle na wspolrzedne canvas (x, y w pikselach), zamiast wysylac dane domenowe (roadId, laneId, position w metrach).

Komentarz w kodzie (linie 39-41) sam przyznaje problem:
> "This couples the backend to the frontend pixel layout."

**Konsekwencje:** Jesli frontend zmieni szerokosc pasa na 16px, pojazdy beda przesniete. Zero walidacji runtime. Ciche rozjazdy.

**Fix:** DTO wysyla `roadId + laneId + position (metry)`. Frontend projektuje piksele lokalnymi stalymi.

#### A6. Brak systemu zdarzen (LOW-MEDIUM)

Cala komunikacja miedzy silnikami to bezposrednie wywolania metod i mutacje wspoldzielonych obiektow. Brak event-driven architecture:

- Nie ma zdarzenia "vehicle spawned" / "vehicle despawned" / "vehicle changed lane"
- TickEmitter musi sam sprawdzac `laneChangeSourceIndex != -1` zeby wiedziec o animacji zmiany pasu
- Statystyki liczone proceduralnie z raw data zamiast reagowac na zdarzenia

**Fix (opcjonalny):** `DomainEvent` + `EventBus` odsprzeglby silniki. Np. `VehicleDespawnedEvent` zamiast manualnego zliczania throughput w TickEmitter.

### 1.5 Podsumowanie architektoniczne

| Problem | Severity | Fix difficulty |
|---------|----------|---------------|
| A1. TickEmitter god class | HIGH | MEDIUM — 3 nowe klasy |
| A2. Anemiczny model domenowy | MEDIUM | MEDIUM — enkapsulacja, metody biznesowe |
| A3. Brak interfejsow silnikow | MEDIUM | EASY — interfejsy + DI |
| A4. SimulationEngine robi za duzo | MEDIUM | MEDIUM — CommandDispatcher |
| A5. Backend zna piksele frontendu | MEDIUM | EASY — zmiana DTO |
| A6. Brak systemu zdarzen | LOW-MEDIUM | HIGH — event bus, refaktor |

**Ogolna ocena architektury: 6/10** — warstwy sa wyraznie rozdzielone (pakiety OK, kierunek zaleznosci OK), ale wewnatrz warstwy engine/ panuje chaos: god class, anemiczny model, brak abstrakcji, 5 klas mutuje ten sam obiekt bez synchronizacji.

---

## 2. Mocne strony

### Backend
- **Sealed interface** dla komend — kompilator wymusza obsluge kazdej komendy
- **IDM (Intelligent Driver Model)** poprawnie zaimplementowany z 5 guardami bezpieczenstwa (NaN, ujemna predkosc, zero-gap, clamp, kolizja)
- **MOBIL lane-change** z politeness factor, conflict resolution, zipper merge
- **Deadlock detection** w IntersectionManager — wymuszony przejazd po timeout
- **Thread-safe command queue** — `LinkedBlockingQueue` izoluje STOMP thread od tick thread
- **Walidacja map** — MapValidator sprawdza referencje node/road, fazy swiatel

### Frontend
- **Dual-canvas** — statyczne drogi rysowane raz, dynamiczne elementy co frame
- **Interpolacja** — plynne 60fps z 20Hz danych, obsluga spawn/despawn
- **Strict TypeScript** — `strict: true`, `noUnusedLocals`, `noUnusedParameters`
- **Debouncing** sliderow — 200ms, nie spamuje backendu
- **Hit testing** — klikanie na canvas z rotowanym AABB i projekcja wektorowa

---

## 3. Znalezione problemy

### CRITICAL

| # | Plik | Problem |
|---|------|---------|
| C1 | `SimulationEngine.java` | **Race condition na roadNetwork** — pole `volatile`, ale zawartość (listy pojazdów/przeszkod) nie jest synchronizowana. Tick thread iteruje `lane.getVehicles()` jednoczesnie z STOMP thread modyfikujacym przez komendy. Grozi `ConcurrentModificationException`. **Fix:** `ReentrantReadWriteLock` — read lock w TickEmitter, write lock przy aplikowaniu komend. |
| C2 | `SimulationEngine.java:227` | **LoadMap nie zaimplementowany** — komenda loguje info i nic nie robi. Frontend wysyla `LOAD_MAP` i nic sie nie dzieje. **Fix:** Dodac `mapLoader.loadFromClasspath()`, wyczyscic pojazdy, zresetowac tickCounter. |
| C3 | `IntersectionManager.java:246` | **Nieograniczony wzrost pozycji** — pojazd przeniesiony na outbound road z `position=0`, ale jesli droga jest bardzo krotka, w 1-2 tickach pozycja przekracza dlugosc lanu. Brak despawn point = pojazd tkwi z `position > lane.length` w nieskonczonosc. **Fix:** Clamp pozycji + bounds check w fizyce. |

### HIGH

| # | Plik | Problem |
|---|------|---------|
| H1 | `Lane.java:38-48` | **O(n) leader lookup** — `getLeader()` skanuje cala liste pojazdow. Przy 500 pojazdach = 250K operacji/tick. Budżet 50ms przekroczony przy ~200 pojazdach. **Fix:** `TreeMap<Double, Vehicle>` — O(log n) lookup. |
| H2 | `TickEmitter.java:76-97` | **Sub-stepping lamie simultaneous-update** — przy speedMultiplier > 1, drugi sub-step czyta juz zmodyfikowana pozycje lidera z pierwszego sub-stepu. Zmienia zachowanie modelu IDM. **Fix:** Snapshot pozycji liderow przed sub-steps. |
| H3 | `LaneChangeEngine.java:250` | **Przeszkody nie sa snapshotowane** — lane change evaluation czyta liste przeszkod po fizyce. Przeszkoda dodana mid-tick = pojazd merguje do pasa z przeszkoda. **Fix:** Snapshot obstacle list na poczatku ticka. |
| H4 | `LaneChangeEngine.java:422` | **Zipper mark resetowany co tick** — `setZipperCandidate(false)` na poczatku, potem mark 1 pojazd. Jesli ten nie zmieni pasa, kolejny czeka caly tick. Max throughput = 20 pojazdow/sek zamiast ~60-100. **Fix:** Persystentny mark + `lastZipperMergeTick`. |
| H5 | `SimulationEngine.java:166` | **Zamkniecie pasa wymusza wszystkie pojazdy naraz** — `forceLaneChange=true` na wszystkich, ale conflict resolution przepuszcza max 1 na tick. Reszta tkwi. **Fix:** Flaguj inkrementalnie, 1 pojazd/tick. |
| H6 | `useWebSocket.ts:12-17` | **Brak retry dla REST fetch /api/roads** — jesli siec padnie, roads sie nie zaladuja, canvas pusty, brak recovery. **Fix:** Exponential backoff. |
| H7 | `useWebSocket.ts:32-38` | **Stale closure sendCommand** — przy reconnect stary handler wisi w komponentach. Memory leak risk. **Fix:** Stable ref pattern. |

### MEDIUM

| # | Plik | Problem |
|---|------|---------|
| M1 | `IntersectionManager.java:39` | Stop line: `road.length - 2.0` moze byc ujemne dla krotkich drog. Fix: `Math.max(0, ...)`. |
| M2 | `IntersectionManager.java:119` | Kalkulacja kata right-of-way nie dziala dla standardowych skrzyzowan gridowych (0°/90°/180°/270°). |
| M3 | `VehicleSpawner.java:125` | `DespawnPoint.position` ignorowane — despawn zawsze na `lane.length`. |
| M4 | `SimulationEngine.java:144` | `SetMaxSpeed` nadpisuje per-road limity. Fix: `min(global, roadLimit)`. |
| M5 | `TickEmitter.java:42` | `LANE_WIDTH_PX = 14.0` hardcoded w backendzie — coupling z frontendem. |
| M6 | `WebSocketConfig.java:21` | `setAllowedOriginPatterns("*")` — brak ograniczenia CORS. |
| M7 | `MapLoader.java:126` | `TrafficLightPhase.PhaseType.valueOf()` bez walidacji w MapValidator — zly JSON crashuje app. |
| M8 | `drawVehicles.ts:8` | Hardcoded `DEFAULT_MAX_SPEED` (33.33 m/s) — nie reaguje na zmiane z backendu. |
| M9 | `hitTest.ts:45` | Brak tolerancji epsilon w projekcji wektorowej — floating point near boundaries. |
| M10 | `constants.ts:1` | Komentarz "must match backend" ale brak walidacji runtime — ciche rozjazdy. |

### LOW

| # | Plik | Problem |
|---|------|---------|
| L1 | `MapValidator.java` | Brak walidacji pozycji SpawnPoint/DespawnPoint (zakres, lane index). |
| L2 | `ObstacleManager.java:39` | Cichy clamp pozycji przeszkody zamiast bledu. |
| L3 | `App.tsx:10` | Brak responsive design, inline styles, brak `React.memo()`. |
| L4 | `ControlsPanel.tsx:160` | Hardcoded intersection ID `'n_center'` w debug dump. |

---

## 4. Piramida testow

### Inwentarz

| Warstwa | Plikow | Testow | % |
|---------|--------|--------|---|
| **Unit (backend)** | 10 | 57 | 66% |
| **Integration (backend)** | 4 | 18 | 21% |
| **E2E (backend)** | 1 | 2 | 2% |
| **Unit (frontend)** | 5 | 22 | 25% |
| **Integration (frontend)** | 0 | 0 | 0% |
| **E2E (frontend)** | 1 | 2 | 2% |
| **Razem** | | **~97** | |

**Ksztalt piramidy: OK** — 66% unit, 21% integration, 4% E2E. Proporcje prawidlowe.

### Pokrycie komponentow

| Komponent | Testy | Ocena |
|-----------|-------|-------|
| PhysicsEngine | 9 testow, IDM, edge cases, benchmark | ★★★★★ |
| LaneChangeEngine | 9 + 9 zipper + 3 narrowing | ★★★★★ |
| IntersectionManager | 8 testow, deadlock, right-of-way | ★★★★★ |
| VehicleSpawner | 8 + 3 despawn | ★★★★☆ |
| MapLoader | 8 + 3 intersection | ★★★★☆ |
| TrafficLight | 6 testow | ★★★★☆ |
| Zustand store | 7 + 4 (duplikat!) | ★★★★☆ |
| Interpolation | 6 testow | ★★★★☆ |
| SimulationCanvas | 7 testow (tylko rozmiar) | ★★★☆☆ |
| StatsPanel | 3 testy | ★★★☆☆ |
| TrafficLightController | 3 testy (za malo) | ★★★☆☆ |
| CommandQueue | 5 testow (timing-dependent) | ★★★☆☆ |
| **ControlsPanel** | **0 testow** | ❌ |
| **Canvas draw* functions** | **0 testow** | ❌ |
| **hitTest.ts** | **0 testow** | ❌ |
| **WebSocket reconnect** | **0 testow** | ❌ |

### Krytyczne luki

1. **ControlsPanel** — zero pokrycia, a to glowny interfejs uzytkownika
2. **Canvas rendering** (drawVehicles, drawRoads, drawTrafficLights) — brak testow
3. **hitTest.ts** — brak testow geometrii klikania
4. **WebSocket resilience** — brak testow reconnect/offline
5. **SimulationEngine tick loop** — brak bezposrednich testow petli
6. **Duplikat testow store** — dwa pliki testuja to samo

### Pokrycie testami — za niskie

**~97 testow na projekt z 7 ukonczonymi fazami i ~4000 linii kodu produkcyjnego to za malo.**

#### Backend — brakujace testy
| Komponent | Co brakuje | Priorytet |
|-----------|-----------|-----------|
| SimulationEngine | Testy stanu (STOPPED→RUNNING→PAUSED transitions), drainCommands ordering, applyCommand dla kazdej komendy | P0 |
| TickEmitter | Tick rate 20Hz, kolejnosc pipeline (physics before lane change), speedMultiplier wplyw na substeps | P0 |
| StatePublisher | Broadcast snapshot format, WebSocket message serialization | P1 |
| ObstacleManager | Add/remove obstacle, clamp behavior, obstacle-vehicle interaction | P1 |
| CommandHandler | STOMP message parsing, command routing, invalid command handling | P1 |
| SimulationController | REST endpoints (/api/status, /api/maps, /debug/dump), response format | P1 |
| MapValidator | Negative cases — brakujace node, cykliczne referencje, puste mapy, duplikaty ID | P2 |
| VehicleSpawner | Wiele spawn pointow, rozklad pojazdow, spawn na pelnym pasie | P2 |

#### Frontend — brakujace testy
| Komponent | Co brakuje | Priorytet |
|-----------|-----------|-----------|
| ControlsPanel | Klikniecia start/pause/stop, disabled states, slider→command dispatch, debounce behavior | P0 |
| drawVehicles/drawRoads/drawObstacles/drawTrafficLights | Renderowanie na mock canvas context, poprawnosc kolorow/pozycji/rotacji | P0 |
| hitTest.ts | Geometria klikania — proste drogi, skosy, krawedzie, tolerancja | P1 |
| useWebSocket | Reconnect, disconnect handling, message buffering, error states | P1 |
| App.tsx | Integracja komponentow, renderowanie warunkowe (brak danych / loading / error) | P2 |

#### Brakujace kategorie testow
| Kategoria | Stan | Co dodac |
|-----------|------|---------|
| **Testy kontraktowe (DTO)** | 1 test | JSON round-trip dla wszystkich DTO (VehicleDto, RoadDto, TrafficLightDto, CommandDto). Backend i frontend musza sie zgadzac. |
| **Testy integracyjne frontend** | 0 testow | Komponent + Zustand store + mock WebSocket — pelny flow od klikniecia do renderowania. |
| **Testy obciazeniowe** | 1 benchmark | Tick performance przy 100/500/1000 pojazdach. Regresja wydajnosci musi byc wykrywana automatycznie. |
| **Testy walidacji map** | 0 negative | MapValidator powinien miec testy negatywne — zle JSON-y, brakujace pola, cykliczne referencje, puste sieci. |
| **Testy WebSocket E2E** | 2 testy | Za malo — brak testow reconnect, latency spike, message loss, command round-trip. |

#### Szacunek brakujacych testow

| Warstwa | Obecne | Brakujace (min) | Docelowe |
|---------|--------|-----------------|----------|
| Unit backend | 57 | ~40 | ~100 |
| Integration backend | 18 | ~15 | ~35 |
| Unit frontend | 22 | ~30 | ~50 |
| Integration frontend | 0 | ~10 | ~10 |
| E2E | 4 | ~6 | ~10 |
| **Razem** | **~97** | **~100** | **~200** |

Obecne pokrycie to okolo **50% tego co powinno byc** dla projektu tej skali. Kluczowe: brakuje testow negatywnych (error paths), testow kontraktowych (DTO sync backend↔frontend), i calej warstwy integracyjnej frontendu.

### Antywzorce

- `CommandQueueTest` uzywa `Thread.sleep(1)` — flaky na wolnych maszynach
- Testy zipper merge zaleza od dokladnej liczby tickow (200, 600) — GC moze zepsuc
- `TickPipelineIntegrationTest.pauseResume` nie testuje faktycznej pauzy
- Frontend nie ma zadnych testow integracyjnych (komponent + store + WebSocket)

---

## 5. Ocena ogolna

| Kategoria | Ocena | Komentarz |
|-----------|-------|-----------|
| Architektura | **6/10** | Pakiety OK, ale god class (TickEmitter), anemiczny model, brak interfejsow, 5 klas mutuje Vehicle |
| Backend code | **6.5/10** | Dobra fizyka i logika, ale race condition i performance issues |
| Frontend code | **7.5/10** | Solidna interpolacja i rendering, braki w error handling |
| Piramida testow | **5/10** | Ksztalt OK, ale pokrycie ~50% docelowego. Brak testow negatywnych, kontraktowych, integracyjnych frontend |
| **Lacznie** | **6/10** | |

---

## 6. Rekomendacje — priorytet

### Natychmiast (blokujace)

1. **Race condition** (C1) — dodac `ReentrantReadWriteLock` w `SimulationEngine`
2. **Zaimplementowac LoadMap** (C2) — kluczowe dla fazy 9 (JSON Map Config)
3. **Position bounds** (C3) — clamp po transferze przez skrzyzowanie

### Nastepne — testy (pokrycie za niskie, ~50% docelowego)

4. **Testy ControlsPanel + canvas draw*** — zero pokrycia kluczowych komponentow frontend
5. **Testy SimulationEngine state machine** — brak testow transitions i applyCommand
6. **Testy TickEmitter** — brak testow pipeline ordering i tick rate
7. **Testy kontraktowe DTO** — JSON round-trip backend↔frontend, zapobiega cichym rozjazdom
8. **Testy negatywne MapValidator** — bledne JSON-y, brakujace pola, cykliczne referencje
9. **Testy integracyjne frontend** — komponent + store + mock WebSocket
10. **Testy WebSocket resilience** — reconnect, disconnect, message loss
11. **Usunac duplikat testow store** — skonsolidowac w jeden plik

### Nastepne — kod

12. **O(n) leader → TreeMap** (H1) — performance bottleneck
13. **Sub-stepping fix** (H2) — snapshot pozycji liderow

### Pozniej

14. Fix right-of-way angle calculation (M2)
15. Uzyc DespawnPoint.position (M3)
16. Responsive frontend (L3)
17. Metryki Micrometer (tick duration, vehicle count)
