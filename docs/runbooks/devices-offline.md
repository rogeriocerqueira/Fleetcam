# Runbook: dispositivos offline

**Alertas:** `FleetOfflineRatioHigh` (>10% da frota), `HeartbeatConsumerLagHigh`
**Regra:** um device fica `OFFLINE` quando passa `offline-after` (5 min) sem heartbeat, medido pelo **relógio do servidor**.

## Primeiro: é o device ou somos nós?
Muitos devices "caindo" ao mesmo tempo quase nunca é coincidência.

| Sinal | Provável causa |
|---|---|
| Lag do `device-health-service` alto | **Nós.** O consumer está atrasado e os heartbeats existem, mas não foram processados. Não acione o Suporte |
| `fleetcam_ingestion_messages_total` caiu para todos | **Nós.** Gateway, rede ou DNS |
| Offline concentrado em um cliente ou região | Operadora ou garagem (veículos desligados) |
| Offline concentrado em um firmware | Firmware travando (cruze com `REBOOT_LOOP`) |
| Casos isolados, espalhados | Normal: veículo desligado, câmera desconectada |

```sql
-- Offline agora, por firmware
select firmware_version, count(*) from device where state = 'OFFLINE' group by 1 order by 2 desc;

-- Histórico de um device específico (pedido comum do Suporte)
select from_state, to_state, reason, at from device_state_transition
where device_id = 'cam-0042' order by at desc limit 20;
```

## Resposta para o Suporte (caso isolado)
"A câmera não envia sinal desde {último contato}. Peça ao cliente para verificar se o veículo foi
ligado e se o cabo de alimentação da câmera está conectado. Se o veículo está rodando e a câmera
continua offline após 30 min, abrir chamado técnico."
