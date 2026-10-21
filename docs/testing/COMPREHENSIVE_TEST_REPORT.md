# Comprehensive End-to-End Test Report — TransMoney Microservices

**Date Executed:** 2026-10-22 (Local Time: UTC+05:00)  
**Repository:** `d:\Tutorials\transmoney-microservice`  
**Branch:** `main` (existing uncommitted changes in `AccountService.java` and `.idea/misc.xml` preserved intact)

---

## 1. Executive Summary

A full-stack static, build, runtime, integration, SAGA, security, and concurrency testing audit was conducted on the **TransMoney** microservices platform (`api-gateway`, `auth-service`, `account-service`, `transaction-service`, `fraud-detection-service`, `notification-service`, and the `React + Vite` frontend).

### Overall Testing Verdict: **CONDITIONAL FAIL (Critical Security, SAGA Integrity, and Error-Handling Bugs Discovered)**

While core happy-path workflows—user registration, BCrypt password hashing, JWT generation/validation, account creation, pessimistic-locked debit idempotency (`AccountService.deductBalance`), asynchronous Kafka clean transfer SAGA, Redis-backed fraud detection rules (velocity, 3x average amount, balance ratio), OTP verification, and SAGA compensation—function at runtime, **16 confirmed bugs and security vulnerabilities** (including **5 Critical** and **6 High** severity issues) were discovered and reproduced with concrete database and HTTP evidence:

1. **Critical Security Vulnerability (`BUG-01`) — Unprotected Internal Endpoints Exposed via API Gateway:** `PUT /api/v1/accounts/{accountNumber}/credit`, `/deduct`, `/block`, and `GET /balance` are routed under `/api/v1/accounts/**` by `api-gateway` without ownership or role checks. Any authenticated user can mint unlimited funds into their own account (`PUT /credit?amount=99999` returned `200 OK`) or drain/block any other user's account.
2. **Critical Security Vulnerability (`BUG-02`) — Missing Ownership Checks (IDOR) in `TransactionController`:** `GET /api/v1/transactions/{transactionId}`, `GET /api/v1/transactions/account/{accountNumber}`, and `POST /api/v1/transactions/verify/{transactionId}` do not validate `X-User-Email`. Any authenticated user can read any account's full transaction history (`200 OK`) or intentionally submit a wrong OTP to block a victim's account.
3. **Critical SAGA Data-Loss Bug (`BUG-03`) — Unvalidated Receiver Account Causes Permanent Sender Balance Loss:** `TransactionService.transfer()` never checks whether `receiverAccountNumber` exists before deducting the sender's balance. Transferring Rs. 500 to non-existent account `999999999999` deducted Rs. 500 from the sender (`49,000.00 -> 48,500.00`), transitioned the transaction to `COMPLETED`, and then failed silently inside `AccountEventConsumer.consumeTransactionCompleted` (`RuntimeException: Account not found`), permanently losing the funds with no SAGA compensation.
4. **Critical Idempotency Bug (`BUG-04`) — Failed Transfers Corrupt Idempotency State:** Because `IdempotencyReservationService.reserve()` commits in `Propagation.REQUIRES_NEW` and `TransactionService.transfer()` is not `@Transactional`:
   - If `deductBalance()` fails (e.g., insufficient balance), `Transaction` is already saved in `PROCESSING` status and linked to the `IdempotencyRecord`. Retrying with the same `Idempotency-Key` returns `201 Created` with the `PROCESSING` transaction as if the transfer succeeded (`TC-TX-08d`).
   - If `description` is omitted (`null`), `transactionRepository.save()` fails on `Column 'description' cannot be null`, leaving `transaction_id = NULL` in `idempotency_records` and permanently locking that `Idempotency-Key` in `"A request with this idempotency key is already processing"` (`TC-TX-04`).
5. **Critical Test Suite Bug (`BUG-05`) — `@FeignClient` Annotation on Test Class Breaks `transaction-service` Build:** `TransactionServiceApplicationTests.java` has a stray `@FeignClient` annotation on a concrete class (`line 7`), causing `mvnw test` to fail with `java.lang.IllegalArgumentException: @FeignClient can only be specified on an interface`.

---

## 2. Exact Environment and Versions

