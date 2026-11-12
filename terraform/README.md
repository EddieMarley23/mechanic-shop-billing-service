# Billing infrastructure

The Billing stack creates only the private Billing PostgreSQL RDS instance (`db.t3.micro`, 20 GB gp2, single AZ), its subnet group and security group. It reads the OS-owned EKS/VPC; it does not create or update the cluster, network or namespace. Its application owns the `billing.commands` queue, dead-letter queue and bindings on the OS-owned RabbitMQ broker. Its Kubernetes manifests create only Billing configuration, secret, Service, Deployment and HPA.

First deploy OS successfully. The Billing pipeline checks `aws sts get-caller-identity`, requires the named cluster to be `ACTIVE`, verifies OS RabbitMQ/Kong in Kubernetes, initializes its **separate** `billing/terraform.tfstate` key in the S3 bucket created by OS, provisions RDS, and deploys Billing. The workflow obtains `DATABASE_URL` and `DATABASE_USERNAME` from Terraform; don't set those as GitHub secrets.

**GitHub Actions secrets:** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (temporary lab AWS Details); `DOCKERHUB_TOKEN`; `DATABASE_PASSWORD` (distinct Billing RDS password); `JWT_SECRET`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` (same values as OS). Optional: `NEW_RELIC_LICENSE_KEY`, `SONAR_TOKEN`, `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_WEBHOOK_SECRET`.

**GitHub Actions variables:** `DOCKERHUB_USERNAME`; optional `AWS_REGION` (`us-east-1` default), `EKS_CLUSTER_NAME` (`mechanic-shop-academy` default), `APP_BASE_URL` (otherwise Kong Load Balancer URL), `PAYMENT_PROVIDER` (`simulated` default, or `mercadopago`), `NEW_RELIC_APP_NAME`, `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, `SONAR_HOST_URL`.

For a manual plan with active lab credentials, from this repository:

```bash
export AWS_REGION=us-east-1 TF_VAR_aws_region=us-east-1
export TF_VAR_db_password='your-billing-rds-password'
bash scripts/terraform-init.sh
terraform -chdir=terraform plan
```

An existing `mechanic-shop-billing` RDS instance not tracked in this state is rejected. Inspect it before importing (`terraform -chdir=terraform import aws_db_instance.billing mechanic-shop-billing`) and review the ensuing plan. See the OS repository's `terraform/README.md` for initial lab setup and shared ownership.
