package com.revolutclone.web.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import com.revolutclone.web.correlation.CorrelationIdFilter;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static final class NotFoundException extends ApiException {
        NotFoundException() {
            super(HttpStatus.NOT_FOUND, URI.create("https://revolutclone.com/problems/not-found"),
                    "Not found", "The thing you asked for does not exist.");
        }
    }

    @Test
    void mapsAnApiExceptionToItsOwnTypeAndStatus() {
        ProblemDetail problem = handler.handleApiException(new NotFoundException());

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getType()).isEqualTo(URI.create("https://revolutclone.com/problems/not-found"));
        assertThat(problem.getTitle()).isEqualTo("Not found");
        assertThat(problem.getDetail()).isEqualTo("The thing you asked for does not exist.");
    }

    @Test
    void includesTheCorrelationIdWhenOneIsOnTheMdc() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");

        ProblemDetail problem = handler.handleApiException(new NotFoundException());

        assertThat(problem.getProperties()).containsEntry("correlationId", "corr-123");
    }

    @Test
    void omitsTheCorrelationIdWhenNoneIsOnTheMdc() {
        ProblemDetail problem = handler.handleApiException(new NotFoundException());

        assertThat(problem.getProperties()).isNullOrEmpty();
    }

    @Test
    void mapsValidationFailuresWithFieldErrors() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", String.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(ProblemType.VALIDATION_ERROR);
        assertThat((Object) problem.getProperties().get("fieldErrors"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("email", "must not be blank");
    }

    @Test
    void mapsUnexpectedExceptionsWithoutLeakingTheirMessage() {
        ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("db password leaked here"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(ProblemType.INTERNAL_ERROR);
        assertThat(problem.getDetail()).doesNotContain("db password");
    }

    @SuppressWarnings("unused")
    private void dummyTarget(String email) {
    }
}