| Component / Tool | Discovered Version / Configuration |
| :--- | :--- |
| **OS** | Windows 10/11 (x64), PowerShell |
| **Java (Default PATH)** | OpenJDK Temurin `21.0.12.1+1-LTS` |
| **Java (`JAVA_HOME` used by Maven Wrapper)** | JDK `25.0.4` (`C:\Program Files\Java\jdk-25.0.4`) |
| **Spring Boot Parent** | `4.1.1` (Spring Framework `7.0.9`, Apache Tomcat `11.0.24`, Hibernate ORM) |
| **Spring Cloud** | `2025.1.3` (`api-gateway`, `transaction-service`) / `2025.1.2` (`fraud-detection-service`) |
| **Node.js / npm** | Node `v22.18.0` / npm `10.9.3` |
| **Frontend Stack** | React `19.2.8`, Vite `8.3.4`, TypeScript `~6.0.2`, Oxlint `1.81.0`, Tailwind CSS `3.4.14`, Zustand `5.0.0`, Zod `3.23.8` |
| **Docker Infrastructure** | `banking-mysql` (`mysql:8.0`, port `3306`), `banking-redis` (`redis:7`, port `6379`), `banking-zookeeper` (`confluentinc/cp-zookeeper:7.5.0`, port `2181`), `banking-kafka` (`confluentinc/cp-kafka:7.5.0`, port `9092`), `transmoney-postgres` (`postgres:17`, port `5432` — unused by current services) |

---

## 3. Discovered Services, Actual Ports, Databases, and Kafka Topics

> [!WARNING]
> **Documentation vs. Code Mismatch:** `README.md` lists `auth-service` (`8081`), `account-service` (`8082`), `transaction-service` (`8083`), and directory `fraud-service` (`8084`). The actual configuration in `application.yaml` across the codebase is shown below.

| Service Directory | `spring.application.name` | Actual Port | README Port | Database / Storage | Outbound Clients / Dependencies |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `backend/api-gateway` | `api-gateway` | **`8080`** | `8080` | Redis (`localhost:6379`) for `RequestRateLimiter` | Routes to `:8086` (`auth`), `:8081` (`account`), `:8082` (`transaction`) |
| `backend/account-service` | `account-service` | **`8081`** | `8082` | MySQL `account_db` (`accounts`, `debit_operations`) | Kafka consumer (`account-service-group`) |
| `backend/transaction-service` | `transaction-service` | **`8082`** | `8083` | MySQL `transaction_db` (`transactions`, `idempotency_records`), Redis (`localhost:6379`) | Feign -> `account-service` (`:8081`); Kafka producer & consumer (`transaction-service`) |
| `backend/fraud-detection-service` | `fraud-detection-service` | **`8084`** | `8084` | Redis (`localhost:6379`) | Feign -> `account-service` (`:8081`); Kafka producer & consumer (`fraud-detection-service`) |
| `backend/notification-service` | `notification-service` | **`8085`** | `8085` | None (Console Logger) | Kafka consumer (`notification-service`) |
| `backend/auth-service` | `auth-service` | **`8086`** | `8081` | MySQL `banking` (`users`) | None |
| `frontend` | `frontend` | **`5173`** | `5173` | `localStorage` (`auth-storage`) | Axios -> `http://localhost:8080` |

### Kafka Event Topology Discovered in Code

| Kafka Topic | Producer Service | Consumer Service (`groupId`) | Payload Type & Schema |
| :--- | :--- | :--- | :--- |
| `transaction.initiated` | `transaction-service` | `fraud-detection-service` (`fraud-detection-service`) | `TransactionInitiatedEvent` (`transactionId`, `senderAccountNumber`, `receiverAccountNumber`, `amount`, `description`) |
| `fraud.check.clean` | `fraud-detection-service` | `transaction-service` (`transaction-service`) | `Map` (`transactionId`, `isFraud: false`, `reason: null`) |
| `verification.required` | `fraud-detection-service` | `transaction-service` (`transaction-service`) | `Map` (`transactionId`, `senderAccountNumber`, `amount`, `reason`) |
| `otp.required` | `transaction-service` | `notification-service` (`notification-service`) | `Map` (`transactionId`, `senderAccountNumber`, `amount`, `reason`, `otpNumber`) |
| `transaction.completed` | `transaction-service` | `account-service` (`account-service-group`) & `notification-service` (`notification-service`) | `TransactionCompletedEvent` (`transactionId`, `senderAccountNumber`, `receiverAccountNumber`, `amount`, `description`) |
| `fraud.detected` | `transaction-service` | `account-service` (`account-service-group`) & `notification-service` (`notification-service`) | `Map` (`transactionId`, `senderAccountNumber`, `amount`, `reason`) |
| `transaction.refunded` | `transaction-service` | `notification-service` (`notification-service`) | `Map` (`transactionId`, `senderAccountNumber`, `amount`, `reason`) |

