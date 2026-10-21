# Confirmed Bugs, Security Vulnerabilities, and Architectural Risks

This document separates **Confirmed Bugs & Security Vulnerabilities** (verified with live runtime execution or build failures) from **Architectural & Operational Risks** (verified via static code analysis). Secrets are never printed.

---

## Part 1: Confirmed Bugs & Security Vulnerabilities

### `BUG-01` — Unprotected Internal Account Mutation Endpoints Exposed via Public API Gateway
- **Severity:** **Critical**
- **Confidence & Evidence:** Confirmed at runtime (`TC-ACC-07a`, `TC-ACC-07b`).
- **Location:**
  - `backend/api-gateway/src/main/resources/application.yaml` (lines 23–26: `Path=/api/v1/accounts/**`)
  - `backend/account-service/src/main/java/com/transmoney/accountservice/controller/AccountController.java` (lines 80–125: `getBalance`, `blockAccount`, `deductBalance`, `creditBalance`)
- **Reproduction Steps:**
  1. Authenticate as any regular user (`CUSTOMER`) and obtain a valid Bearer JWT.
  2. Send `PUT http://localhost:8080/api/v1/accounts/{anyAccountNumber}/credit?amount=99999` with `Authorization: Bearer <token>`.
  3. Send `GET http://localhost:8080/api/v1/accounts/{victimAccountNumber}/balance` or `PUT .../block` or `PUT .../deduct`.
- **Actual Result:** HTTP `200 OK` (`BALANCE CREDITED SUCCESSFULLY.` / `50000.00`).
- **Impact:** Complete financial compromise. Any logged-in user can credit unlimited money to any account, drain any user's balance, view any user's balance, or block any user's account through the public API Gateway.
- **Recommended Fix:**
  1. Move internal SAGA endpoints (`deduct`, `credit`, `block`, `balance`) to an internal path prefix (e.g., `/internal/v1/accounts/**`) that is **explicitly blocked** or not routed in `api-gateway`.
  2. Enforce network segmentation (bind internal services to internal Docker network/localhost) and require a shared internal service token or mTLS for `/internal/**` endpoints.
- **Regression Test Needed:** Integration test calling `PUT /api/v1/accounts/{acc}/credit`, `/deduct`, `/block`, and `GET /balance` via API Gateway with a `CUSTOMER` JWT and asserting `403 Forbidden` or `404 Not Found`.

---

### `BUG-02` — Broken Object-Level Authorization (IDOR) on All Transaction Query & OTP Endpoints
- **Severity:** **Critical**
- **Confidence & Evidence:** Confirmed at runtime (`TC-SECURITY-IDOR-01a`, `TC-SECURITY-IDOR-01b`).
- **Location:** `backend/transaction-service/src/main/java/com/transmoney/transactionservice/controller/TransactionController.java` (lines 35–61: `getTransaction`, `getTransactionHistory`, `verifyOTP`)
- **Reproduction Steps:**
  1. Authenticate as `qa.attacker`.
  2. Call `GET http://localhost:8080/api/v1/transactions/{victimTransactionId}` and `GET http://localhost:8080/api/v1/transactions/account/{victimAccountNumber}`.
- **Actual Result:** Both returned HTTP `200 OK` with the victim's full transaction details and history (4 records). Furthermore, `POST /api/v1/transactions/verify/{victimTransactionId}?otp=000000` has no ownership check—an attacker can intentionally submit a wrong OTP to trigger `blockAccountAndCompensate()` and **block the victim's bank account**!
- **Impact:** Full disclosure of any customer's transaction history and ability for an attacker to DoS/block any customer's account when a transaction is pending verification.
- **Recommended Fix:** Require `@RequestHeader("X-User-Email") String userEmail` on `getTransaction`, `getTransactionHistory`, and `verifyOTP`, and verify via `accountServiceClient` that `userEmail` owns the relevant account (`senderAccountNumber` for `verifyOTP`; `senderAccountNumber` or `receiverAccountNumber` for read endpoints).
- **Regression Test Needed:** API test verifying `403 Forbidden` when User B requests User A's transaction ID, account transaction history, or OTP verification endpoint.

---

