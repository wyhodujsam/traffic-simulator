# Plan poprawy architektury — Traffic Simulator

**Data:** 2026-03-29
**Bazuje na:** review.md (problemy A1-A6)
**Cel:** Przejsc z oceny 6/10 na 8/10 bez przepisywania od zera

---

## Zasady refaktoryzacji

1. **Kazdy krok ma dzialajace testy** — refaktor bez regresji
2. **Jeden problem na raz** — nie mieszac zmian
3. **Zachowac API** — TickEmitter nadal produkuje SimulationStateDto, fronend nie wymaga zmian (poza krokiem 6)
4. **Kolejnosc wedlug ryzyka** — najpierw to co moze zepsuc dzialajaca aplikacje (race condition, performance), potem czystosc kodu

---

## Krok 1: Enkapsulacja kolekcji w Lane i RoadNetwork

**Problem:** `lane.getVehicles()` zwraca mutowalny `List<Vehicle>` — 5 klas dodaje/usuwa bezposrednio. Brak kontroli, brak sortowania, brak walidacji.

**Zmiany:**

### Lane.java
```
- Uczynic `vehicles` i `obstacles` prywatnymi (usunac publiczny getter listy)
- Dodac metody:
    addVehicle(Vehicle v)        — dodaje + utrzymuje posortowana liste (wg position desc)
    removeVehicle(Vehicle v)     — usuwa
    removeVehiclesIf(Predicate)  — zastepuje lane.getVehicles().removeIf()
    getVehicleCount()            — zamiast lane.getVehicles().size()
    getVehiclesView()            — Collections.unmodifiableList() do odczytu
    clearVehicles()              — zamiast lane.getVehicles().clear()
- Analogicznie: addObstacle(), removeObstacle(), getObstaclesView()
```

### RoadNetwork.java
```
- Dodac metody query:
    getAllLanes()                — flat stream roads → lanes (eliminuje podwojne for-each)
    getAllVehicles()             — flat stream → vehicles
    findRoad(String id)         — Optional<Road>
    findLane(String laneId)     — Optional<Lane>
```

**Dotyka:** VehicleSpawner, LaneChangeEngine, IntersectionManager, SimulationEngine, TickEmitter, PhysicsEngine
**Testy:** Istniejace 56 backend testow musza przejsc po zamianie `.getVehicles().add()` na `.addVehicle()`
**Ryzyko:** Niskie — czysto mechaniczna zamiana, zadna logika sie nie zmienia

---

## Krok 2: Interfejsy silnikow + Dependency Inversion

**Problem:** TickEmitter i LaneChangeEngine zaleza od konkretnych klas. Nie mozna podmienic implementacji, nie mozna mockowac w testach.

**Zmiany:**

```
Nowe interfejsy:
    IPhysicsEngine
        void tick(Lane lane, double dt, double stopLine)
        double computeAcceleration(Vehicle v, double leaderSpeed, double leaderPos, double leaderLength, boolean hasLeader)

    ILaneChangeEngine
        void tick(RoadNetwork network, long currentTick)

    ITrafficLightController
        void tick(RoadNetwork network)

    IIntersectionManager
        Map<String, Double> computeStopLines(RoadNetwork network)
        void processTransfers(RoadNetwork network, long currentTick)

    IVehicleSpawner
        void tick(double dt, RoadNetwork network, long currentTick)
        void despawnVehicles(RoadNetwork network)
```

- Istniejace klasy implementuja swoje interfejsy (PhysicsEngine implements IPhysicsEngine itd.)
- TickEmitter zmienia typy pol z konkretnych na interfejsy
- LaneChangeEngine zmienia pole `physicsEngine` na `IPhysicsEngine`

**Dotyka:** Deklaracje pol w TickEmitter, LaneChangeEngine
**Testy:** Bez zmian — Spring wstrzykuje te same beany
**Ryzyko:** Niskie — czysto deklaratywna zmiana, zero logiki

---

## Krok 3: Wydzielenie SnapshotBuilder z TickEmitter

**Problem:** TickEmitter buduje DTO snapshot (130 linii) — projekcja pikseli, statystyki, traffic lights. To nie jest odpowiedzialnosc schedulera.

