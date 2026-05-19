package com.github.liliumsoftware.optimization.exception;

import com.github.liliumsoftware.optimization.routing.RoutingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exception types are public API: a host application catches them and surfaces their message and cause to
 * users, so both must survive construction. All three are unchecked by design — planning failures abort a run
 * rather than being part of every signature.
 */
class ExceptionContractTest {

    @Test
    void planningExceptionCarriesMessageAndCause() {
        Throwable cause = new IllegalStateException("root");

        assertThat(new PlanningException("failed")).hasMessage("failed").hasNoCause();
        assertThat(new PlanningException(cause)).hasCause(cause);
        assertThat(new PlanningException("failed", cause)).hasMessage("failed").hasCause(cause);
        assertThat(new PlanningException("failed")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancelExceptionIsAnUncheckedAbortSignal() {
        assertThat(new CancelException("Algorithm cancelled"))
                .hasMessage("Algorithm cancelled")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void routingExceptionCarriesMessageAndCause() {
        Throwable cause = new java.io.IOException("connection reset");

        assertThat(new RoutingException("boom")).hasMessage("boom").hasNoCause();
        assertThat(new RoutingException("boom", cause)).hasMessage("boom").hasCause(cause);
    }

    @Test
    void objectErrorBindsAMessageToTheOffendingObject() {
        ObjectError error = new ObjectError("capacity.exceeded", "vehicle-1");

        assertThat(error.defaultMessage()).isEqualTo("capacity.exceeded");
        assertThat(error.objectName()).isEqualTo("vehicle-1");
        assertThat(error).isEqualTo(new ObjectError("capacity.exceeded", "vehicle-1"))
                .hasSameHashCodeAs(new ObjectError("capacity.exceeded", "vehicle-1"));
        assertThat(error).isNotEqualTo(new ObjectError("capacity.exceeded", "vehicle-2"));
    }

}
