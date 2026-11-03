package io.archsim.app.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The servlet container's error page, which answers errors that happen outside Spring MVC
 * (a filter, the container itself) with Starlette's body instead of Spring Boot's. Asked
 * for directly, {@code /error} is a route like any other that does not exist.
 */
@RestController
class ApiErrorController implements ErrorController {

	@RequestMapping("${server.error.path:/error}")
	ResponseEntity<Object> error(HttpServletRequest request) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		HttpStatusCode code = (status instanceof Integer value) ? HttpStatusCode.valueOf(value) : HttpStatus.NOT_FOUND;
		return StatusAnswers.of(code, HttpHeaders.EMPTY);
	}

}
