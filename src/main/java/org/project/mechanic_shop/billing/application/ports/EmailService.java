package org.project.mechanic_shop.billing.application.ports;

public interface EmailService {
	void sendEmail(String[] to, String subject, String body);
}
