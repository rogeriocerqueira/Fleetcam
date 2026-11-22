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
| 2026-11 | Esqueleto inicial do monorepo (Gradle, serviços, docs) | Gerou a estrutura e a primeira versão do código e da documentação com o Claude | Li arquivo por arquivo, `./gradlew build` local e no CI, rodei o simulador contra o ambiente | _preencher_ |
| 2026-11 | Regra de reboot loop | Sugeriu `uptime < uptime_anterior` | Teste com loop rápido (500s → 20s → 20s) | A regra simples deixava passar reboots consecutivos; trocada por "uptime cresceu menos que o tempo decorrido" |
| 2026-11 | Queries da investigação | Rascunho das queries de decomposição de latência | `analytics/investigate.py --check` confere os números citados no postmortem | Baseline do dataset violava o próprio SLO (5% de backlog); ajustado para 0,4% |
| | _próximas entradas_ | | | |

## Prompts que funcionaram bem
- **Investigação:** "Aqui está o schema e o sintoma. Liste hipóteses ordenadas por probabilidade e, para cada uma, a query que a confirmaria ou descartaria." Isso força pensar em descartar hipóteses, não só em confirmar.
- **Code review:** "Revise este consumer Kafka procurando: perda de mensagem, duplicação, partição travada e cardinalidade de métricas."
