# meico-tools

Renders expressive MIDI based on MPM and MEI using [meico](https://github.com/cemfi/meico) — with support for isolation and exaggeration of individual performance parameters.

## Endpoints

- **`/convert`** — MEI to MSM conversion
- **`/perform`** — expressive MIDI rendering from MEI + MPM

## Build & Run

```
./gradlew :server:shadowJar
java -jar server/build/libs/server-all.jar
```

## Test

```
./gradlew test
```
