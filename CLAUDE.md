# AntCraft

Fabric mod for Minecraft 26.3 (Java 25, Mojang names). See README.md for the design and roadmap.

## Test-driven development

Every behaviour change follows red, then green:

1. Write or change a test that describes the new behaviour.
2. Run it and confirm it fails for the expected reason. A compile error because the API does not exist yet counts as red.
3. Change production code until the test passes. Do not weaken the test to make it pass.
4. Refactor with the tests green.

Pick the test type by what is being tested:

- **Unit tests** (`src/test/java`, JUnit 5 with Fabric Loader JUnit): pure logic such as config parsing, pathfinding cost functions, task planning and blueprint parsing. Keep this logic in classes that do not need a running world. If a test needs registries, call `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()` in `@BeforeAll`.
- **Game tests** (`src/gametest/java`, Fabric GameTest API): bot behaviour in a real world, run on a headless server. Each test gets an empty 8x8x8 area with a stone floor at y=0 (see `AntBotGameTest.invokeTestMethod`). Use `helper.succeedWhen` for "eventually" assertions and keep `maxTicks` tight.

Game tests run on a dedicated server, so they enable bots with `AntCraftConfig.setValues(...)`. Tests in a batch run concurrently: never toggle global config to test the opt-in gate there. Test the gate with unit tests instead.

## Commands

```sh
./gradlew test          # unit tests
./gradlew runGameTest   # game tests (headless server)
./gradlew build         # compile + both test suites; this is what CI runs
./gradlew runClient     # play-test manually
```

Gradle needs write access to `~/.gradle`, so in the Claude Code sandbox run it with the sandbox disabled.
