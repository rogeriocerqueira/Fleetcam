package dev.fleetcam.simulator

/**
 * Field problems we can inject. Each one maps to a real class of incident on embedded
 * video devices, and to a section of docs/runbooks.
 */
enum class Fault(val description: String) {
    /** Write errors grow until the firmware flips the card to read-only (camera stops recording). */
    SD_CARD_DEGRADING("cartão SD acumulando erros de escrita até ficar somente leitura"),

    /** 30% of sends fail; 10% of successful sends "lose" the ack, so the device resends (duplicates). */
    FLAKY_CONNECTIVITY("rede instável: falhas de envio, backlog e acks perdidos"),

    /** Firmware 2.3.1 bug: device reports local time (UTC-3) as if it were UTC. */
    FIRMWARE_CLOCK_SKEW("firmware 2.3.1 com relógio 3h atrasado"),

    /** Device crashes ~3 minutes after every boot (bad power harness or firmware crash). */
    REBOOT_LOOP("reinícios em loop"),

    /** Stops sending entirely after a few minutes (vehicle parked with camera unplugged). */
    GOES_DARK("para de enviar dados"),

    /** Safety events with GPS (0,0) — invalid, must end up in the DLT. */
    BAD_GPS("eventos com GPS sem fix"),
}
