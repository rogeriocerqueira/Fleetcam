# SLOs

| SLI | Objetivo | Janela | Medido em |
|---|---|---|---|
| Eventos de segurança visíveis no painel em ≤ 30s após acontecerem | 99% | 28 dias | `fleetcam_safety_e2e_latency_seconds_bucket{le="30.0"}` |
| Eventos de segurança válidos armazenados (não perdidos) | 99,99% | 28 dias | `stored / (stored + dead_lettered)` |
| Heartbeats processados com lag < 1 min | 99,5% | 28 dias | `kafka_consumer_fetch_manager_records_lag_max` |

## Por que 30 segundos?
Gestores de frota acompanham alguns eventos ao vivo (sonolência, por exemplo) para ligar para o
motorista. Acima de cerca de 30s a ligação perde o sentido. O objetivo é 99%, e não 99,9%, porque parte
do atraso vem de cobertura de rede em rodovias, fora do nosso controle.

## Como a latência é medida
`latência = (device_sent_at − device_time) + (processed_at − received_at)`

Cada parcela usa um único relógio. A versão anterior (`processed_at − device_time`) misturava relógios
e gerou um falso alarme ([postmortem 001](postmortems/001-firmware-clock-skew.md)).

## Alertas (burn rate multi-janela)
| Alerta | Condição | Ação |
|---|---|---|
| Fast burn | erro > 14,4 × 1% em 1h **e** 5m | page |
| Slow burn | erro > 3 × 1% em 6h **e** 30m | ticket |

A janela curta faz o alerta parar logo depois que o problema acaba. A janela longa evita alarme por
picos de poucos minutos.
