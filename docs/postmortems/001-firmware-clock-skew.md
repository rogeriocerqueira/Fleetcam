# Postmortem 001 — Alarme de SLO de latência disparado por relógio errado no firmware 2.3.1

| | |
|---|---|
| **Status** | Resolvido (mitigação no backend) · correção definitiva aguardando fornecedor |
| **Severidade** | SEV-2: dado exibido errado para clientes, sem perda de dado |
| **Detecção** | Alerta `SafetyEventLatencyBudgetBurnFast`, 2026-11-18 ~13:15 UTC |
| **Duração do impacto** | 2026-11-18 13:00 UTC → deploy da correção de horário no backend |
| **Autor** | _seu nome_ |

> **Incidente simulado** para fins de portfólio: o cenário, o fornecedor e a bancada são fictícios. Os números abaixo são reproduzíveis: `make investigate` gera o dataset sintético e roda as queries
> de [`analytics/investigation.sql`](../../analytics/investigation.sql). O CI confere essas conclusões a cada push.

## Resumo

Às 13:00 UTC do dia 18/11 o firmware **2.3.1** foi liberado por OTA para cerca de 25% da frota. Logo
depois, o alerta de queima de error budget do SLO "99% dos eventos de segurança visíveis em até 30s"
disparou: a conformidade caiu de **99,7% para cerca de 74%**.

**Não era latência.** O firmware 2.3.1 envia o horário **local (UTC-3) marcado como se fosse UTC**, ou
seja, o relógio dessas câmeras fica 3h atrasado. A fórmula de latência subtraía um timestamp do
dispositivo de um timestamp do servidor, então todo evento dessas câmeras parecia ter levado 3 horas
para chegar.

O impacto real para o cliente era outro e pior: **9.171 eventos de 310 câmeras, de todos os 40
clientes, apareceram no painel com horário 3h errado.** Para um gestor de frota, isso significa um
evento de "celular ao volante" atribuído ao motorista do turno anterior.

## Linha do tempo (UTC)

| Hora | Evento |
|---|---|
| 13:00 | Rollout OTA do firmware 2.3.1 (25% da frota) pelo time de firmware |
| 13:15 | `SafetyEventLatencyBudgetBurnFast` dispara (janelas de 5m e 1h acima de 14,4x) |
| 13:20 | Primeira hipótese: degradação do `safety-event-processor`. Descartada: `processed_at - received_at` p50 = 0,55s, igual ao baseline |
| 13:35 | Quebra por firmware (q2): **2.2.0 em 99,5%, 2.3.1 em 0%**. O problema está isolado num firmware |
| 13:45 | Segunda hipótese: câmeras com 2.3.1 acumulando backlog (conectividade). Descartada pela decomposição (q3): buffer p50 de 4,6s, igual ao 2.2.0 |
| 13:50 | Histograma do offset (q4): **100% dos eventos do 2.3.1 com offset de exatamente 180 min**. Um valor redondo e constante indica bug de relógio ou fuso, não rede |
| 14:10 | Suporte avisado com texto pronto ([comunicado](../communication/001-support-notice.md)). Fornecedor acionado ([relatório](../communication/001-vendor-report.md)) |
| 16:30 | Deploy da mitigação: correção de horário no backend + nova fórmula de latência |

## Investigação

### 1. A queda começa exatamente no rollout

```
hour_utc     events  pct_within_30s_naive
12:00        1005    99.40
13:00        1002    74.85   <- rollout
14:00         988    73.28
```

A queda é um degrau, não uma rampa. Degradação de infraestrutura costuma ser gradual; um degrau
alinhado com um deploy aponta para o deploy.

### 2. Só um firmware é afetado

```
firmware  events  pct_within_30s_naive  p50_naive_latency_min
2.2.0     26125   99.48                 0.1
2.3.1      9171    0.00                 180.1
```

Se o backend estivesse lento, os dois firmwares seriam afetados igualmente.

### 3. Decompondo a latência em partes que usam o mesmo relógio

| firmware | buffer no device (device-device) | servidor (servidor-servidor) | offset entre relógios |
|---|---|---|---|
| 2.2.0 | 4,5 s | 0,55 s | 1,1 s (≈ tempo de rede) |
| 2.3.1 | 4,6 s | 0,55 s | **10.801 s (≈ 3h)** |

O device não está segurando eventos e o servidor não está lento. Toda a "latência" está no offset
entre relógios, que deveria ser só o tempo de rede.

### 4. Um valor constante e redondo

`round(offset_min)` = **180 para 100% dos eventos do 2.3.1.** Problema de rede produziria uma
distribuição espalhada. Um valor fixo igual ao fuso de Brasília (UTC-3) indica que o firmware está
lendo o RTC em horário local e serializando como UTC.

### 5. Reprodução em bancada

Uma câmera com 2.3.1 na bancada, sincronizada via NTP, com o tráfego capturado: `device_time` saiu como
`10:00:00Z` às 13:00:00Z reais. Reproduzido também no simulador (`FAULTS=firmware_clock_skew:10`).

## Causa raiz

1. **Firmware 2.3.1** (fornecedor) passou a gerar timestamps a partir do relógio local sem converter para UTC.
2. **Backend** confiava no `device_time` para exibir o horário do evento e calculava latência
   misturando relógios (`processed_at - device_time`).
3. **Processo:** o rollout OTA foi direto para 25% da frota, sem canário com validação de telemetria.

## O que funcionou

- O SLO com alerta de burn-rate detectou o problema em cerca de 15 min.
- Os três timestamps em cada mensagem (`device_time`, `device_sent_at`, `received_at`) permitiram
  provar a causa com dado, sem precisar de acesso ao device.
- A idempotência por `message_id` absorveu os duplicados normais (0,79%), que não atrapalharam a análise.

## O que não funcionou

- A métrica de latência estava errada por construção: ela teria mascarado também um atraso real.
- Nada no pipeline detectava relógio errado. Descobrimos pelo efeito colateral, não pela causa.

## Ações

| Ação | Tipo | Status |
|---|---|---|
| Latência e2e calculada só com diferenças do mesmo relógio (`ClockMath.endToEndLatency`) | Correção | ✅ |
| `event_time` exibido ao cliente corrigido pelo offset estimado quando \|offset\| > 5 min (`ClockMath.correctedEventTime`) | Mitigação | ✅ |
| Regra de saúde `CLOCK_SKEW` por device e alerta `DeviceClockSkewByFirmware` | Detecção | ✅ |
| Métrica `fleetcam.ingestion.clock.offset` com tag `firmware` no dashboard | Detecção | ✅ |
| Corrigir conversão de fuso no firmware (fornecedor), com reteste em bancada antes do release | Correção | ⏳ fornecedor |
| Rollout OTA em canário (1% → 10% → 100%) com gate automático nas métricas acima | Prevenção | 📋 backlog |
| Backfill: recalcular `event_time` dos 9.171 eventos já gravados (`update ... set event_time = device_time + (received_at - device_sent_at)`) | Reparo | 📋 backlog |

## Lições

- **Nunca subtraia timestamps de relógios diferentes.** Dispositivo em campo não é fonte confiável de tempo.
- Um SLO "vermelho" diz que algo mudou, não o quê. A hipótese mais óbvia ("o backend está lento") foi a primeira a cair.
