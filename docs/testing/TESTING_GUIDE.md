# TransMoney Microservices — Safe Local Testing Guide

This guide documents how to safely start, test, verify, and clean up the **TransMoney** microservices platform on a local development machine without modifying or deleting existing user data.

---

## 1. Prerequisites

- **Java:** JDK 21+ (JDK 25 for `account-service`, `transaction-service`, `notification-service` as configured in their `pom.xml` files, or pass `-Djava.version=21`)
- **Node.js & npm:** Node.js `v18+` (tested with `v22.18.0`, npm `10.9.3`)
- **Docker Desktop:** Required for `banking-mysql` (`3306`), `banking-redis` (`6379`), `banking-zookeeper` (`2181`), and `banking-kafka` (`9092`)

---

## 2. Service Port Map & Startup Order

> [!IMPORTANT]
> Always use the actual configured ports from `src/main/resources/application.yaml` rather than `README.md`:

| Startup Order | Component | Directory | Port | Required Environment Variables |
| :---: | :--- | :--- | :---: | :--- |
| **1** | **MySQL 8.0** | `backend/docker-compose.yml` (`banking-mysql`) | `3306` | `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD` |
| **2** | **Redis 7** | `backend/docker-compose.yml` (`banking-redis`) | `6379` | *(None)* |
| **3** | **Zookeeper** | `backend/docker-compose.yml` (`banking-zookeeper`) | `2181` | *(None)* |
| **4** | **Kafka** | `backend/docker-compose.yml` (`banking-kafka`) | `9092` | *(Depends on Zookeeper)* |
| **5** | **Auth Service** | `backend/auth-service` | `8086` | `AUTH_DB_PASSWORD`, `JWT_SECRET` (Base64 >= 256-bit) |
| **6** | **Account Service** | `backend/account-service` | `8081` | `ACCOUNT_DB_PASSWORD` |
| **7** | **Transaction Service** | `backend/transaction-service` | `8082` | `TRANSACTION_DB_PASSWORD` |
| **8** | **Fraud Detection Service** | `backend/fraud-detection-service` | `8084` | *(Connects to Redis `:6379`, Kafka `:9092`, Account `:8081`)* |
| **9** | **Notification Service** | `backend/notification-service` | `8085` | *(Connects to Kafka `:9092`)* |
| **10** | **API Gateway** | `backend/api-gateway` | `8080` | `JWT_SECRET` (must match `auth-service`) |
| **11** | **Frontend (Vite)** | `frontend` | `5173` | `VITE_API_BASE_URL=http://localhost:8080` |

### Starting Infrastructure Containers

```powershell
docker start banking-mysql banking-redis banking-zookeeper
Start-Sleep -Seconds 5
docker start banking-kafka
docker ps
```

### Starting Backend Services (PowerShell)

```powershell
# Set required environment variables in your session (do NOT hardcode real secrets in git):
$env:JWT_SECRET = "<YOUR_BASE64_ENCODED_256_BIT_SECRET>"
$env:AUTH_DB_PASSWORD = "<YOUR_MYSQL_USER_PASSWORD>"
$env:ACCOUNT_DB_PASSWORD = "<YOUR_MYSQL_ROOT_PASSWORD>"
$env:TRANSACTION_DB_PASSWORD = "<YOUR_MYSQL_ROOT_PASSWORD>"

# Run each service in its directory:
cd d:\Tutorials\transmoney-microservice\backend\auth-service
.\mvnw.cmd spring-boot:run
```

---

## 3. Running Build & Automated Test Suites

### Backend Maven Tests

```powershell
# Run in each backend/<service-name> directory:
.\mvnw.cmd test
```
> **Note:** Until `BUG-05` (`@FeignClient` on `TransactionServiceApplicationTests.java:7`) is fixed, `transaction-service` requires removing `@FeignClient` from `TransactionServiceApplicationTests.java` for `.\mvnw.cmd test` to pass.