---

## 4. Phase 2 — Existing Automated Tests and Build Checks

### 4.1 Backend Maven Test Results

| Service | Command Executed | Exit Code | Tests Run | Passed | Failed / Errors | Notes / Failure Root Cause |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| `api-gateway` | `.\mvnw.cmd test` | `0` | 1 | 1 | 0 | `ApiGatewayApplicationTests.contextLoads` (requires `JWT_SECRET` env var) |
| `auth-service` | `.\mvnw.cmd test` | `0` | 1 | 1 | 0 | `AuthServiceApplicationTests.contextLoads` (requires MySQL + `JWT_SECRET`) |
| `account-service` | `.\mvnw.cmd test` | `0` | 1 | 1 | 0 | `AccountServiceApplicationTests.contextLoads` (requires MySQL) |
| `transaction-service` | `.\mvnw.cmd test` | **`1`** | 1 | 0 | **1 Error** | **FAIL:** `TransactionServiceApplicationTests` has `@FeignClient` on the test class (`line 7`), causing `IllegalArgumentException: @FeignClient can only be specified on an interface`. |
| `fraud-detection-service` | `.\mvnw.cmd test` | `0` | 1 | 1 | 0 | `FraudDetectionServiceApplicationTests.contextLoads` |
| `notification-service` | `.\mvnw.cmd test` | `0` | 1 | 1 | 0 | `NotificationServiceApplicationTests.contextLoads` |

### 4.2 Frontend Checks (`frontend/package.json`)

| Check | Command Executed | Exit Code | Result & Details |
| :--- | :--- | :---: | :--- |
| **Linter (`oxlint`)** | `npm run lint` | `0` | **0 errors, 9 warnings** (`no-unused-vars` in `jwt.ts`, `ProfilePage.tsx`, `TransactionHistoryPage.tsx`, `TransactionDetailPage.tsx`, `TransferPage.tsx`; `react(immutability)` in `DashboardPage.tsx`, `TransactionDetailPage.tsx`, `TransferPage.tsx`; `react(refs)` in `TransferPage.tsx:205`). |
| **TypeScript + Vite Build** | `npm run build` (`tsc -b && vite build`) | `0` | **PASS:** Built 1,650 modules in `2.68s` (`dist/assets/index-BdRLVkfu.js` 451.65 kB). |
| **Unit / E2E Test Script** | *(None in `package.json`)* | N/A | No frontend test runner (Vitest/Jest/Playwright) is configured in `frontend/package.json`. |

---

## 5. Summary of Runtime Functional, SAGA, Security, and Concurrency Tests

A total of **42 test cases** (6 build/static checks + 36 runtime API, DB, Kafka SAGA, Redis, security, and concurrency tests) were defined and evaluated:
- **PASS:** **24** (Met expected functional/security behavior)
- **FAIL:** **14** (Confirmed application bugs, security vulnerabilities, or HTTP status contract failures reproduced with evidence)
- **PARTIAL:** **2** (Code-inspected + API-verified for frontend workflows; no headless browser automation tool is available in this environment)
- **BLOCKED:** **1** (`transaction-service` JUnit test suite blocked by `@FeignClient` on `TransactionServiceApplicationTests`)
- **NOT RUN:** **1** (Destructive broker-kill during live database write skipped to protect existing local Kafka volume state; analyzed via code inspection)

### Key Runtime Evidence Highlights

1. **Debit Idempotency & Pessimistic Locking (`TC-IDEMP-01`, `TC-CONC-01`, `TC-CONC-02`):**
   - Sequential replay of `operationId=TX-IDEMPOTENCY-TEST-001` (`amount=1000`) on a Rs. 5,000 test account deducted only once (`balance = 4000.00`, `1` row in `debit_operations`).
   - Reusing `TX-IDEMPOTENCY-TEST-001` with `amount=500` was rejected (`IllegalArgumentException`, HTTP `500`), preserving `balance = 4000.00`.
   - Firing **5 simultaneous `PUT /deduct` requests of Rs. 1,000** (`CONC-DISTINCT-1..5`) against a Rs. 2,000 test account (`785618266979`) resulted in **exactly 2 HTTP `200`s and 3 HTTP `500`s**, ending with `balance = 0.00` (zero overdraft).
   - Firing **5 simultaneous `PUT /deduct` requests of Rs. 500 with the SAME `operationId`** (`CONC-SAME-OP-1792612835`) against a Rs. 2,000 balance resulted in **5 HTTP `200`s** and deducted **only Rs. 500 once** (`balance = 1500.00`, `op_count = 1`).

