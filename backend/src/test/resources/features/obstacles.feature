Feature: Road obstacles
  As a traffic simulator user
  I want to place and remove obstacles
  So that I can observe vehicle reactions to road blockages

  Background:
    Given mapa "straight-road" jest załadowana

  @OBST-01
  Scenario: User places an obstacle on a lane
    When dodaję przeszkodę na drodze "r1" pas 0 pozycja 400.0
    Then na pasie "r1-lane0" jest 1 przeszkoda

  @OBST-02
  Scenario: Obstacle blocks the entire lane
    When dodaję przeszkodę na drodze "r1" pas 0 pozycja 400.0
    And uruchamiam symulację
    And spawnam pojazd na pasie "r1-lane0" na pozycji 300.0 z prędkością 20.0
    And wykonuję 200 ticków z krokiem 0.05s
    Then pojazd na pasie "r1-lane0" zatrzymał się przed pozycją 400.0

  @OBST-03
  Scenario: User removes an obstacle
    When dodaję przeszkodę na drodze "r1" pas 0 pozycja 400.0
    And usuwam przeszkodę z pasa "r1-lane0"
    Then na pasie "r1-lane0" jest 0 przeszkód

  @OBST-04
  Scenario: Vehicles brake before obstacle
    When dodaję przeszkodę na drodze "r1" pas 0 pozycja 400.0
    And uruchamiam symulację
    And spawnam pojazd na pasie "r1-lane0" na pozycji 200.0 z prędkością 25.0
    And wykonuję 100 ticków z krokiem 0.05s
    Then prędkość pojazdu na pasie "r1-lane0" jest mniejsza niż 25.0