**Zmiany:**

```
Nowa klasa: SnapshotBuilder (@Component)
    Przeniesione metody:
        buildSnapshot(RoadNetwork, long tick, double spawnRate, double speedMultiplier,
                      VehicleSpawner spawner) → SimulationStateDto
        projectVehicle(Vehicle, Road) → VehicleDto                [private]
        projectObstacle(Obstacle, Road) → ObstacleDto             [private]
        buildTrafficLightDtos(RoadNetwork) → List<TrafficLightDto> [private]
    Przeniesione stale:
        LANE_WIDTH_PX = 14.0  (tymczasowo — krok 6 to usunie)

TickEmitter po zmianach:
    emitTick():
        1-8. Bez zmian (orkiestracja pipeline)
        9. SimulationStateDto snapshot = snapshotBuilder.buildSnapshot(...)
        10. statePublisher.broadcast(snapshot)
    Pola: +snapshotBuilder, -LANE_WIDTH_PX
    Rozmiar: ~260 linii → ~80 linii
```

**Dotyka:** Tylko TickEmitter (wydzielenie) + nowa klasa
**Testy:** Dodac SnapshotBuilderTest — projekcja pojazdow, statystyki, traffic lights
**Ryzyko:** Niskie — extract method refactoring, zero zmiany logiki

---

## Krok 4: Wydzielenie CommandDispatcher z SimulationEngine

**Problem:** `applyCommand()` to 150-liniowy switch, ktory mutuje model domenowy bezposrednio. SimulationEngine jest jednoczesnie state container i command processor.

**Zmiany:**

```
Nowa klasa: CommandDispatcher (@Component)
    Pole: SimulationEngine engine (do odczytu/zapisu stanu)
    Metoda: dispatch(SimulationCommand cmd)

    Wewnetrznie — handlery per komenda (prywatne metody, nie osobne klasy):
        handleStart()
        handleStop()
        handlePause()
        handleResume()
        handleSetSpawnRate(double rate)
        handleSetSpeedMultiplier(double mult)
        handleSetMaxSpeed(double speed)
        handleAddObstacle(String laneId, double position)
        handleRemoveObstacle(String laneId, double position)
        handleCloseLane(String roadId, int laneIndex)
        handleSetLightCycle(String intersectionId, double cycleDuration)
        handleLoadMap(String mapId)    ← ZAIMPLEMENTOWAC (bug C2 z review)

SimulationEngine po zmianach:
    - Usunac applyCommand()
    - Zostawic: enqueue(), drainCommands() (wywoluje commandDispatcher.dispatch()),
      gettery/settery stanu, loadDefaultMap()
    - Rozmiar: ~250 linii → ~100 linii
```

**Dotyka:** SimulationEngine, nowa klasa CommandDispatcher
**Testy:** Przenieść istniejace CommandQueueTest, dodac testy per handler (szczegolnie LoadMap ktory jest nowy)
**Ryzyko:** Srednie — wymaga ostroznego przenoszenia stanu. `drainCommands()` musi delegowac do dispatchera.

---

## Krok 5: Wzbogacenie modelu domenowego (Vehicle, Lane)

**Problem:** Vehicle to 17 pol bez metod. Fizyka, animacja i lifecycle mieszaja sie. 5 klas mutuje pola bezposrednio.

**Zmiany:**

### Vehicle.java — dodac metody biznesowe
```
Nowe metody:
    updatePhysics(double position, double speed, double acceleration)
        — jedyne miejsce mutujace pola fizyczne, waliduje invarianty
        — position >= 0, speed >= 0, speed <= v0

    startLaneChange(Lane targetLane, int sourceIndex, long currentTick)
        — ustawia lane, laneChangeProgress=0, laneChangeSourceIndex=sourceIndex,
          lastLaneChangeTick=currentTick
        — waliduje cooldown

    advanceLaneChangeProgress(double increment)
        — inkrementuje progress, clampuje do [0, 1]

    completeLaneChange()
        — resetuje laneChangeSourceIndex=-1, laneChangeProgress=0

    isInLaneChange() → boolean
        — laneChangeSourceIndex != -1

    canChangeLane(long currentTick, double cooldownTicks) → boolean
        — currentTick - lastLaneChangeTick >= cooldownTicks

Ograniczyc settery:
    - Usunac publiczne setPosition(), setSpeed(), setAcceleration()
      (zastapic przez updatePhysics())
    - Usunac publiczne setLane(), setLaneChangeProgress(), setLaneChangeSourceIndex()
      (zastapic przez startLaneChange(), advanceLaneChangeProgress())
    - Zostawic settery dla: forceLaneChange, zipperCandidate (flagi ustawiane z zewnatrz)
```

