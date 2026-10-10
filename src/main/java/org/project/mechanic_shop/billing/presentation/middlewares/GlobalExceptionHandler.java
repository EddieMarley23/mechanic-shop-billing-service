package org.project.mechanic_shop.billing.presentation.middlewares;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.domain.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	public ErrorResponse handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
		List<ErrorResponse.ErrorField> errors = e
			.getFieldErrors()
			.stream()
			.map(fe -> new ErrorResponse.ErrorField(fe.getField(), fe.getDefaultMessage()))
			.toList();

		return new ErrorResponse(HttpStatus.UNPROCESSABLE_CONTENT.value(), "Validation error.", errors);
	}

	@ExceptionHandler(
		{
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class,
		}
	)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleBadRequest(Exception e) {
		return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Malformed request or invalid data type.");
	}

	@ExceptionHandler(EntityNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleEntityNotFoundException(EntityNotFoundException e) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND.value(), e.getMessage());
	}

	@ExceptionHandler(AccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleAccessDeniedException(AccessDeniedException e) {
		return ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Access denied.");
	}

	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleIllegalStateException(IllegalStateException e) {
		log.warn("Business rule violation: {}", e.getMessage());
		return ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage());
	}

	@ExceptionHandler(PaymentGatewayException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public ErrorResponse handlePaymentGatewayException(PaymentGatewayException e) {
		log.error("Payment gateway failure: {}", e.getMessage());
		return ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleException(Exception e) {
		log.error("Unexpected error", e);
		return ErrorResponse.of(
			HttpStatus.INTERNAL_SERVER_ERROR.value(),
			"Unexpected error: Contact the technical team."
		);
	}
}
