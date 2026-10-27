# Smart Rule evidence

Фактичні моделі підтримують enabled, джерела `ACCELEROMETER_X/Y/Z`, `MOVEMENT_INTENSITY`, `ML_CLASS`, numeric thresholds, consecutive matches, cooldown і дії, включно з `VIBRATION_PULSE`.

Сценарій X > 10.0, 3 збіги, cooldown 5000 ms, vibration існує безпосередньо в `SmartRuleEngineTest` і `SmartRuleDaoTest`. Тести підтверджують trigger після третього унікального input, dedup однакового physical input, disarm до false-condition rearm і cooldown. Room instrumentation підтверджує CRUD правила та execution persistence.

`SmartRuleEventCoordinator.prepare()` атомарно створює execution і gated outbox intent. Після side effect `finalizeEvent()` зберігає outcome й відкриває payload для доставки. Restart переводить незавершену фізичну дію в `OUTCOME_UNKNOWN` без повтору. Це at-least-once cloud delivery з idempotent event ID, але не exactly-once фізична вібрація.

UI screenshot: `screenshots/09_automations.png`. State machine: `architecture/smart_rule_execution.mmd`.
