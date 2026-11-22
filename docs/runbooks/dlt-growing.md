# Runbook: eventos de segurança indo para a DLT

**Alerta:** `SafetyEventsDeadLettered`
**Tópico:** `device.safety-events.v1.DLT`

## Por que importa
Todo evento na DLT é um evento que o cliente **não está vendo**. Ele não foi perdido: está guardado
para inspeção e reprocessamento.

## Diagnóstico
1. Logs do `safety-event-processor`: `event=dead_lettered ... cause="..."`.
2. Abra uma mensagem no Kafka UI (http://localhost:8090). Os headers `kafka_dlt-exception-message`
   e `kafka_dlt-original-offset` mostram o motivo e a origem.
3. Classifique:
   | Causa | Exemplo | Ação |
   |---|---|---|
   | Validação | `GPS without fix (0,0)`, `confidence out of range` | Bug de firmware ou de modelo. Agrupe por `firmwareVersion` e acione o fornecedor |
   | Desserialização | payload truncado ou formato novo | Mudança de contrato não coordenada. Verifique deploys recentes do ingestion |
   | Transiente esgotado | timeout de banco após retries | Problema de infra. Corrija e **reprocesse** |

## Reprocessamento
Depois de corrigir a causa, republique as mensagens da DLT no tópico original. O processamento é
idempotente por `message_id`, então reprocessar duas vezes não duplica nada.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic device.safety-events.v1.DLT --from-beginning --property print.key=true --timeout-ms 5000
```