### Aktualizacja klientow
```
PhysicsEngine:
    v.setPosition(x); v.setSpeed(y); v.setAcceleration(z);
    → v.updatePhysics(x, y, z);

LaneChangeEngine.commitLaneChanges():
    vehicle.setLane(targetLane); vehicle.setLastLaneChangeTick(tick); ...
    → vehicle.startLaneChange(targetLane, sourceIndex, tick);

LaneChangeEngine.updateLaneChangeProgress():
    vehicle.setLaneChangeProgress(p);
    → vehicle.advanceLaneChangeProgress(increment);

IntersectionManager:
    v.setPosition(0.0); v.setLane(targetLane);
    → v.updatePhysics(0.0, v.getSpeed(), v.getAcceleration());
      v.setLane(targetLane);  // transfer = specjalny przypadek, nie lane change
```

**Dotyka:** Vehicle, PhysicsEngine, LaneChangeEngine, IntersectionManager, VehicleSpawner
**Testy:** Dodac VehicleTest — updatePhysics walidacja, startLaneChange cooldown, invarianty
**Ryzyko:** Srednie — duzo miejsc do zaktualizowania, ale kazda zmiana jest mechaniczna

---

## Krok 6: Przeniesienie projekcji pikseli na frontend

**Problem:** Backend wysyla x/y w pikselach (TickEmitter.LANE_WIDTH_PX = 14.0). Coupling backend↔frontend.

**Zmiany:**

### Backend — SnapshotBuilder (z kroku 3)
```
VehicleDto zmiana pol:
    - Usunac: x, y, angle (piksele)
    - Dodac: roadId, laneId, laneIndex, position (metry), laneChangeProgress, laneChangeSourceIndex

ObstacleDto zmiana pol:
    - Usunac: x, y, angle
    - Dodac: roadId, laneId, laneIndex, position

Usunac: projectVehicle(), projectObstacle(), LANE_WIDTH_PX
Uproscic: buildSnapshot() — mapuje Vehicle → VehicleDto bez projekcji geometrycznej
```

### Frontend — nowy modul projection.ts
```
Nowy plik: src/rendering/projection.ts
    projectVehicle(vehicle: VehicleDto, roads: RoadDto[]) → {x, y, angle}
    projectObstacle(obstacle: ObstacleDto, roads: RoadDto[]) → {x, y, angle}
    Stale: LANE_WIDTH_PX = 14  (teraz tylko tutaj)

Zmiana: drawVehicles.ts, drawObstacles.ts
    — uzywaja projection.ts zamiast gotowych x/y z DTO
```

**Dotyka:** Backend SnapshotBuilder + DTO, Frontend rendering + types
**Testy:** Backend — uproscic SnapshotBuilderTest. Frontend — dodac projection.test.ts
**Ryzyko:** Srednie — zmiana kontraktu API. Backend i frontend musza byc wdrozone jednoczesnie.

---

## Krok 7: O(n) leader lookup → posortowana lista

**Problem:** `Lane.getLeader()` to O(n) — bottleneck przy 200+ pojazdach.

**Zmiany:**

