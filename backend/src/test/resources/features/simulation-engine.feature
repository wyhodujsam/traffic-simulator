Feature: Traffic simulation engine
  As a traffic simulator user
  I want to control the simulation and observe vehicles
  So that I can study traffic jam phenomena

  Background:
    Given mapa "straight-road" jest załadowana

  @SIM-01
  Scenario: Simulation runs at 20 Hz tick loop
    When uruchamiam symulację
    And wykonuję 20 ticków z krokiem 0.05s
    Then licznik ticków wynosi 20

  @SIM-02
  Scenario: Vehicles have individual physics parameters
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 5.0 pojazdów/s
    And wykonuję 20 ticków z krokiem 0.05s
    Then każdy pojazd ma unikalne parametry prędkości maksymalnej

  @SIM-03
  Scenario: Vehicles accelerate smoothly using IDM model
    When uruchamiam symulację
    And spawnam pojazd na pasie "r1-lane0" na pozycji 0.0
    And wykonuję 100 ticków z krokiem 0.05s
    Then prędkość pojazdu jest większa niż 0.0
    And prędkość pojazdu jest mniejsza lub równa prędkości maksymalnej

  @SIM-04
  Scenario: Vehicles maintain safe following distance
    When uruchamiam symulację
    And spawnam pojazd na pasie "r1-lane0" na pozycji 100.0
    And spawnam pojazd na pasie "r1-lane0" na pozycji 50.0
    And wykonuję 200 ticków z krokiem 0.05s
    Then żadne pojazdy na pasie "r1-lane0" się nie nakładają

  @SIM-05
  Scenario: Vehicles spawn at road entry points
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 2.0 pojazdów/s
    And wykonuję 40 ticków z krokiem 0.05s
    Then na drodze jest co najmniej 1 pojazd

  @SIM-06
  Scenario: Vehicles despawn at road exit points
    When spawnam pojazd na pasie "r1-lane0" na pozycji 790.0 z prędkością 30.0
    And ustawiam tempo spawnowania na 0.0 pojazdów/s
    And uruchamiam symulację
    And wykonuję 20 ticków z krokiem 0.05s
    Then pojazd został usunięty z pasa "r1-lane0"

  @SIM-07
  Scenario: Vehicle speed is clamped to [0, maxSpeed]
    When uruchamiam symulację
    And spawnam pojazd na pasie "r1-lane0" na pozycji 10.0
    And wykonuję 500 ticków z krokiem 0.05s
    Then prędkość każdego pojazdu jest w zakresie 0 do prędkości maksymalnej
