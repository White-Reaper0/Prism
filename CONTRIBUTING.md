# Contributing to PRISM

Thanks for your interest. PRISM is a research platform, not a polished consumer product —
contributions that make the measurements more honest or the pipeline more useful are welcome.

## Ground rules

**1. Never fabricate sensor data.**
Raw IMU values must flow to disk and the wire unchanged. If you apply a calibration correction,
put the corrected values in a separate field or separate file. The original `values` array in
`ImuSample` must always be the unmodified `SensorEvent.values`.

**2. Label what is estimated.**
Every calibration result must carry a `CalibrationProvenance`. Never call something `VERIFIED`
unless a controlled external test confirmed it. The norm of the gyro mean from a 4-second still
window is an `ESTIMATED` bias, not a verified one.

**3. Synchronization is not assumed.**
The camera HAL clock and the IMU sensor clock may not share an origin on every device. New code
must preserve both `sensorTimestampNs` and `arrivalElapsedNs` and must not claim the clocks are
synchronized without evidence.

**4. Nothing is silent.**
Dropped samples, gaps, rejected images, and calibration failures must be reported explicitly —
not swallowed, not hidden, not rounded away.

## Code style

- Kotlin: the project style file is in `.editorconfig`. Match the surrounding code.
- Python: standard PEP 8. The receiver must stay importable by the test suite without a network.
- New wire-protocol fields must be covered by a test in `LinkProtocolTest.kt`.
- New calibration estimators must document their assumptions in the `uncertaintyNote` field.

## Running tests before opening a PR

```bash
# JVM (no device needed)
./gradlew :app:testDebugUnitTest

# Python (no device needed)
python -m unittest discover -s tools -p "test_*.py" -v
```

The PR template has a full checklist. Please fill it in — it's short.

## Priority areas (where help is most useful)

1. **IMU bias correction** — apply the stored calibration to streamed and recorded values.
   The architecture hook (`ImuCollector.streamListener`) already exists; it needs a
   `BiasCorrector` that subtracts `CalibrationStore.imu?.gyroscopeBiasRadS` and writes the
   result to a `corrected_values` field while keeping the original `values` field.

2. **Six-face accelerometer calibration** — gravity-based axis alignment, per-axis bias and
   scale factor. Add to `ImuCalibration.kt` as `sixFaceCalibration(faces: List<Window>)`.

3. **Allan Variance** — capture `event.timestamp` in `collectWindow()` and add an
   `AllanVariance` utility. The output populates `ImuNoiseModel` and lets `FormatConverters`
   write real noise parameters to `mav0/imu0/sensor.yaml` instead of zeros.

4. **Camera-to-IMU extrinsics** — a guided procedure using Kalibr or an equivalent tool.
   The data collection pipeline (synchronized camera + IMU) already works.

## Opening an issue first

For anything beyond a small bugfix, open an issue first to discuss scope. Research-oriented
changes (VIO integration, new calibration algorithms, new export formats) especially benefit
from a brief discussion before code is written.

## License

By contributing you agree that your contribution is licensed under the same
[PolyForm Noncommercial 1.0.0](LICENSE) terms as the rest of the project.