### Lane.java (buduje na kroku 1)
```
Wewnetrzna zmiana struktury danych:
    - List<Vehicle> vehicles → TreeMap<Double, Vehicle> vehiclesByPosition
    - Lub: utrzymywac ArrayList posortowany desc po position

Nowe/zmienione metody:
    getLeader(Vehicle v) → O(log n) — tailMap().firstEntry()
    findLeaderAt(double pos) → O(log n)
    findFollowerAt(double pos) → O(log n)
    addVehicle(Vehicle v) — wstawia w posortowane miejsce
    updateVehiclePosition(Vehicle v, double oldPos, double newPos) — reinsert

Wyzwanie: Fizyka zmienia pozycje co tick — trzeba reindeksowac.
    Opcja A: TreeMap — reinsert po kazdym updatePhysics (O(log n) per vehicle)
    Opcja B: Posortowany ArrayList — sortowac raz na tick po fizyce (O(n log n) per lane)
    Opcja C: Utrzymywac sorted invariant w addVehicle, odswiezac po fizyce

    Rekomendacja: Opcja B — najprostsza, O(n log n) per lane zamiast O(n²) per lane.
    W PhysicsEngine.tick() na koniec: lane.resortVehicles()
```

**Dotyka:** Lane, PhysicsEngine (dodac resort), LaneChangeEngine (bez zmian jesli API identyczne)
**Testy:** Dodac benchmark — 500 pojazdow, 100 tickow. Assert: czas < budżet.
**Ryzyko:** Srednie — sortowanie musi byc stabilne, pozycje musza byc unikalne (dodac epsilon)

---

## Krok 8: Synchronizacja — ReentrantReadWriteLock

**Problem:** Race condition (C1 z review) — tick thread i STOMP thread modyfikuja roadNetwork jednoczesnie.

**Zmiany:**

### SimulationEngine.java
```
Nowe pole:
    private final ReentrantReadWriteLock networkLock = new ReentrantReadWriteLock()

Nowe metody:
    readLock()  → networkLock.readLock()
    writeLock() → networkLock.writeLock()

CommandDispatcher:
    dispatch() → writeLock().lock() / unlock() wokol kazdej mutacji
```

### TickEmitter.java
```
emitTick():
    simulationEngine.readLock().lock();
    try {
        // caly pipeline 1-9
    } finally {
        simulationEngine.readLock().unlock();
    }
```

**Dotyka:** SimulationEngine, TickEmitter, CommandDispatcher
**Testy:** Dodac test wspolbieznosci — 100 watkow wysyla komendy podczas aktywnego ticka
**Ryzyko:** Srednie — lock moze spowolnic processing komend. Mierzyc latency przed/po.

---

## Podsumowanie — kolejnosc i zaleznosci

```
Krok 1: Enkapsulacja kolekcji ──────────────────────┐
Krok 2: Interfejsy silnikow (niezalezny) ──────────┐ │
                                                     │ │
Krok 3: SnapshotBuilder (zalezy od 1) ──────────────┤ │
Krok 4: CommandDispatcher (niezalezny od 1-3) ──────┤ │
                                                     │ │
Krok 5: Wzbogacenie Vehicle (zalezy od 1) ──────────┤ │
Krok 6: Projekcja na frontend (zalezy od 3) ────────┘ │
                                                       │
Krok 7: Posortowana lista (zalezy od 1) ──────────────┘
Krok 8: Synchronizacja (zalezy od 4)
```

**Mozna rownolegle:**
- Krok 1 + Krok 2 + Krok 4
- Po kroku 1: Krok 3 + Krok 5 + Krok 7
- Po kroku 3: Krok 6
- Po kroku 4: Krok 8

### Szacunek wplywu

| Krok | Nowe klasy | Zmienione klasy | Nowe testy | Wplyw na ocene |
|------|-----------|-----------------|------------|----------------|
| 1 | 0 | 7 | ~5 | +0.5 |
| 2 | 5 interfejsow | 3 | 0 | +0.3 |
| 3 | 1 (SnapshotBuilder) | 1 (TickEmitter) | ~5 | +0.5 |
| 4 | 1 (CommandDispatcher) | 1 (SimulationEngine) | ~8 | +0.5 |
| 5 | 0 | 5 | ~6 | +0.3 |
| 6 | 1 (projection.ts) | 4 backend + 3 frontend | ~4 | +0.3 |
| 7 | 0 | 2 | ~2 | +0.3 |
| 8 | 0 | 3 | ~2 | +0.3 |
| **Razem** | **8** | **~15** | **~32** | **6/10 → 9/10** |
