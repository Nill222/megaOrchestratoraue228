# Этап 9.3 — SLA и критерии производительности

Документ фиксирует, что считается "нормально работает" в числах для текущего интеграционного контура.
Целевая платформа приемки: Windows-стенд.

---

## 1) Область SLA

SLA применяется к контуру:
- `camera-worker` (capture + shared memory + embedded detector),
- оркестратор (lifecycle/restart/fan-out),
- python/geometry сервисы,
- каналы fan-out (robot/client) без взаимной блокировки.

---

## 2) Метрики и пороги (PASS/FAIL)

### A. Задержка кадра (latency)

Определение:
- `capture_latency_ms` — задержка захвата кадра в worker.
- `decision_latency_ms` — от команды `capture` до сформированного финального вердикта в оркестраторе.

Порог:
- `p50(capture_latency_ms) <= 40 ms`
- `p95(capture_latency_ms) <= 120 ms`
- `p99(capture_latency_ms) <= 200 ms`
- `p95(decision_latency_ms) <= 250 ms`

### B. Потери/дропы (drops)

Определение:
- `drop_rate = capture_dropped / capture_total`.

Порог:
- `drop_rate <= 0.5%` на прогоне >= 30 минут.
- Ни одного непрерывного окна 60 секунд с `drop_rate > 2%`.

### C. Таймаут-бюджет IPC

Определение:
- доля запросов IPC, завершившихся timeout (worker/python/geometry).

Порог:
- `ipc_timeout_rate <= 0.1%` от общего числа команд.
- Серийных timeout подряд не более `3` на один сервис.

### D. Рестарты и устойчивость lifecycle

Определение:
- restart, выполненный супервизором после сбоя процесса/IPC.

Порог:
- `max_restarts_per_service_per_hour <= 2`.
- `sum_restarts_all_services_per_hour <= 6`.
- После fault-инъекции сервис восстанавливается и возвращается в `health=ok` не позже чем за `10 s`.

### E. Изоляция критичных каналов (robot/client)

Определение:
- при искусственной задержке client-канала robot-канал не должен блокироваться.

Порог:
- при `client_artificial_delay_ms` до `2000 ms`:
  - `p95(robot_send_latency_ms) <= 100 ms`,
  - потери robot-сообщений = `0` в сценарии без сетевого отключения robot endpoint.

---

## 3) Методика измерений

Минимальный прогон для решения "годно/не годно":
- длительность: `30–60 минут`,
- профиль: 5 камер (или максимально близкий к целевому стенду),
- обязателен fault-блок из этапа 9.2 (kill/timeout/broken response),
- сбор логов из `logs/` по всем сервисам.

Источник метрик:
- worker metrics (`capture_total`, `capture_dropped`, latency, fps),
- логи оркестратора (restart counts, ошибки IPC),
- логи fan-out (robot/client queue/send показатели).

---

## 4) Правило решения "годно/не годно"

Система признается **ГОДНО**, если одновременно выполнены все условия:
- все пороги из раздела 2 соблюдены;
- fault-сценарии 9.2 завершены успешным восстановлением без ручного вмешательства;
- нет зависаний, ведущих к полной остановке контура.

Если нарушен хотя бы один порог, статус **НЕ ГОДНО**.

---

## 5) Шаблон итоговой фиксации (для отчета)

```text
Run ID:
Date/Time:
Duration:
Profile (cameras/fps/load):

capture latency: p50=, p95=, p99=
decision latency: p95=
drop rate=
ipc timeout rate=
restarts/hour: worker=, python=, geometry=, total=
robot latency p95=

Fault scenarios 9.2: PASS/FAIL
Final SLA decision: READY / NOT_READY
Comment:
```

---

## 6) Пересмотр SLA

SLA пересматривается при любом из событий:
- изменение архитектуры hot path (например этап 10, async pipeline),
- смена источника захвата/SDK,
- изменение производственного требования по такту линии.