### `BUG-03` — Transfer to Non-Existent Receiver Account Permanently Loses Sender's Money
- **Severity:** **Critical**
- **Confidence & Evidence:** Confirmed at runtime (`TC-TX-06`).
- **Location:**
  - `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionService.java` (lines 166–209, 346–368)
  - `backend/account-service/src/main/java/com/transmoney/accountservice/service/AccountEventConsumer.java` (lines 24–43)
- **Reproduction Steps:**
  1. Send `POST http://localhost:8080/api/v1/transactions/transfer` from sender `478783368699` (balance `49,000.00`) to non-existent `receiverAccountNumber: "999999999999"` for `amount: 500`.
  2. Wait 3 seconds and query `account_db.accounts` and `transaction_db.transactions`.
- **Actual Result:**
  - `TransactionService.transfer()` only validates the sender account (`accountServiceClient.getAccount(req.getSenderAccountNumber(), userEmail)`). It never checks whether `receiverAccountNumber` exists or is `ACTIVE`.
  - Sender's balance was deducted from `49,000.00` to `48,500.00`.
  - Fraud check passed (`fraud.check.clean`), and `TransactionService.completeTransaction()` marked the transaction (`b9054bfc-8173-4577-9524-b9fe4be386fb`) as **`COMPLETED`**!
  - When `AccountEventConsumer.consumeTransactionCompleted()` tried to credit `999999999999`, `accountService.creditBalance()` threw `RuntimeException("Account not found")`, with no SAGA compensation triggered. **Rs. 500 vanished permanently.**
- **Impact:** Permanent loss of customer funds whenever a user mistypes a 12-digit receiver account number or sends money to a `BLOCKED`/`CLOSED` receiver account.
- **Recommended Fix:**
  1. Validate that `receiverAccountNumber` exists and has `accountStatus == ACTIVE` before deducting the sender's balance in `TransactionService.transfer()`.
  2. Add a compensating failure event (e.g., `transaction.credit.failed`) if crediting the receiver fails asynchronously so the sender is refunded and the transaction is marked `FAILED`.
- **Regression Test Needed:** Integration test transferring to `999999999999` and to a `BLOCKED` receiver account, asserting HTTP `400`/`404` and unchanged sender balance.

---

### `BUG-04` — Failed Transfers Corrupt `IdempotencyRecord` State (False `201 Created` on Retry or Permanent Key Lockout)
- **Severity:** **Critical**
- **Confidence & Evidence:** Confirmed at runtime (`TC-TX-04a/b` and `TC-TX-08c/d`).
- **Location:** `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionService.java` (lines 118–195) and `entity/Transaction.java` (line 44)
- **Reproduction Steps:**
  - **Scenario A (False Success on Retry — `TC-TX-08c/d`):**
    1. Send `POST /api/v1/transactions/transfer` with `amount: 9999999` (exceeds balance) and `Idempotency-Key: E2E-INSUFF-1`. First request returns `500 Internal Server Error` when `accountServiceClient.deductBalance()` fails at line 189.
    2. However, lines 181–185 already saved `Transaction` (`status = PROCESSING`) and updated `idempotencyRecord.setTransactionId(savedTransaction.getId())`!
    3. Retry the exact same request with `Idempotency-Key: E2E-INSUFF-1`: it returns **`201 Created` with the un-deducted, dead `PROCESSING` transaction (`9dab8cd2-...`)**!
  - **Scenario B (Omitted Optional `description` Locks Key Forever — `TC-TX-04a/b`):**
    1. Send `POST /api/v1/transactions/transfer` without `description` (`null`, which `TransferReq.java` and `TransferPage.tsx` allow as optional).
    2. `idempotencyReservationService.reserve()` commits in `Propagation.REQUIRES_NEW` with `transaction_id = NULL`.
    3. `transactionRepository.save(transaction)` throws `DataIntegrityViolationException` because `Transaction.description` has `@Column(nullable = false)` (`Transaction.java:44`).
    4. Any subsequent retry with that `Idempotency-Key` permanently throws `IllegalStateException: A request with this idempotency key is already processing`.