### Frontend Lint, Typecheck, and Build

```powershell
cd d:\Tutorials\transmoney-microservice\frontend
npm run lint
npm run build
```

---

## 4. Dedicated Test-Data Setup (Safe Isolation Pattern)

Never use or modify existing accounts in `account_db.accounts`. Always create dedicated test users with a unique timestamp or prefix (`qa.test.<timestamp>@test.local`):

```powershell
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$email = "qa.test.$ts@test.local"

# 1. Register test user via API Gateway
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/auth/register" `
  -ContentType "application/json" `
  -Body (@{ email = $email; password = "Password123!" } | ConvertTo-Json)

# 2. Login to obtain JWT
$login = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/auth/login" `
  -ContentType "application/json" `
  -Body (@{ email = $email; password = "Password123!" } | ConvertTo-Json)
$token = $login.token

# 3. Create dedicated test banking account
$account = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/accounts" `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body (@{
    accountHolderName = "QA Dedicated Test User"
    phone = "03000000000"
    accountType = "CURRENT"
    initialDeposit = 5000
  } | ConvertTo-Json)
$accountNumber = $account.accountNumber
```

---

## 5. Database & Redis Verification Queries (Credentials Omitted)

### Verify Account Balances & Debit Idempotency (`account_db`)

```sql
USE account_db;

-- Check dedicated test account balance and status
SELECT id, account_number, account_holder_name, email, account_type, account_status, balance
FROM accounts
WHERE email LIKE 'qa.test.%@test.local';

-- Check debit operation records and unique index enforcement
SELECT id, operation_id, account_number, amount, created_at
FROM debit_operations
WHERE operation_id = 'TX-IDEMPOTENCY-TEST-001';

SHOW INDEX FROM debit_operations;
```

### Verify SAGA Transactions & Idempotency Records (`transaction_db`)

```sql
USE transaction_db;

-- Check transaction status transitions and failure reasons
SELECT id, sender_account_number, receiver_account_number, amount, status, failure_reason, created_at, completed_at
FROM transactions
WHERE sender_account_number = '<TEST_ACCOUNT_NUMBER>';

-- Check idempotency key reservations
SELECT id, user_email, idempotency_key, request_hash, status, transaction_id, created_at
FROM idempotency_records
WHERE user_email LIKE 'qa.test.%@test.local';
```

### Verify Redis Fraud Counters & OTP Keys

```powershell
# Check pending 6-digit OTP for a transaction in PENDING_VERIFICATION
docker exec banking-redis redis-cli GET "verification-otp:<TRANSACTION_ID>"

# Check fraud velocity counter (60s TTL) and running average amount
docker exec banking-redis redis-cli GET "fraud-velocity<ACCOUNT_NUMBER>"
docker exec banking-redis redis-cli GET "fraud:avg_amount<ACCOUNT_NUMBER>"
```

---

## 6. Safe Cleanup Instructions (Preserving All Pre-Existing Data)

Always scope `DELETE` statements strictly to your `qa.test.%@test.local` email prefix and test `account_number` so pre-existing accounts and users are never touched:

```sql
-- 1. Clean up transaction_db test records
USE transaction_db;
DELETE FROM idempotency_records
WHERE user_email LIKE 'qa.test.%@test.local';

DELETE FROM transactions
WHERE sender_account_number IN (
    SELECT account_number FROM account_db.accounts WHERE email LIKE 'qa.test.%@test.local'
);

-- 2. Clean up account_db test records
USE account_db;
DELETE FROM debit_operations
WHERE account_number IN (
    SELECT account_number FROM accounts WHERE email LIKE 'qa.test.%@test.local'
);

DELETE FROM accounts
WHERE email LIKE 'qa.test.%@test.local';

-- 3. Clean up banking.users test records
USE banking;
DELETE FROM users
WHERE email LIKE 'qa.test.%@test.local';
```
