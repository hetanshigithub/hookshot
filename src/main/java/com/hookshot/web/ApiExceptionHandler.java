package com.hookshot.web;

import com.hookshot.delivery.InvalidTargetException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to RFC 9457 problem responses. Validation errors use Spring's built-in handling. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidTargetException.class)
    public ProblemDetail invalidTarget(InvalidTargetException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid webhook target");
        return problem;
    }
}
