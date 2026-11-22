# ADR 0003 — Idempotência por `message_id` e Dead Letter Topic

**Status:** aceito

## Contexto
Duplicados são inevitáveis: o device reenvia depois de um ack perdido, e o consumer reprocessa depois
de um rebalance. No dataset de referência, 0,79% das mensagens chegam duplicadas. Também há mensagens
que nunca vão processar (firmware com bug, GPS sem fix).

## Decisão
1. **Idempotência no destino:** `message_id` (UUID gerado no device) é a chave primária. A inserção usa `on conflict do nothing`, e duplicados viram a métrica `outcome=duplicate`.
   Não usamos exactly-once do Kafka: ele não cobre o trecho device → gateway, que é onde nascem os duplicados.
2. **Erros classificados:**
   - Permanentes (`InvalidSafetyEventException`, desserialização): DLT imediatamente, sem retry.
   - Transientes (banco, timeout): backoff exponencial (500ms → 5s, até 30s) e depois DLT.
3. **Heartbeats não usam DLT:** depois de alguns retries, são logados e descartados. O próximo heartbeat (em 5s) substitui o perdido, e travar a partição faria centenas de devices "caírem" de uma vez.

## Consequências
- ✅ Nenhum evento de segurança some em silêncio: ou está no banco, ou na DLT.
- ✅ Reprocessar a DLT é seguro, porque o processamento é idempotente.
- ⚠️ A DLT precisa de alerta e de dono, senão vira um cemitério ([runbook](../runbooks/dlt-growing.md)).