- **Impact:** Retrying a failed transfer returns a fake `201 Created` (`PROCESSING`) transaction that never executes, or permanently locks the idempotency key; omitting the optional description crashes every transfer.
- **Recommended Fix:**
  1. Change `Transaction.description` to `@Column(nullable = true)` (or default null to `""`).
  2. Wrap pre-deduction failure paths in a `try/catch`: if `getAccount`, `save(transaction)`, or `deductBalance` fails, either delete the `IdempotencyRecord` reservation or mark the `Transaction` as `FAILED` so retries do not return a zombie `PROCESSING` transaction.
- **Regression Test Needed:** Test transfer with `description: null` (`201 Created`), and test retrying a failed insufficient-balance transfer with the same `Idempotency-Key`.

---

### `BUG-05` — Stray `@FeignClient` Annotation on `TransactionServiceApplicationTests` Breaks Maven Test Build
- **Severity:** **Critical (Build / CI Blocker)**
- **Confidence & Evidence:** Confirmed during Phase 2 (`TC-BUILD-04`, exit code `1`).
- **Location:** `backend/transaction-service/src/test/java/com/transmoney/transactionservice/TransactionServiceApplicationTests.java` (line 7)
- **Reproduction Steps:** Run `.\mvnw.cmd test` in `backend/transaction-service`.
- **Actual Result:** `Caused by: java.lang.IllegalArgumentException: @FeignClient can only be specified on an interface` at `FeignClientsRegistrar.java:195`.
- **Impact:** `transaction-service` cannot execute any JUnit tests or build without `-DskipTests`.
- **Recommended Fix:** Remove `@FeignClient` (and its import) from `TransactionServiceApplicationTests.java`.
- **Regression Test Needed:** Run `.\mvnw.cmd test` in `backend/transaction-service` and assert exit code `0`.

---

### `BUG-06` — Fraud Rule 3 (`isBalanceCheckFailed`) Uses Post-Deduction Balance Instead of Pre-Transfer Balance
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed via code inspection (`FraudDetectionService.java:52, 98, 161`) and runtime (`TC-FRAUD-01`, `TC-FRAUD-04`).
- **Location:** `backend/fraud-detection-service/src/main/java/com/transmoney/frauddetectionservice/service/FraudDetectionService.java` (lines 52, 98, 161–173)
- **Reproduction Steps:**
  1. Create an account with Rs. 10,000.
  2. Transfer Rs. 5,000 (`50%` of balance).
  3. Because `TransactionService.transfer()` deducts Rs. 5,000 *before* publishing `transaction.initiated`, `accountServiceClient.getBalance(senderAccountNumber)` in `FraudDetectionService` returns the remaining balance (**Rs. 5,000**), not **Rs. 10,000**.
  4. `isBalanceCheckFailed` checks `5000 > 5000 * 0.9 (4500)` -> `true`, flagging a 50% transfer as `"Transaction exceeds 90% of your account balance."`!
- **Impact:** Any transfer exceeding `0.9 / 1.9` (~`47.37%`) of a user's actual balance is falsely flagged for OTP verification.
- **Recommended Fix:** Compute pre-deduction balance as `BigDecimal originalBalance = senderBalance.add(amount);` before calling `isBalanceCheckFailed(originalBalance, amount)`, or include `preTransferBalance` in `TransactionInitiatedEvent`.
- **Regression Test Needed:** Unit test for `FraudDetectionService` where pre-transfer balance is `10,000` and transfer amount is `6,000` (`60%`), asserting `isBalanceCheckFailed` returns `false`.

---

### `BUG-07` — Frontend `OTPVerificationPage` Shows "Verification Successful" When OTP is Wrong or Expired
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed via runtime API response (`TC-FRAUD-02`) and frontend code (`OTPVerificationPage.tsx:24-26`).
- **Location:**
  - `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionService.java` (lines 267–283)
  - `frontend/src/features/transactions/OTPVerificationPage.tsx` (lines 23–29)
- **Reproduction Steps:**
  1. Trigger a `PENDING_VERIFICATION` transaction.
  2. Submit a wrong OTP (`000000`) to `POST /api/v1/transactions/verify/{transactionId}?otp=000000`.
