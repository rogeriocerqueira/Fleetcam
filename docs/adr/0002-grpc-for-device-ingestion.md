# ADR 0002 — gRPC com batches para ingestão de dispositivos

**Status:** aceito

## Contexto
As câmeras usam rede móvel (3G/LTE), muitas vezes com sinal ruim e plano de dados limitado. Ficam
offline com frequência e precisam despejar backlog ao reconectar.

## Opções consideradas
| Opção | Prós | Contras |
|---|---|---|
| REST + JSON | simples, fácil de debugar | payload 3 a 5x maior, sem contrato tipado |
| MQTT | feito para IoT, leve, QoS | exige broker dedicado; mais uma peça para operar neste projeto |
| **gRPC + Protobuf (unário, batch)** | payload binário compacto, contrato versionado em `.proto`, codegen Kotlin | debug exige ferramentas (`grpcurl`); HTTP/2 em redes com proxy |

## Decisão
gRPC unário com `TelemetryBatch`: o device envia até N mensagens por chamada, e o servidor só responde
depois do ack do Kafka (`acks=all`).

- **Batch** amortiza o custo de conexão e dá suporte natural ao backlog.
- **Rejeição por item**: uma mensagem inválida não bloqueia o batch inteiro (`rejected_message_ids`).
- **Códigos de status** guiam o retry do device: `INVALID_ARGUMENT` descarta, `UNAVAILABLE` tenta de novo.

## Consequências
- Entrega *at-least-once*: um ack perdido gera reenvio. Os consumers são idempotentes por `message_id` ([ADR 0003](0003-idempotency-and-dlt.md)).
- O `.proto` é o contrato com o fornecedor de firmware. Mudanças só podem adicionar campos (compatibilidade proto3).
- Em produção: mTLS com certificado por device, que também autentica o `device_id`.
- Streaming bidirecional ficou para depois. Seria útil para comandos servidor → device (ex.: "envie o clipe X").
