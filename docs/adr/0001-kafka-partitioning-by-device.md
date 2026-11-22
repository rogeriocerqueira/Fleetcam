# ADR 0001 — Particionar os tópicos de dispositivo por `deviceId`

**Status:** aceito

## Contexto
A avaliação de saúde é stateful: detectar reboot ou loop depende da ordem dos heartbeats de um mesmo
device. Com processamento fora de ordem, um heartbeat antigo poderia "desfazer" um estado novo.

## Decisão
Todas as mensagens são publicadas com `key = deviceId`. O Kafka garante ordem por partição, então as
mensagens de um device são consumidas em ordem e por um único consumer do grupo.

## Consequências
- ✅ Ordem por device sem locks distribuídos. O `SELECT ... FOR UPDATE` só protege contra o sweeper de offline.
- ✅ Escala horizontal até o número de partições (6 localmente).
- ⚠️ "Hot partition": um device muito ruidoso atrasa os outros da mesma partição. Aceitável, porque o volume por device é limitado pelo firmware.
- ⚠️ A ordem no Kafka é a ordem de **chegada**, não a de produção. Backlog despejado após reconexão chega depois de mensagens mais novas. Por isso o avaliador também descarta heartbeats com `deviceTime` anterior ao último processado.
- ⚠️ Aumentar partições depois muda o mapeamento key → partição. Exige drenar os consumers.
