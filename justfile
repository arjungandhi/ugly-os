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

# Watch sources and redeploy the launcher on every change (no true hot reload).
dev:
    cd {{launcher}} && watchexec --restart --exts kt,xml \
        --watch app/src --watch common/src \
        -- './gradlew installDebug && adb shell am start -n com.uglyos.launcher/.MainActivity'

# Run the launcher unit tests.
test:
    cd {{launcher}} && ./gradlew test

# Run Android lint on the launcher.
lint:
    cd {{launcher}} && ./gradlew lint
