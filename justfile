# ugly-os task runner
# Run `just` with no args to list available recipes.

launcher := "launcher"

# List available recipes.
default:
    @just --list

# Build the launcher debug APK.
build:
    cd {{launcher}} && ./gradlew assembleDebug

# Install the launcher on the connected device.
install:
    cd {{launcher}} && ./gradlew installDebug

# Run the launcher unit tests.
test:
    cd {{launcher}} && ./gradlew test
