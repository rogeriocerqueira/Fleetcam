# Runbook: latência de eventos de segurança acima do SLO

**Alertas:** `SafetyEventLatencyBudgetBurnFast` (page), `SafetyEventLatencyBudgetBurnSlow` (ticket)
**SLO:** 99% dos eventos visíveis em até 30s após acontecerem (janela de 28 dias). Ver [docs/slo.md](../slo.md).

## O que o cliente sente
Eventos (sonolência, celular, frenagem) demoram para aparecer no painel. Gestores que acompanham ao vivo
deixam de agir a tempo.

## Diagnóstico: separe as três partes da latência
A latência e2e é `buffer no device + tempo no servidor`. Descubra qual parte cresceu:

1. **Servidor**: no Grafana, painel "Resultado do processamento" e lag do consumer.
   - Lag do `safety-event-processor` subindo → consumer lento ou parado. Veja logs (`event=dead_lettered`, exceções de banco) e a saúde do Postgres.
2. **Buffer no device**: muitos devices reconectando ao mesmo tempo (queda de operadora, por exemplo).
   - Painel "Tamanho de batch p95" sobe, porque devices estão despejando backlog.
   - Isso é atraso real, mas não é bug do backend. Confirme por região/operadora com SQL:
     ```sql
     -- Postgres (fleet_safety)
     select date_trunc('minute', received_at) as minute, count(*),
            percentile_cont(0.95) within group (order by extract(epoch from device_sent_at - device_time)) as p95_buffer_s
     from safety_event where received_at > now() - interval '1 hour' group by 1 order by 1;
     ```
3. **Relógio**: se uma versão de firmware descola no painel "Desvio de relógio", veja o [postmortem 001](../postmortems/001-firmware-clock-skew.md) e o [runbook de clock skew](clock-skew.md).

## Mitigação
- Consumer lento: aumentar `concurrency` ou réplicas (limitado ao número de partições, 6).
- Banco: verificar locks e conexões (`pg_stat_activity`).
- Backlog de devices: não há o que mitigar no backend; comunique o Suporte se for regional.

## Escalonamento
#iot-video → on-call do time de plataforma se Kafka ou Postgres estiverem degradados.
