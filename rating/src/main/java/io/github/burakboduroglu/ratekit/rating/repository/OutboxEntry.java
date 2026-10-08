package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.ChargeEvent;

/** An unsent outbox row: its id, to mark it sent, and the charge it stands for. */
public record OutboxEntry(long id, ChargeEvent charge) {
}
