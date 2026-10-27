# Recording crash analysis

The reported five-second physical-device crash was not reproduced: no affected device, screen recorder, or `adb` executable was available.

Two concrete failure mechanisms were present:

1. `SensorEvent.values` escaped its callback and was read asynchronously even though Android owns and reuses that array.
2. Every sensor callback created a coroutine, allowing an unbounded backlog under Room/ML contention.

The implementation now snapshots all scalar fields in the callback and uses one bounded ingestion worker. Capacity is 512; overflow drops the newest incoming sample, increments a counter, and emits structured diagnostics at the first and every hundredth drop. Epoch checks prevent stale samples crossing pause/stop/start boundaries. Lifecycle commands use one actor and invalid repeated transitions are no-ops.

Status: `IMPLEMENTED_NOT_DEVICE_VERIFIED`. The original incident must not be called resolved until repeated physical testing with the affected screen-recording setup passes.
