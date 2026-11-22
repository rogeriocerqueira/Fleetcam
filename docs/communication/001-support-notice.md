# Comunicado para Suporte/CS — horário errado em eventos de algumas câmeras

**Enviado em:** 18/11, 14:10 (horário de Brasília: 11:10)

**O que o cliente pode estar vendo**
Alguns eventos de segurança (sonolência, celular ao volante, frenagem brusca etc.) aparecem no painel
**3 horas antes** do horário real. Exemplo: um evento que aconteceu às 15h aparece às 12h.

**Quem foi afetado**
Câmeras que receberam a atualização de firmware 2.3.1 a partir de hoje, 10h (Brasília). Cerca de 1 em
cada 4 câmeras, distribuídas entre todos os clientes.

**O que NÃO foi afetado**
- Nenhum vídeo ou evento foi perdido.
- A detecção de eventos continua funcionando normalmente. Só o horário exibido está errado.

**O que estamos fazendo**
Hoje: correção no nosso sistema para exibir o horário certo, inclusive nos eventos já registrados.
Previsão: até 18h. Em paralelo, o fabricante está corrigindo o firmware.

**Como responder ao cliente**
> "Identificamos um problema no horário exibido de alguns eventos após uma atualização das câmeras.
> Nenhum evento ou vídeo foi perdido, e a correção está sendo aplicada hoje. Os eventos passados
> também terão o horário corrigido automaticamente."

**Como confirmar se uma câmera específica foi afetada**
No GraphiQL interno, consulte o device e veja se há um problema aberto do tipo `CLOCK_SKEW`:
`{ device(id: "cam-0123") { firmwareVersion openIssues { type detail } } }`

Dúvidas: canal #iot-video
