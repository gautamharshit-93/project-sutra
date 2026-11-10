package com.sutra.quotation.service.internal;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work once the current transaction has committed: calls to other services that must see this service's
 * committed data (e.g. the extraction service claiming a just-uploaded quotation), or that must not happen if the
 * change rolls back (attaching or deleting stored files). Outside a transaction it runs at once.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }
}
