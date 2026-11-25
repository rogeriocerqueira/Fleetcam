# Uso de IA neste projeto

Registro de como uso IA no desenvolvimento: o que delego, como verifico e onde ela errou. A ideia é
tratar IA como ferramenta de engenharia com processo, não como piloto automático.

## Princípios
1. **Toda saída de IA é rascunho.** Só entra no repositório depois de eu ler, entender e conseguir explicar.
2. **Verificação objetiva:** código só com testes passando; SQL só com resultado conferido no dado; afirmações em doc só com fonte ou query que as reproduza.
3. **Contexto explícito no prompt:** contrato (`.proto`), invariantes ("nunca misturar relógios") e restrições (cardinalidade de métricas).

## Registro

| Data | Tarefa | Como a IA ajudou | Como verifiquei | O que precisou de correção |
|---|---|---|---|---|
| 2026-11-22 | Esqueleto inicial do monorepo (Gradle, serviços, docs) | Gerou a estrutura e a primeira versão do código e da documentação com o Claude, sem conseguir compilar no ambiente dele | CI no GitHub e `./gradlew build` / `test` local | O código nunca tinha sido compilado: o CI falhou no primeiro push (ver as linhas abaixo) |
| 2026-11 | Regra de reboot loop | Sugeriu `uptime < uptime_anterior` | Teste com loop rápido (500s → 20s → 20s) | A regra simples deixava passar reboots consecutivos; trocada por "uptime cresceu menos que o tempo decorrido" |
| 2026-11 | Queries da investigação | Rascunho das queries de decomposição de latência | `analytics/investigate.py --check` confere os números citados no postmortem | Baseline do dataset violava o próprio SLO (5% de backlog); ajustado para 0,4% |
| 2026-11-23 | Primeiro build | — | `./gradlew compileTestKotlin` local | Erro de compilação: literal `0` (Int) onde a API esperava `Long` no resumo do GraphQL |
| 2026-11-23 | Classes de configuração (`@ConfigurationProperties`) | Usou `@ConstructorBinding` em data classes Kotlin com todos os parâmetros com valor padrão | Teste de integração do safety (Testcontainers) | O Kotlin gera um construtor vazio e copia a anotação para ele; o Spring recusa e **nenhum dos 3 serviços subiria**. Removida a anotação: o Boot já usa o construtor primário |
| 2026-11-23 | Produtor da DLT | Criou um `KafkaTemplate` próprio a partir do `KafkaProperties` | Mesmo teste de integração | Dois bugs: (1) serializer configurado no código e no yml ao mesmo tempo, recusado pelo Spring Kafka; (2) o endereço do Kafka vinha do yml e ignorava o do Testcontainers, então o produtor falava com outro broker. Passou a reaproveitar a fábrica de produtores do Boot |
| 2026-11-23 | Teste de integração do safety | Contou com a criação automática de tópicos | Mesmo teste | O Kafka do Testcontainers não cria tópicos sozinho; o teste passou a declarar o tópico de origem |
| 2026-11-24 | Testes do gateway gRPC | Escreveu testes em memória (sem Docker) para códigos de status e publicação no Kafka | `./gradlew :services:ingestion-service:test` | _preencher após rodar_ |
| | _próximas entradas_ | | | |

### Lição desta rodada
Os testes unitários passaram desde o início porque testam regras isoladas. Os três bugs graves só
apareceram quando o Spring subiu de verdade com Kafka e Postgres reais. Código gerado por IA que nunca
rodou precisa de pelo menos um teste que suba a aplicação inteira.

## Prompts que funcionaram bem
- **Investigação:** "Aqui está o schema e o sintoma. Liste hipóteses ordenadas por probabilidade e, para cada uma, a query que a confirmaria ou descartaria." Isso força pensar em descartar hipóteses, não só em confirmar.
- **Code review:** "Revise este consumer Kafka procurando: perda de mensagem, duplicação, partição travada e cardinalidade de métricas."
