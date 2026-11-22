# Runbook: problemas de cartão SD

**Problemas detectados:** `SD_CARD_READ_ONLY` (crítico), `SD_CARD_WRITE_ERRORS`, `SD_CARD_LOW_SPACE`
**Alerta de frota:** `SdCardReadOnlySpike`

## O que cada um significa para o cliente
| Problema | Impacto |
|---|---|
| `SD_CARD_READ_ONLY` | **A câmera não está gravando.** Se acontecer um acidente, não haverá vídeo. Trocar o cartão com urgência |
| `SD_CARD_WRITE_ERRORS` | Cartão degradando. Vídeos podem estar corrompidos. Geralmente evolui para read-only |
| `SD_CARD_LOW_SPACE` | A gravação em loop deveria liberar espaço sozinha. Se não está liberando, é bug de firmware |

## Caso isolado vs. lote
```sql
-- Novos read-only nas últimas 24h, por firmware
select d.firmware_version, count(*)
from device_health_issue i join device d using (device_id)
where i.type = 'SD_CARD_READ_ONLY' and i.opened_at > now() - interval '24 hours'
group by 1;
```
- **Espalhado entre firmwares:** desgaste natural. Cartões têm ciclos de escrita limitados e vídeo contínuo os consome rápido.
- **Concentrado em um firmware:** suspeitar de bug (por exemplo, escrita excessiva de logs). Reproduzir em bancada com cartão novo e medir escrita.
- **Concentrado em um lote de instalação ou cliente:** possível lote de cartões defeituosos.

## Validação em bancada
1. Mesmo modelo de câmera e firmware, cartão novo do mesmo fornecedor.
2. Gravar continuamente por 24h, monitorando `write_errors_since_boot`.
3. Comparar com um cartão de outro lote para isolar a variável.
