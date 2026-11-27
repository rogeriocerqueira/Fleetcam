package dev.fleetcam.health.presentation

import dev.fleetcam.health.domain.IssueDetail
import java.util.Locale

/**
 * Support-facing text for an issue: plain Portuguese, says what the customer experiences.
 * Kept out of the domain on purpose, so health rules stay language-neutral.
 */
fun IssueDetail.toSupportText(): String = when (this) {
    IssueDetail.SdReadOnly ->
        "Cartão SD em modo somente leitura: a câmera NÃO está gravando vídeo. Trocar o cartão."

    is IssueDetail.SdWriteErrors ->
        "$count erros de escrita no cartão SD desde o último boot. Risco de perda de vídeos."

    is IssueDetail.SdLowSpace -> {
        val pct = String.format(Locale.ROOT, "%.1f", freePercent)
        "Apenas $pct% livre no cartão SD: a gravação em loop pode não estar liberando espaço."
    }

    is IssueDetail.RebootLoop ->
        "$reboots reinícios em até ${window.toMinutes()} min. Possível falha de alimentação ou firmware."

    is IssueDetail.ClockSkew -> {
        val direction = if (deviceBehind) "atrasado" else "adiantado"
        "Relógio do dispositivo $direction ~${offset.toMinutes()} min (firmware $firmware). " +
            "Eventos são exibidos com horário corrigido pelo servidor."
    }

    is IssueDetail.Offline ->
        "Sem heartbeat há ${silence.toMinutes()} min (último contato $lastSeenAt). " +
            "Verificar ignição/alimentação do veículo e cobertura de sinal na região."
}
