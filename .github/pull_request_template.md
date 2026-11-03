# Pull request

## What this changes

<!-- One-paragraph summary of what the PR does and why. -->

## Type

- [ ] Bug fix
- [ ] New feature
- [ ] Calibration / measurement improvement
- [ ] Documentation
- [ ] Tooling / build

## Checklist

- [ ] The code compiles (`gradlew :app:assembleDebug`) with no new errors or warnings
- [ ] JVM unit tests still pass (`gradlew :app:testDebugUnitTest`)
- [ ] Python receiver tests still pass (`python -m unittest discover -s tools -p "test_*.py"`)
- [ ] Any new wire-protocol fields are covered by `LinkProtocolTest`
- [ ] Raw IMU values are not modified or filtered before being written to disk or streamed — any corrected values go to a separate field or file
- [ ] New calibration results carry an explicit `CalibrationProvenance` value
- [ ] No fabricated sensor readings, poses, or synchronization claims
- [ ] `docs/` updated if the change affects the wire format, calibration workflow, or timestamp conventions

## Testing done

<!-- How did you test this? Device model, duration, what you checked. -->

## Remaining limitations or follow-up work

<!-- Is anything unverified? What should the next PR address? -->
