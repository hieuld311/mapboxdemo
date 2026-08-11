package com.fauto.car.navigation;

import fauto.car.navigation.FAutoCarNavigationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** PENDING, RUNNING, COMPLETED/CANCELLED state machine backing a blocking Binder command. */
final class CommandRequest {
    private enum State {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    final String stringValue;
    private final CountDownLatch completed = new CountDownLatch(1);
    volatile int resultCode = FAutoCarNavigationManager.ERROR_OPERATION_FAILED;
    private State state = State.PENDING;

    private CommandRequest(String stringValue) {
        this.stringValue = stringValue;
    }

    static CommandRequest forString(String value) {
        return new CommandRequest(value);
    }

    static CommandRequest empty() {
        return new CommandRequest(null);
    }

    synchronized boolean tryStart() {
        if (state != State.PENDING) {
            return false;
        }
        state = State.RUNNING;
        return true;
    }

    synchronized boolean cancelIfPending() {
        if (state != State.PENDING) {
            return false;
        }
        state = State.CANCELLED;
        return true;
    }

    void complete(int result) {
        if (finish(result)) {
            completed.countDown();
        }
    }

    void fail(int result) {
        if (finish(result)) {
            completed.countDown();
        }
    }

    private synchronized boolean finish(int result) {
        if (state == State.COMPLETED || state == State.CANCELLED) {
            return false;
        }
        resultCode = result;
        state = State.COMPLETED;
        return true;
    }

    boolean await(long timeoutMillis) throws InterruptedException {
        return completed.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    void awaitCompletion() throws InterruptedException {
        completed.await();
    }
}
