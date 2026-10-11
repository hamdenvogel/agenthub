package br.com.hvogel.agenthub.api;

import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.exception.ConsentRequiredException;
import br.com.hvogel.agenthub.exception.InvalidTargetException;
import br.com.hvogel.agenthub.exception.RemediationConflictException;
import br.com.hvogel.agenthub.exception.RemediationException;
import br.com.hvogel.agenthub.exception.RemediationNotFoundException;
import br.com.hvogel.agenthub.exception.ReportNotReadyException;
import br.com.hvogel.agenthub.exception.TargetBusyException;
import br.com.hvogel.agenthub.exception.TargetNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ProblemDetail notFound(AnalysisNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ReportNotReadyException.class)
    public ProblemDetail notReady(ReportNotReadyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TargetNotFoundException.class)
    public ProblemDetail targetNotFound(TargetNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidTargetException.class)
    public ProblemDetail invalidTarget(InvalidTargetException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TargetBusyException.class)
    public ProblemDetail targetBusy(TargetBusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RemediationNotFoundException.class)
    public ProblemDetail remediationNotFound(RemediationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RemediationException.class)
    public ProblemDetail remediationInvalid(RemediationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RemediationConflictException.class)
    public ProblemDetail remediationConflict(RemediationConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ConsentRequiredException.class)
    public ProblemDetail consentRequired(ConsentRequiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(ClaudeException.class)
    public ProblemDetail claudeFailed(ClaudeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail illegalState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
