# AWS reference deployment

The reference topology uses an internet-facing Application Load Balancer, one ECS Fargate task with Nginx and Spring Boot, and RDS PostgreSQL 16. The AWS configuration is a deployment starting point and has been exercised locally with Docker. AWS provisioning and deployment require your account, domain and secrets.

## Provision the environment

1. Create two public subnets for the load balancer and private subnets for the tasks and RDS. Give private tasks egress to ECR, S3, Secrets Manager, and CloudWatch through NAT or VPC endpoints. Use the task security group as the only source allowed to reach PostgreSQL on port 5432.
2. Create a private RDS PostgreSQL 16 instance and database. Turn on backups, encryption, and deletion protection as appropriate for your environment. The JDBC URL uses `sslmode=verify-full` and the RDS trust root supplied by your chosen certificate installation path. Configure the dedicated database user with Flyway migration privileges or run migrations with a separate role before API startup.
3. Create ECR repositories `seatlock-api` and `seatlock-web`, CloudWatch log group `/ecs/seatlock`, and the task execution role. Grant the execution role permission to read only the injected SSM parameters and Secrets Manager values. Store the JDBC URL and username as SSM parameters and the database password as a Secrets Manager secret.
4. Configure your domain and certificate on the ALB HTTPS listener. Redirect the HTTP listener to HTTPS. Use an IP target group for the `web` container on port 80, with `/actuator/health` returning 200. Allow port 80 on the task security group from the ALB security group. Use the ALB subnet CIDR or VPC CIDR for the template's real-IP setting when security groups constrain the source. Keep the API listener on loopback within the task.

## Build and release

Set your ECR registry and image tag. Use a content-based tag or digest for immutable releases.

```sh
aws ecr get-login-password --region "$SEATLOCK_AWS_REGION" |
  docker login --username AWS --password-stdin "$SEATLOCK_ECR_REGISTRY"
docker buildx build --platform linux/arm64 --push -f infra/docker/api.Dockerfile \
  -t "$SEATLOCK_ECR_REGISTRY/seatlock-api:$SEATLOCK_IMAGE_TAG" .
docker buildx build --platform linux/arm64 --push -f infra/docker/web.Dockerfile \
  -t "$SEATLOCK_ECR_REGISTRY/seatlock-web:$SEATLOCK_IMAGE_TAG" .
```

Copy [the task definition](../../infra/aws/task-definition.json) to your deployment directory and fill the image, account, region, network, and secret placeholders. For the first deployment, inject `SEATLOCK_ADMIN_EMAIL` and `SEATLOCK_ADMIN_PASSWORD` from private parameters to provision an administrator. Remove those inputs after bootstrap. Register the task definition and create an ECS service of desired count one in the private subnets, attaching the web target group. Enable the ECS deployment circuit breaker and rollback.

Set ALB idle timeout above the periodic SSE heartbeat, and verify a request through the advertised HTTPS origin. Register a real customer, approve a test organizer, publish a test event, verify a hold and Razorpay checkout in staging, and export an operations snapshot. Keep the reference demo accounts disabled.

## Optional production services

Use ElastiCache Redis with a TLS endpoint for shared rate limits, and Amazon MQ for RabbitMQ with a private TLS endpoint for asynchronous delivery if broker-backed notifications are required. Inject `REDIS_URL` and `AMQP_URL` from Secrets Manager and enable the corresponding flags. Keep the database pool below the RDS connection budget across API and worker tasks.

For a small showcase installation, start with one task and PostgreSQL. The database inbox worker works without a broker. Scale API tasks and add dedicated workers when measured throughput warrants it. Keep logs in CloudWatch and route OTLP to a private collector if deploying tracing. Set alerts for RDS connections, API errors, failed outbox events, and booking latency.

[AWS ECS task definition parameters](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)

For paid checkout, inject merchant secrets into the API container and follow [Razorpay deployment setup](razorpay.md).