- **Actual Result:** Backend returns **HTTP `200 OK`** with `{"status": "FLAGGED", "failureReason": "Wrong OTP entered -transaction cancelled.Account Block for security..."}`. Because HTTP status is `200`, `OTPVerificationPage.tsx` executes `setSuccess(true)` and displays **"Verification Successful — Your transaction is now being processed"** even though the transaction was cancelled and the user's account was just blocked!
- **Impact:** Completely misleads the user after a failed or expired OTP verification.
- **Recommended Fix:** Either return HTTP `400 Bad Request` / `422 Unprocessable Entity` from `verifyOTP()` when OTP is invalid/expired (keeping `@Transactional(noRollbackFor = ...)` so the compensation commits), or check `res.data.status === 'COMPLETED'` in `OTPVerificationPage.tsx` before setting `setSuccess(true)`.
- **Regression Test Needed:** Component/API test verifying that a wrong OTP displays the failure reason in red rather than `"Verification Successful"`.

---

### `BUG-08` — Self-Transfer Allowed (`senderAccountNumber == receiverAccountNumber`)
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed at runtime (`TC-TX-05`).
- **Location:** `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionService.java` (lines 61–171)
- **Reproduction Steps:** Send `POST /api/v1/transactions/transfer` where `senderAccountNumber` and `receiverAccountNumber` are both `478783368699`.
- **Actual Result:** Returns `201 Created` (`7d0fcc00-08ab-4bcd-ac09-232908facd76`), deducts Rs. 500, runs Kafka SAGA, and credits Rs. 500 back to the same account. Worse, if the self-transfer triggers OTP verification and the user doesn't verify it, their money stays deducted until OTP expires! Additionally, a user can artificially inflate their `fraud:avg_amount` in Redis via self-transfers to bypass Fraud Rule 2!
- **Impact:** Pollutes transaction history, manipulates Redis fraud-detection running averages, and can lock a user's own funds in `PENDING_VERIFICATION`.
- **Recommended Fix:** Reject transfers where `req.getSenderAccountNumber().equals(req.getReceiverAccountNumber())` with `400 Bad Request`.
- **Regression Test Needed:** API test asserting `400 Bad Request` when `senderAccountNumber == receiverAccountNumber`.

---

### `BUG-09` — Race Condition Between `fraud.detected` (Account Block) and SAGA Refund (`creditBalance`)
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed by code inspection (`TransactionService.java:324-339` and `AccountService.java:222-224`).
- **Location:**
  - `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionService.java` (lines 324–339: `blockAccountAndCompensate`)
  - `backend/account-service/src/main/java/com/transmoney/accountservice/service/AccountService.java` (lines 222–224: `creditBalance`)
- **Description:** In `blockAccountAndCompensate()`, `transaction-service` **first** publishes `fraud.detected` to Kafka (line 335, which triggers `AccountEventConsumer` to set `accountStatus = BLOCKED`) and **then** calls `compensateTransaction()` -> `accountServiceClient.creditBalance()` (line 300). However, `AccountService.creditBalance()` rejects non-active accounts (`if (account.getAccountStatus() != AccountStatus.ACTIVE) throw new RuntimeException("Account is not Active")`)! If the Kafka consumer processes `fraud.detected` before the HTTP Feign call to `creditBalance()` completes (or if `creditBalance` is retried), the SAGA refund throws an exception and **fails to refund the sender's money**!
- **Recommended Fix:**
  1. In `blockAccountAndCompensate()`, execute `compensateTransaction()` (the synchronous refund) **before** publishing `fraud.detected` to Kafka.
  2. Allow SAGA refund credits to succeed even if an account is `BLOCKED`, or create a dedicated `refundBalance()` method with idempotency protection (`operationId`) and pessimistic locking (`findByAccountNumberForUpdate`).

---