2. **Clean End-to-End Transfer SAGA & Idempotent Replay (`TC-TX-01`, `TC-TX-02`):**
   - Transferring Rs. 1,000 from `478783368699` (initial `50,000.00`) to `787474381412` (initial `20,000.00`) with `Idempotency-Key: E2E-CLEAN-TX-1-1792612835` returned `201 Created` (`status: PROCESSING`).
   - Within 3 seconds, Kafka SAGA (`transaction.initiated` -> `fraud.check.clean` -> `transaction.completed`) transitioned the transaction (`88954685-1caf-4b28-8018-b8215c13c270`) to `COMPLETED` (`completed_at = 2026-10-22 01:00:57.379722`), updated sender balance to `49,000.00`, and credited receiver balance to `21,000.00`.
   - Replaying the exact same transfer request with `Idempotency-Key: E2E-CLEAN-TX-1-1792612835` returned the original `COMPLETED` transaction (`88954685-1caf-4b28-8018-b8215c13c270`) without double-deducting or double-crediting (`49,000.00` and `21,000.00` unchanged; `idemp_count = 1`).

3. **Fraud Detection, Redis OTP Verification, and SAGA Compensation (`TC-FRAUD-01`, `TC-FRAUD-02`, `TC-FRAUD-03`):**
   - **Valid OTP Flow (`TC-FRAUD-01`):** Transferring Rs. 6,000 from account `765565280093` (initial `10,000.00`) triggered the balance-percentage fraud rule (`6000 > 4000 * 0.9`), transitioning transaction `f059d3a7-e812-4049-a20d-0d6314a9e6b6` to `PENDING_VERIFICATION` and storing a 6-digit OTP in Redis (`verification-otp:f059d3a7-...`). Submitting the valid OTP via `POST /api/v1/transactions/verify/{id}?otp=...` transitioned the transaction to `COMPLETED` and credited the receiver (`21,000.00 -> 27,000.00`).
   - **Wrong OTP SAGA Compensation & Account Block (`TC-FRAUD-02`):** Initiating a Rs. 2,500 transfer (`92a1ec4a-4a73-4739-b249-019c98e139bc`) from `765565280093` (balance `4,000.00 -> 1,500.00`) and submitting a wrong OTP (`000000`) triggered SAGA compensation: Rs. 2,500 was refunded (`balance = 4,000.00`), transaction status became `FLAGGED`, and `account-service` consumed `fraud.detected` and updated `account_status` to `BLOCKED`.
   - **Velocity Limit (`TC-FRAUD-03`):** Submitting 6 transfers of Rs. 100 within 60 seconds from `velAcc` (`10,000.00` initial balance) caused the 6th transfer to be flagged as `PENDING_VERIFICATION` (`fraud-velocity<acc> > 5`).

4. **API Gateway Rate Limiting & CORS (`TC-GW-RATE-01`, `TC-GW-CORS-01`):**
   - `OPTIONS /api/v1/auth/login` with `Origin: http://localhost:5173` returned `200 OK` with `Access-Control-Allow-Origin: http://localhost:5173`.
   - Firing 30 rapid concurrent requests to `GET /api/v1/accounts/me` through `api-gateway` (`replenishRate: 10, burstCapacity: 20`) enforced Redis rate limiting (`HTTP 429 Too Many Requests` returned once burst capacity was exceeded).

---

## 6. Post-Test Cleanup Verification

All temporary QA test accounts (`qa.*.1792612835@test.local`, `debit.idempotency.test@local.dev`), test transactions, and test idempotency/debit records were cleaned up after execution. Verification queries confirmed that all **8 pre-existing accounts** in `account_db.accounts`, **8 pre-existing users** in `banking.users`, and **10 pre-existing transactions** in `transaction_db.transactions` remain 100% untouched.

---

## 7. Known Limitations

1. **No Browser Automation Tooling Installed:** Neither Playwright/Cypress nor a browser MCP tool is installed in this workspace, so frontend UI flows were verified via TypeScript compilation (`tsc -b`), production bundling (`vite build`), static code audit of all React components/stores/schemas, and live execution of the exact HTTP requests/payloads sent by `authApi.ts`, `transactionApi.ts`, and `client.ts` against `api-gateway` (`localhost:8080`).
2. **Notification Delivery is Console-Only:** `notification-service` logs alerts to stdout (`sendAlert`) rather than sending real emails/SMS or persisting notifications to a database, and `NotificationsPage.tsx` renders static `MOCK_NOTIFICATIONS`.
