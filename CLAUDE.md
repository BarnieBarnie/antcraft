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
- **Game tests** (`src/gametest/java`, Fabric GameTest API): bot behaviour in a real world, run on a headless server. Each test gets an empty 8x8x8 area with a stone floor at y=0 (see `AntBotGameTest.invokeTestMethod`). Use `helper.succeedWhen` for "eventually" assertions and keep `maxTicks` tight: about twice the slowest measured pass. A failing test runs until its `maxTicks`, and a full run lasts as long as its slowest test. Each test logs the tick it ended on (`Game test <id> passed at tick <n> of <maxTicks>` in `build/check.log`); `scripts/ticks.py` with the logs of a few full runs lists the slowest pass of each test against its limit. Where a test can see it cannot pass any more, fail at once from `helper.onEachTick` (`GameTests.failOnGiveUp`, `GameTests.failIfIdle`) instead of waiting out the limit.

Game tests run on a dedicated server, so they enable bots with `AntCraftConfig.setValues(...)`. Tests in a batch run concurrently: never toggle global config to test the opt-in gate there. Test the gate with unit tests instead.

## Commands

```sh
./gradlew test          # unit tests
./gradlew runGameTest   # game tests (headless server)
./gradlew runGameTest -PgameTest='antcraft-gametest:miner_game_test*'   # only matching game tests
./gradlew runGameTest -PgameTest='antcraft-gametest:miner_game_test*,antcraft-gametest:builder_game_test*'   # several classes
./gradlew build         # compile + both test suites
./gradlew testIsolated  # unit tests, one JVM per class, catches bootstrap-order bugs; CI runs build and this
./gradlew runClient     # play-test manually
scripts/nightly.sh      # every game test twice on origin/main, files an issue per failing test (systemd timer at 03:00)
```

Agents run builds through `scripts/check.sh` instead of `./gradlew` directly. It takes the same tasks and options (default `build`), saves the full output to `build/check.log` and prints only `PASS`/`FAIL` with test counts, compile errors as `file:line: message`, and each failing test with its message. Its exit code is Gradle's, and it adds `--continue` so one run reports every failing suite. Grep `build/check.log` only when the summary is not enough.

```sh
scripts/check.sh                                                     # build
scripts/check.sh test                                                # unit tests
scripts/check.sh runGameTest -PgameTest='antcraft-gametest:miner_game_test*'
scripts/check.sh runGameTest -PgameTest='antcraft-gametest:miner_game_test*,antcraft-gametest:builder_game_test*'
```

Game test ids are `antcraft-gametest:<class_in_snake_case>_<method_in_snake_case>`, so `-PgameTest` takes a pattern such as `antcraft-gametest:miner_game_test*` for one class or the full id for one test. Patterns are globs (`*`, `?`); separate several with commas (no spaces) to run the tests of several classes in one run. A one-letter word inside a method name loses its underscore (`buildsACopy` is `builds_acopy`, not `builds_a_copy`), so to run one test, match part of its id, such as `antcraft-gametest:builder_game_test*fluid_wall*`. A pattern that matches no test fails the run. Without it, every game test runs.

Gradle needs write access to `~/.gradle`, so in the Claude Code sandbox run it with the sandbox disabled.
