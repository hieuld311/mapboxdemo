package com.fauto.car.navigation;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * Gives a Binder caller synchronous-call semantics for a command that must run on a single
 * serialized worker thread, without double-executing a command that has already started once
 * its result timeout elapses.
 */
final class BlockingCommandGateway {
    interface CommandExecutor {
        void execute(int messageWhat, CommandRequest request);
    }

    interface FailureReporter {
        void onCommandFailed(int resultCode, String message);
    }

    private BlockingCommandGateway() {
    }

    static int dispatch(
            Handler handler,
            int messageWhat,
            CommandRequest request,
            long timeoutMillis,
            int queueFailureResultCode,
            int timeoutResultCode,
            CommandExecutor executor,
            FailureReporter failureReporter) {
        if (handler == null) {
            failureReporter.onCommandFailed(queueFailureResultCode, "Event handler is null");
            return queueFailureResultCode;
        }
        if (Looper.myLooper() == handler.getLooper()) {
            executor.execute(messageWhat, request);
            return request.resultCode;
        }
        if (!handler.sendMessage(Message.obtain(handler, messageWhat, request))) {
            failureReporter.onCommandFailed(
                    queueFailureResultCode, "Navigation command could not be queued");
            return queueFailureResultCode;
        }
        try {
            if (!request.await(timeoutMillis)) {
                if (request.cancelIfPending()) {
                    handler.removeMessages(messageWhat, request);
                    failureReporter.onCommandFailed(
                            timeoutResultCode, "Navigation command timed out before execution");
                    return timeoutResultCode;
                }
                request.awaitCompletion();
            }
            return request.resultCode;
        } catch (InterruptedException error) {
            if (request.cancelIfPending()) {
                handler.removeMessages(messageWhat, request);
                Thread.currentThread().interrupt();
                failureReporter.onCommandFailed(
                        timeoutResultCode, "Navigation command was interrupted before execution");
                return timeoutResultCode;
            }

            // The command has already started, so wait for the actual app result instead of
            // returning an error while the command continues in the background.
            while (true) {
                try {
                    request.awaitCompletion();
                    break;
                } catch (InterruptedException ignored) {
                    // Keep waiting for the already-started command's actual result.
                }
            }
            Thread.currentThread().interrupt();
            return request.resultCode;
        }
    }
}
