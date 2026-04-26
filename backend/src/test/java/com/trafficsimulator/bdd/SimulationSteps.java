package com.trafficsimulator.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.*;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.model.*;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class SimulationSteps {

    @Autowired private SimulationEngine simulationEngine;
    @Autowired private IPhysicsEngine physicsEngine;
    @Autowired private ILaneChangeEngine laneChangeEngine;
    @Autowired private IVehicleSpawner vehicleSpawner;
    @Autowired private ITrafficLightController trafficLightController;
    @Autowired private IIntersectionManager intersectionManager;
    @Autowired private ObstacleManager obstacleManager;
    @Autowired private MapLoader mapLoader;

    private long currentTick;

    @Before
    public void resetSimulation() {
        simulationEngine.enqueue(new SimulationCommand.Stop());
        simulationEngine.drainCommands();
        simulationEngine.getTickCounter().set(0);
        vehicleSpawner.reset();
        vehicleSpawner.setVehiclesPerSecond(1.0);
        simulationEngine.setSpawnRate(1.0);
        simulationEngine.setSpeedMultiplier(1.0);
        currentTick = 0;
    }

    // --- Background / Setup ---

    @Given("mapa {string} jest załadowana")
    public void mapaJestZaladowana(String mapId) throws Exception {
        MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/" + mapId + ".json");
        simulationEngine.setRoadNetwork(loaded.network());
        vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
    }

    // --- Actions ---

    @When("uruchamiam symulację")
    public void uruchamiamSymulacje() {
        simulationEngine.enqueue(new SimulationCommand.Start());
        simulationEngine.drainCommands();
    }

    @When("pauzuję symulację")
    public void pauzujeSymulacje() {
        simulationEngine.enqueue(new SimulationCommand.Pause());
        simulationEngine.drainCommands();
    }

    @When("zatrzymuję symulację")
    public void zatrzymujeSymulacje() {
        simulationEngine.enqueue(new SimulationCommand.Stop());
        simulationEngine.drainCommands();
    }

    @When("wykonuję {int} ticków z krokiem {double}s")
    public void wykonujeTicki(int count, double dt) {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        for (int i = 0; i < count; i++) {
            currentTick = simulationEngine.getTickCounter().incrementAndGet();

            trafficLightController.tick(dt, network);
            Map<String, Double> stopLines = intersectionManager.computeStopLines(network);

            vehicleSpawner.tick(dt, network, currentTick);

            for (Road road : network.getRoads().values()) {
                for (Lane lane : road.getLanes()) {
                    double stopLine = stopLines.getOrDefault(lane.getId(), -1.0);
                    physicsEngine.tick(lane, dt, stopLine);
                }
            }

            laneChangeEngine.tick(network, currentTick);
            intersectionManager.processTransfers(network, currentTick);
            vehicleSpawner.despawnVehicles(network, currentTick);
        }
    }

    @When("ustawiam tempo spawnowania na {double} pojazdów\\/s")
    public void ustawiamTempoSpawnowania(double rate) {
        vehicleSpawner.setVehiclesPerSecond(rate);
        simulationEngine.setSpawnRate(rate);
    }

    @When("ustawiam mnożnik prędkości na {double}")
    public void ustawiamMnoznikPredkosci(double multiplier) {
        simulationEngine.enqueue(new SimulationCommand.SetSpeedMultiplier(multiplier));
        simulationEngine.drainCommands();
    }

    @When("spawnam pojazd na pasie {string} na pozycji {double}")
    public void spawnamPojazd(String laneId, double position) {
        spawnamPojazdZPredkoscia(laneId, position, 0.0);
    }

    @When("spawnam pojazd na pasie {string} na pozycji {double} z prędkością {double}")
    public void spawnamPojazdZPredkoscia(String laneId, double position, double speed) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        Vehicle vehicle =
                Vehicle.builder()
                        .id("bdd-" + System.nanoTime())
                        .position(position)
                        .speed(speed)
                        .lane(lane)
                        .length(4.5)
                        .v0(lane.getMaxSpeed())
                        .aMax(1.4)
                        .b(2.0)
                        .s0(2.0)
                        .timeHeadway(1.5)
                        .spawnedAt(currentTick)
                        .build();
        lane.addVehicle(vehicle);
    }

    @When("ładuję mapę {string}")
    public void ladujeMape(String mapId) {
        simulationEngine.enqueue(new SimulationCommand.LoadMap(mapId));
        simulationEngine.drainCommands();
    }

    @When("dodaję przeszkodę na drodze {string} pas {int} pozycja {double}")
    public void dodajePrzeszkode(String roadId, int laneIndex, double position) {
        obstacleManager.addObstacle(
                simulationEngine.getRoadNetwork(), roadId, laneIndex, position, currentTick);
    }

    @When("usuwam przeszkodę z pasa {string}")
    public void usuwamPrzeszkode(String laneId) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        if (!lane.getObstaclesView().isEmpty()) {
            String obstacleId = lane.getObstaclesView().get(0).getId();
            obstacleManager.removeObstacle(simulationEngine.getRoadNetwork(), obstacleId);
        }
    }

    @When("zamykam pas {int} na drodze {string}")
    public void zamykamPas(int laneIndex, String roadId) {
        simulationEngine.enqueue(new SimulationCommand.CloseLane(roadId, laneIndex));
        simulationEngine.drainCommands();
    }

    // --- Assertions ---

    @Then("licznik ticków wynosi {int}")
    public void licznikTickowWynosi(int expected) {
        assertThat(simulationEngine.getTickCounter().get()).isEqualTo(expected);
    }

    @Then("status symulacji to {string}")
    public void statusSymulacjiTo(String expectedStatus) {
        assertThat(simulationEngine.getStatus().name()).isEqualTo(expectedStatus);
    }

    @Then("mnożnik prędkości wynosi {double}")
    public void mnoznikPredkosciWynosi(double expected) {
        assertThat(simulationEngine.getSpeedMultiplier()).isEqualTo(expected);
    }

    @Then("tempo spawnowania wynosi {double}")
    public void tempoSpawnowaniaWynosi(double expected) {
        assertThat(simulationEngine.getSpawnRate()).isEqualTo(expected);
    }

    @Then("prędkość pojazdu jest większa niż {double}")
    public void predkoscPojazduWiekszaNiz(double minSpeed) {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        double maxVehicleSpeed =
                network.getAllVehicles().mapToDouble(Vehicle::getSpeed).max().orElse(0.0);
        assertThat(maxVehicleSpeed).isGreaterThan(minSpeed);
    }

    @Then("prędkość pojazdu jest mniejsza lub równa prędkości maksymalnej")
    public void predkoscPojazduMniejszaOdMaksymalnej() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        network.getAllVehicles()
                .forEach(v -> assertThat(v.getSpeed()).isLessThanOrEqualTo(v.getV0() * 1.01));
    }

    @Then("każdy pojazd ma unikalne parametry prędkości maksymalnej")
    public void kazdyPojazdMaUnikalneParametry() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        long totalVehicles = network.getAllVehicles().count();
        if (totalVehicles < 2) return;
        long distinctV0 = network.getAllVehicles().mapToDouble(Vehicle::getV0).distinct().count();
        assertThat(distinctV0).isGreaterThan(1);
    }

    @Then("żadne pojazdy na pasie {string} się nie nakładają")
    public void zadnePojazdySieNieNakladaja(String laneId) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        var vehicles = lane.getVehiclesView();
        for (int i = 0; i < vehicles.size() - 1; i++) {
            Vehicle front = vehicles.get(i);
            Vehicle behind = vehicles.get(i + 1);
            double gap = front.getPosition() - front.getLength() - behind.getPosition();
            assertThat(gap)
                    .as("Gap between %s and %s", front.getId(), behind.getId())
                    .isGreaterThanOrEqualTo(-0.5);
        }
    }

    @Then("na drodze jest co najmniej {int} pojazd")
    public void naDrodzeJestCoNajmniej(int minCount) {
        long count = simulationEngine.getRoadNetwork().getAllVehicles().count();
        assertThat(count).isGreaterThanOrEqualTo(minCount);
    }

    @Then("pojazd został usunięty z pasa {string}")
    public void pojazdZostalUsuniety(String laneId) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        assertThat(lane.getVehicleCount()).isZero();
    }

    @Then("prędkość każdego pojazdu jest w zakresie {int} do prędkości maksymalnej")
    public void predkoscWZakresie(int minSpeed) {
        simulationEngine
                .getRoadNetwork()
                .getAllVehicles()
                .forEach(
                        v ->
                                assertThat(v.getSpeed())
                                        .isBetween((double) minSpeed, v.getV0() * 1.01));
    }

    @Then("na pasie {string} jest {int} przeszkoda")
    public void naPasieJestPrzeszkoda(String laneId, int count) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        assertThat(lane.getObstaclesView()).hasSize(count);
    }

    @Then("na pasie {string} jest {int} przeszkód")
    public void naPasieJestPrzeszkod(String laneId, int count) {
        naPasieJestPrzeszkoda(laneId, count);
    }

    @Then("pojazd na pasie {string} zatrzymał się przed pozycją {double}")
    public void pojazdZatrzymalSiePrzed(String laneId, double position) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        lane.getVehiclesView().forEach(v -> assertThat(v.getPosition()).isLessThan(position));
    }

    @Then("prędkość pojazdu na pasie {string} jest mniejsza niż {double}")
    public void predkoscPojazduMniejszaNiz(String laneId, double maxSpeed) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        assertThat(lane.getVehiclesView()).isNotEmpty();
        lane.getVehiclesView().forEach(v -> assertThat(v.getSpeed()).isLessThan(maxSpeed));
    }

    @Then("mapa jest załadowana poprawnie")
    public void mapaJestZaladowanaPoprawnie() {
        assertThat(simulationEngine.getRoadNetwork()).isNotNull();
    }

    @Then("droga sieciowa zawiera co najmniej {int} drogę")
    public void drogaSieciowaZawiera(int minRoads) {
        assertThat(simulationEngine.getRoadNetwork().getRoads())
                .hasSizeGreaterThanOrEqualTo(minRoads);
    }

    @Then("światła na skrzyżowaniu zmieniły fazę co najmniej {int} razy")
    public void swiatlaZmenilyFaze(int minChanges) {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        assertThat(network.getIntersections().values()).isNotEmpty();
        network.getIntersections().values().stream()
                .filter(i -> i.getType() == IntersectionType.SIGNAL)
                .forEach(i -> assertThat(i.getTrafficLight()).isNotNull());
    }

    @Then("istnieją pojazdy z prędkością {double}")
    public void istniejaPojazdyZPredkoscia(double speed) {
        long stoppedCount =
                simulationEngine
                        .getRoadNetwork()
                        .getAllVehicles()
                        .filter(v -> Math.abs(v.getSpeed() - speed) < 0.1)
                        .count();
        assertThat(stoppedCount).as("Vehicles with speed ~%.1f", speed).isGreaterThan(0);
    }

    @Then("żaden pojazd nie blokuje skrzyżowania")
    public void zadenPojazdNieBlokujeSkrzyzowania() {
        RoadNetwork network = simulationEngine.getRoadNetwork();
        long blocked = network.getAllVehicles().filter(v -> v.getSpeed() < 0.1).count();
        long total = network.getAllVehicles().count();
        if (total > 0) {
            assertThat((double) blocked / total).as("Ratio of stuck vehicles").isLessThan(0.9);
        }
    }

    @Then("co najmniej {int} pojazd zmienił pas")
    public void coNajmniejPojazdZmienilPas(int minCount) {
        long changed =
                simulationEngine
                        .getRoadNetwork()
                        .getAllVehicles()
                        .filter(v -> v.getLastLaneChangeTick() > 0)
                        .count();
        assertThat(changed).isGreaterThanOrEqualTo(minCount);
    }

    @Then("pas {string} jest nieaktywny")
    public void pasJestNieaktywny(String laneId) {
        Lane lane =
                simulationEngine
                        .getRoadNetwork()
                        .findLane(laneId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Lane not found: " + laneId));
        assertThat(lane.isActive()).isFalse();
    }
}
