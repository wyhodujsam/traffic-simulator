# Copilot Instructions — Traffic Simulator

## Code Quality Workflow

When asked to check code quality, validate clean code, or fix violations:

1. **Compile**: `mvn clean compile -q`
2. **PMD**: `mvn pmd:pmd pmd:cpd -q` → read `target/pmd.xml`
3. **Checkstyle**: `mvn checkstyle:checkstyle -q` → read `target/checkstyle-result.xml`
4. **SpotBugs**: `mvn spotbugs:spotbugs -q` → read `target/spotbugsXml.xml`
5. **Spotless check**: `mvn spotless:check -q` (auto-fix: `mvn spotless:apply`)
6. **Tests**: `mvn test -q`

After analysis, summarize issues by severity and fix from CRITICAL down.

## Java/Spring Boot Conventions

- Java 17, Spring Boot 3.x, Maven
- Constructor injection only (no @Autowired on fields)
- All if/else/for/while must have braces {}
- No star imports (use explicit imports)
- No catch(Exception) — use specific types
- Method max 60 lines, class max 500 lines, max 5 parameters
- Use records for DTOs and parameter objects
- google-java-format AOSP style (enforced by Spotless)

## Architecture

- engine/ — simulation logic (PhysicsEngine, IntersectionManager, LaneChangeEngine)
- model/ — domain objects (Vehicle, Road, Lane, Intersection)
- controller/ — REST + WebSocket endpoints
- dto/ — data transfer objects
- config/ — Spring configuration, map loading
- scheduler/ — tick emitter

## Testing

- JUnit 5 + Mockito for unit tests
- Cucumber BDD for integration scenarios
- ArchUnit for architecture rules (src/test/.../architecture/ArchitectureTest.java)
- Run: `mvn test` (196 tests)
