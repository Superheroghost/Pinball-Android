# Neon Nexus Pinball (Android)

A native Android pinball game: Kotlin + OpenGL ES 2.0 rendering, JBox2D physics,
procedural audio, no game engine.

## Layout

```
app/src/main/java/com/superheroghost/neonpinball/
  sim/          Pure-Kotlin simulation core (no Android imports)
    PhysicsWorld   jBox2D wrapper: bodies, fixtures, contact routing
    TableGeometry  Full table: walls, arch, lanes, slings, drop bank,
                   spinner, ramp tube, scoop, one-way gates, sensors
    GameSim        World orchestration: stepping, balls, plunger, drains,
                   layer switching (playfield <-> ramp), ball search
    RulesEngine    Ruleset: scoring, lanes, locks, multiball, jackpots,
                   extra ball, skill shot, ball save, combos, overdrive
    Elements       Bumpers, slingshots, drop targets, standups, spinner...
    Flipper        Torque-driven revolute joint flippers
  gl/           Camera2D + GeometryBatch: single-draw-call vertex-color
                batching, additive glow layering, no per-frame allocations
  game/         GameSession (ball flow), GameLoop (fixed timestep +
                interpolation), InputState (multitouch zones), Particles,
                PinballRenderer (procedural neon playfield), AudioSynth
                (runtime PCM synthesis -> SoundPool), HudView
  MainActivity  Title screen / settings / high scores (standard views)
  GameActivity  GL surface host, HUD overlay, pause, haptics routing
jbox2d/         Vendored JBox2D sources (java-library module)
harness/        Headless JVM test harness: physics + rules + full-game E2E
```

## Building

Open in Android Studio (or `./gradlew assembleDebug`). Requires the Android
SDK; minSdk 26, compileSdk/targetSdk 36.

The simulation core, rules engine, session flow and renderer-side game logic
contain zero Android dependencies and are compiled and tested headlessly:

```
./harness/build-sim.sh HarnessMain      # physics probes (launch, gates, flippers)
./harness/build-sim.sh RulesTestMain    # rules engine unit tests (45 checks)
./harness/build-sim.sh SessionTestMain  # full 3-ball game E2E on real physics
./harness/build-app.sh                  # compile-check every app source vs android.jar
```

(The harness scripts expect local JDK/Kotlinc toolchains under `/home/user/tools`;
adjust the `TOOLS` env var as needed.)

## Controls

- Touch left half: left flipper; right half: right flipper.
- Right edge of the lower screen: pull down and release to launch.

## Ruleset

- **N·E·X lanes** complete → bonus multiplier up (2x–6x) + lock credit.
- **Standups** (pair) / **drop bank** clears → lock credits; 2 credits light LOCK.
- **Ramp shot** with lock lit → ball locks. Lock 2 balls → **MULTIBALL**.
- **Ramp during multiball** → JACKPOT; 3 jackpots light SUPER at the orbit.
- **Orbit** with SUPER lit → SUPER JACKPOT.
- Skill shot on launch (lit lane), 8 s ball save, combo chains, 6 objective
  arrows → **OVERDRIVE** (2x scoring, 20 s), 2 bank clears → **EXTRA BALL**.
- Ball-end bonus = weighted stats × bonus multiplier.

## Design notes

- Single render thread: fixed-timestep sim stepping (240 Hz) driven from the
  GL frame callback, render interpolation between steps.
- Two-plane physics: the ramp tube is a second collision plane switched by
  shot sensors; balls on the ramp don't collide with playfield objects.
- All sounds are synthesized at runtime (additive partials + noise
  transients), so the game ships with zero audio assets.
- Ball-search watchdog relocates balls that rest >10 s in one spot
  (deterministic; position-anchored, immune to flipper-motor jitter).
