# meico-tools

Renders expressive MIDI based on MPM and MEI using [meico](https://github.com/cemfi/meico) — with support for isolation and exaggeration of individual performance parameters.

## Endpoints

- **`/convert`** — MEI to MSM conversion
- **`/perform`** — expressive MIDI rendering from MEI + MPM

## Build & Run

```
./gradlew :server:shadowJar
ALLOWED_ORIGINS=http://localhost:5173 java -jar server/build/libs/server-all.jar
```

`ALLOWED_ORIGINS` is a comma-separated list of origins permitted for CORS requests. The server rejects all cross-origin requests if this variable is not set.

## Test

```
./gradlew test
```