### `BUG-10` — `AccountService.creditBalance()` Lacks `@Transactional`, Pessimistic Locking, and Idempotency
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed via code inspection (`AccountService.java:212-232`).
- **Location:** `backend/account-service/src/main/java/com/transmoney/accountservice/service/AccountService.java` (lines 212–232)
- **Description:** Unlike `deductBalance()`, which uses `@Transactional`, `findByAccountNumberForUpdate()` (`PESSIMISTIC_WRITE`), and `DebitOperation` idempotency records, `creditBalance()` has:
  1. **No `@Transactional` annotation** and uses plain `findByAccountNumber()` (lost-update race condition under concurrent credits/debits).
  2. **No `operationId` / idempotency tracking**: `creditBalance()` is called by `AccountEventConsumer` on Kafka topic `transaction.completed` (at-least-once delivery). Any duplicate Kafka message delivery or consumer rebalance will **credit the receiver's account twice**!
- **Recommended Fix:** Annotate `creditBalance()` with `@Transactional`, lock the row with `findByAccountNumberForUpdate()`, and record a `CreditOperation` (keyed by `transactionId`) to make credits idempotent across Kafka redeliveries.

---

### `BUG-11` — Missing `@RestControllerAdvice` Across All Backend Services Turns Business & Validation Errors into `403` or `500`
- **Severity:** **High**
- **Confidence & Evidence:** Confirmed at runtime (`TC-AUTH-02`, `TC-AUTH-03`, `TC-AUTH-05`, `TC-ACC-03`, `TC-TX-03`, `TC-TX-07`).
- **Location:** `auth-service` (`SecurityConfig.java:28-31`, `AuthService.java`), `account-service`, `transaction-service`.
- **Description:**
  - In `auth-service`, `SecurityConfig` permits `/api/v1/auth/**` but requires authentication on `/error`. When `AuthService.login()` throws `RuntimeException("Invalid email or password")`, `AuthService.register()` throws `RuntimeException("Email already registered")`, or `@Valid` fails with `MethodArgumentNotValidException`, Spring forwards internally to `/error`, which Spring Security blocks with **`403 Forbidden` (empty body)**!
  - Consequently, `LoginPage.tsx:48` (which checks `status === 401 || status === 400`) and `RegisterPage.tsx:43` (which checks `status === 409 || status === 400`) never show specific error messages—they always display `"Something went wrong. Please try again later."`
  - In `account-service` and `transaction-service`, business exceptions (`IllegalArgumentException`, `IllegalStateException`, `RuntimeException`) return **`500 Internal Server Error`**.
- **Recommended Fix:**
  1. Permit `/error` in `auth-service/SecurityConfig.java` (`.requestMatchers("/api/v1/auth/**", "/error").permitAll()`).
  2. Add a `@RestControllerAdvice` in each service mapping domain exceptions to appropriate HTTP status codes (`400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`) with a structured JSON `{"message": "..."}` body.

---

### `BUG-12` — Direct Microservice Ports (`8081`, `8082`, `8084`, `8085`, `8086`) Allow Bypassing API Gateway Authentication & Rate Limiting
- **Severity:** **Medium / High**
- **Confidence & Evidence:** Confirmed at runtime (`TC-AUTH-09`).
- **Location:** `account-service` (`:8081`), `transaction-service` (`:8082`), `fraud-detection-service` (`:8084`).
- **Reproduction Steps:** Send `GET http://localhost:8081/api/v1/accounts/478783368699` with header `X-User-Email: qa.sender.1792612835@test.local` and **no JWT**.
- **Actual Result:** HTTP `200 OK` with full account details.
- **Recommended Fix:** Bind internal services to `127.0.0.1` / internal Docker network only, and verify a signed gateway header or internal token on downstream services.

---

### `BUG-13` — Hardcoded `JWT_SECRET` in `backend/.idea/workspace.xml`
- **Severity:** **Medium**
- **Confidence & Evidence:** Confirmed via static file inspection (`backend/.idea/workspace.xml`, lines 143 and 155).
- **Location:** `backend/.idea/workspace.xml:143, 155`
- **Description:** While `.gitignore` ignores root `.idea/`, `backend/.idea/workspace.xml` contains a hardcoded Base64 `JWT_SECRET` inside IntelliJ `RunManager` configurations.
- **Recommended Fix:** Add `**/.idea/` to `.gitignore`, remove `JWT_SECRET` from `backend/.idea/workspace.xml`, and rotate the development secret.

---

