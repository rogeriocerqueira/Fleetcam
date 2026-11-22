# ADR 0004 — Três timestamps por mensagem; nunca misturar relógios

**Status:** aceito (escrito após o [postmortem 001](../postmortems/001-firmware-clock-skew.md))

## Contexto
O relógio de um dispositivo em campo não é confiável: RTC sem bateria, NTP sem rede, bugs de fuso no
firmware. Já o relógio do servidor é confiável, mas não sabe quando o evento aconteceu.

## Decisão
Toda mensagem carrega:

| Campo | Relógio | Uso |
|---|---|---|
| `device_time` | device | quando aconteceu (pode estar errado) |
| `device_sent_at` | device | quando o batch saiu do device |
| `received_at` | servidor | quando o gateway recebeu |

Regras (centralizadas em `ClockMath`, no módulo `contracts`):
- Só subtrair timestamps **do mesmo relógio**.
- `offset ≈ received_at − device_sent_at` estima o erro do relógio do device, independentemente de quanto tempo a mensagem ficou em buffer.
- Detecção de offline usa só `received_at`.
- O horário exibido ao cliente é corrigido pelo offset quando |offset| > 5 min.

## Consequências
- ✅ Latência, offline e horário exibido ficam corretos mesmo com relógio errado.
- ✅ Relógio errado vira um problema detectável (`CLOCK_SKEW`) em vez de um efeito colateral.
- ⚠️ A correção assume offset constante entre a produção e o envio. Se o NTP corrigir o relógio no meio do backlog, os eventos daquele batch podem ficar com erro.
