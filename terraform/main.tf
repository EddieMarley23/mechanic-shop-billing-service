terraform {
  required_version = ">= 1.10.0"
  backend "s3" {}
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags { tags = { Project = "mechanic-shop", Owner = "billing-service", Environment = "academy" } }
}
variable "aws_region" {
  type    = string
  default = "us-east-1"
  validation {
    condition     = contains(["us-east-1", "us-west-2"], var.aws_region)
    error_message = "Learner Lab permits us-east-1 or us-west-2 only."
  }
}
variable "cluster_name" {
  type    = string
  default = "mechanic-shop-academy"
}
variable "db_username" {
  type    = string
  default = "billingadmin"
}
variable "db_password" {
  type      = string
  sensitive = true
  validation {
    condition     = length(var.db_password) >= 8
    error_message = "RDS requires a database password of at least 8 characters."
  }
}

# Data sources do not create or claim any shared resources.
data "aws_eks_cluster" "shared" { name = var.cluster_name }
data "aws_vpc" "shared" { id = data.aws_eks_cluster.shared.vpc_config[0].vpc_id }
data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.shared.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
  filter {
    name   = "availability-zone"
    values = [for suffix in ["a", "b", "c"] : "${var.aws_region}${suffix}"]
  }
}

resource "aws_security_group" "db" {
  name_prefix = "mechanic-shop-billing-db-"
  description = "Billing PostgreSQL reachable only from within the lab VPC"
  vpc_id      = data.aws_vpc.shared.id
  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.shared.cidr_block]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
resource "aws_db_subnet_group" "billing" {
  name       = "mechanic-shop-billing-db"
  subnet_ids = data.aws_subnets.public.ids
}
resource "aws_db_instance" "billing" {
  identifier                 = "mechanic-shop-billing"
  engine                     = "postgres"
  instance_class             = "db.t3.micro"
  allocated_storage          = 20
  storage_type               = "gp2"
  db_name                    = "billingdb"
  username                   = var.db_username
  password                   = var.db_password
  db_subnet_group_name       = aws_db_subnet_group.billing.name
  vpc_security_group_ids     = [aws_security_group.db.id]
  publicly_accessible        = false
  multi_az                   = false
  monitoring_interval        = 0
  skip_final_snapshot        = true
  deletion_protection        = false
  auto_minor_version_upgrade = true
}
output "jdbc_url" { value = "jdbc:postgresql://${aws_db_instance.billing.address}:5432/billingdb" }
output "db_username" { value = var.db_username }
