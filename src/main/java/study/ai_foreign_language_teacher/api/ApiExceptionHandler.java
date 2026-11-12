package study.ai_foreign_language_teacher.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import study.ai_foreign_language_teacher.api.Dto.ErrorView;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.llm.LlmException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorView notFound(NotFoundException e) {
        return new ErrorView(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorView badRequest(IllegalArgumentException e) {
        return new ErrorView(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorView unreadable(HttpMessageNotReadableException e) {
        return new ErrorView("Request body is not valid JSON");
    }

    @ExceptionHandler(LlmException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorView llm(LlmException e) {
        return new ErrorView(e.getMessage());
    }
}
