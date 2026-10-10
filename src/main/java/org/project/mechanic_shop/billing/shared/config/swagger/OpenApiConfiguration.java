package org.project.mechanic_shop.billing.shared.config.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
	info = @Info(
		title = "Mechanic Shop — Billing Service",
		version = "v1",
		description = """
			Budgets, customer approval and payments of the mechanic shop service orders.

			Budgets and payments are created by Saga commands sent by the OS Service; this API exposes
			the customer-facing actions (approve or reject a budget, pay) and the queries.

			Authenticate with the JWT issued by the OS Service (`POST /api/auth/login`) or by the
			CPF login (`POST /functions/cpf-login`).
			"""
	),
	security = { @SecurityRequirement(name = "bearerAuth") }
)
@SecurityScheme(
	name = "bearerAuth",
	type = SecuritySchemeType.HTTP,
	scheme = "bearer",
	bearerFormat = "JWT",
	in = SecuritySchemeIn.HEADER
)
public class OpenApiConfiguration {}