### `BUG-14` — `Account.email` Lacks a Database `UNIQUE` Constraint & `dailyTransactionLimit` is Never Enforced
- **Severity:** **Medium**
- **Confidence & Evidence:** Confirmed via MySQL `SHOW CREATE TABLE accounts` and `Account.java:33-34`, `AccountService.java:48-52`.
- **Location:** `backend/account-service/src/main/java/com/transmoney/accountservice/Entity/Account.java` (line 33) and `service/AccountService.java` (lines 36, 48–52, 137–205)
- **Description:**
  1. `Account.email` is `@Column(nullable = false)` without `unique = true`. Concurrent `POST /api/v1/accounts` requests for the same email can bypass `accountRepository.existsByEmail(userEmail)` and insert two accounts for one user, breaking `getAccountByEmail()` (`NonUniqueResultException`).
  2. `Account.dailyTransactionLimit` (`100,000` for `SAVING`, `500,000` for `CURRENT`) is stored on account creation but is **never checked or enforced** in `deductBalance()` or `TransactionService.transfer()`.
- **Recommended Fix:** Add `unique = true` to `Account.email` and enforce `dailyTransactionLimit` in `deductBalance()` or `transfer()`.

---

### `BUG-15` — Cryptographically Weak PRNG (`Math.random()`) Used for Security OTP Generation & OTP Logged in Plaintext
- **Severity:** **Medium**
- **Confidence & Evidence:** Confirmed via code inspection (`TransactionEventConsumer.java:82`, `NotificationService.java:42`).
- **Location:** `backend/transaction-service/src/main/java/com/transmoney/transactionservice/service/TransactionEventConsumer.java` (line 82)
- **Description:** `String.format("%06d", (int) (Math.random()*900000 + 100000))` uses `Math.random()` instead of `java.security.SecureRandom`.
- **Recommended Fix:** Use a static `SecureRandom` instance (`100000 + SECURE_RANDOM.nextInt(900000)`).

---

### `BUG-16` — Minor Frontend UI Defects & Documentation Port Mismatch
- **Severity:** **Low**
- **Confidence & Evidence:** Confirmed via code inspection (`TransactionHistoryPage.tsx:176`, `ProtectedLayout.tsx:15`, `README.md:21-26`).
- **Details:**
  1. `frontend/src/features/transactions/TransactionHistoryPage.tsx:176` renders transaction amounts with `$` (`$${tx.amount.toFixed(2)}`) while all other pages (`DashboardPage`, `TransferPage`, `TransactionDetailPage`) use `Rs.`.
  2. `frontend/src/layouts/ProtectedLayout.tsx:15` labels the `/notifications` sidebar link as **`Investments`** instead of **`Notifications`**.
  3. `README.md:21-26` lists `auth-service` (`8081`), `account-service` (`8082`), `transaction-service` (`8083`), and directory `fraud-service` (`8084`), whereas the actual ports are `8086`, `8081`, `8082`, and directory `fraud-detection-service` (`8084`).

---

## Part 2: Architectural & Reliability Risks

| Risk ID | Risk Description | Impact & Recommendation |
| :--- | :--- | :--- |
| **RISK-01** | **Swallowed Exceptions in Kafka Consumers (`TransactionEventConsumer.java:107-109`, `NotificationService.java`)** | `consumeVerificationRequired` catches `Exception` and only logs `log.error(...)` without rethrowing. If Redis or MySQL is momentarily unavailable when `verification.required` arrives, the Kafka offset is committed anyway and the transaction remains stuck in `PROCESSING` forever. Rethrow exceptions or configure a `DefaultErrorHandler` with Dead-Letter Topic (`DeadLetterPublishingRecoverer`). |
| **RISK-02** | **Dual-Write Without Transactional Outbox (`TransactionService.java:189-206`)** | `deductBalance()` (HTTP) and `kafkaTemplate.send("transaction.initiated", ...)` are not atomic. If the process crashes or Kafka is unreachable after line 189, sender funds are deducted with no Kafka event emitted. Use the Transactional Outbox pattern or a scheduled reconciliation job for stuck `PROCESSING` transactions. |
| **RISK-03** | **NullPointerException Risk in `RateLimitConfig.java:15-20`** | `exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()` can throw NPE if `getRemoteAddress()` is null. Add a null-safe fallback. |
