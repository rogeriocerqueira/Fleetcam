# Runbook: relógio do dispositivo desviado

**Problema:** `CLOCK_SKEW` · **Alerta:** `DeviceClockSkewByFirmware`
**Histórico:** [postmortem 001](../postmortems/001-firmware-clock-skew.md)

## Como medimos
`offset = received_at (servidor) - device_sent_at (device)`. Usamos o momento de envio do **batch**, e
não o horário do evento, para que eventos que ficaram em buffer offline não pareçam desvio de relógio.

## Diagnóstico
- **Muitos devices, mesmo firmware, offset redondo (60, 120, 180 min):** bug de fuso horário no firmware. Acione o fornecedor com o template do [relatório 001](../communication/001-vendor-report.md).
- **Devices isolados, offset arbitrário:** NTP falhando ou RTC sem bateria (comum após o veículo ficar dias parado). Normalmente se corrige no próximo sync.
- **Offset crescendo devagar ao longo dos dias:** drift do RTC sem NTP.

## Impacto e mitigação
O backend corrige `event_time` automaticamente quando |offset| > 5 min (`fleetcam.safety.clock-correction-tolerance`).
O cliente vê o horário certo. A correção só é exata quando o offset é constante entre o evento e o envio.
