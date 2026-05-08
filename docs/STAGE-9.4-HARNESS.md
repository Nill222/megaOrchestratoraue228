# Этап 9.4 — Нагрузочный harness

Скрипт автоматического прогона: `scripts/load_harness.py`.

## Что делает

- запускает интеграционный контур `N` раз (worker + python + geometry + fan-out),
- поддерживает профили:
  - `normal`
  - `stress`
- собирает метрики:
  - latency до robot TCP (`p50/p95/p99/max`),
  - `capture_total`, `capture_dropped`, `drop_rate`,
  - `timeouts_detected`,
  - `restart_counts` (worker/python/geometry),
  - `non_zero_exit_runs`.

## Запуск

Из корня проекта:

```bash
python3 scripts/load_harness.py --profile normal
python3 scripts/load_harness.py --profile stress
```

Переопределение параметров:

```bash
python3 scripts/load_harness.py --profile normal --runs 10 --cameras 5 --worker-ipc-mode stdio
python3 scripts/load_harness.py --profile stress --runs 30 --cameras 5 --worker-ipc-mode named_pipe
```

Примечание:
- для локального прогона "до реального железа" default `stress` использует 3 камеры (более стабильный повторяемый baseline);
- на целевом стенде запускай с `--cameras 5`.

## Артефакты

Результаты пишутся в `logs/harness/`:

- `load-harness-<profile>-<timestamp>.json` — полный отчёт;
- `load-harness-<profile>-<timestamp>.csv` — построчно по каждому run;
- `load-harness-<timestamp>-run-<n>.log` — stdout/stderr конкретного run;
- `harness-config-<timestamp>.json` — зафиксированный конфиг прогона.

## Интерпретация

Решение `READY/NOT_READY` принимай по порогам из `docs/STAGE-9.3-SLA.md`.

В JSON-отчёте это поле рассчитывается автоматически:
- `summary.sla_decision`
- `summary.sla_fail_reasons`
