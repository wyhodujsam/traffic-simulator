Feature: Simulation controls
  As a traffic simulator user
  I want to control the simulation flow
  So that I can freely explore different scenarios

  Background:
    Given mapa "straight-road" jest załadowana

  @CTRL-01
  Scenario: Start, pause and stop simulation
    When uruchamiam symulację
    Then status symulacji to "RUNNING"
    When pauzuję symulację
    Then status symulacji to "PAUSED"
    When zatrzymuję symulację
    Then status symulacji to "STOPPED"

  @CTRL-02
  Scenario: Change simulation speed
    When uruchamiam symulację
    And ustawiam mnożnik prędkości na 2.0
    Then mnożnik prędkości wynosi 2.0

  @CTRL-03
  Scenario: Change vehicle spawn rate
    When uruchamiam symulację
    And ustawiam tempo spawnowania na 3.0 pojazdów/s
    Then tempo spawnowania wynosi 3.0

  @CTRL-05
  Scenario: Load predefined map scenario
    When ładuję mapę "phantom-jam-corridor"
    Then mapa jest załadowana poprawnie
    And droga sieciowa zawiera co najmniej 1 drogę
