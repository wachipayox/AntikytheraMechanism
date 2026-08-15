package dev.antikytheramechanism.interaction;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-thread feedback journal for one BucketItem#use call routed across a mini/macro boundary. */
public final class BucketRouteFeedbackContext {
    private static final ThreadLocal<Deque<State>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private BucketRouteFeedbackContext() {
    }

    public static void enter() {
        STACK.get().push(new State());
    }

    public static void exit() {
        Deque<State> states = STACK.get();
        if (states.isEmpty()) {
            throw new IllegalStateException("No bucket route feedback context is active");
        }
        states.pop();
        if (states.isEmpty()) {
            STACK.remove();
        }
    }

    public static void markClientOutwardPrediction() {
        State state = current();
        if (state != null) {
            state.clientOutwardPrediction = true;
        }
    }

    public static void markServerAuthoritativeMacroPlacement() {
        State state = current();
        if (state != null) {
            state.serverAuthoritativeMacroPlacement = true;
        }
    }

    public static boolean clientOutwardPrediction() {
        State state = current();
        return state != null && state.clientOutwardPrediction;
    }

    public static boolean serverAuthoritativeMacroPlacement() {
        State state = current();
        return state != null && state.serverAuthoritativeMacroPlacement;
    }

    private static State current() {
        Deque<State> states = STACK.get();
        return states.isEmpty() ? null : states.peek();
    }

    private static final class State {
        private boolean clientOutwardPrediction;
        private boolean serverAuthoritativeMacroPlacement;
    }
}
