package com.transmoney.transactionservice.service;

import com.transmoney.transactionservice.client.AccountServiceClient;
import com.transmoney.transactionservice.dto.AccountResponse;
import com.transmoney.transactionservice.dto.TransactionResponse;
import com.transmoney.transactionservice.dto.TransferReq;
import com.transmoney.transactionservice.entity.IdempotencyRecord;
import com.transmoney.transactionservice.entity.Transaction;
import com.transmoney.transactionservice.entity.TransactionStatus;
import com.transmoney.transactionservice.entity.TransactionType;
import com.transmoney.transactionservice.event.TransactionCompletedEvent;
import com.transmoney.transactionservice.event.TransactionInitiatedEvent;
import com.transmoney.transactionservice.repository.IdempotencyRecordRepository;
import com.transmoney.transactionservice.repository.TransactionRepository;
import com.transmoney.transactionservice.utils.RequestHashUtil;
import feign.FeignException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Data
@AllArgsConstructor
@Slf4j
@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyReservationService idempotencyReservationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private final String FRAUD_DETECTED_TOPIC = "fraud.detected";

    /*
     * SAGA STEP -1: Initiate transfer
     * Deduct from sender account via feign
     * Saves transaction as PROCESSING
     * Publish event to Kafka for fraud check
     */
    public TransactionResponse transfer(
            TransferReq req,
            String userEmail,
            String idempotencyKey) {

        // validate idempotency Key:
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RuntimeException("Idempotency Key is required");
        }

        // Generate request data to be hashed:
        String reqData = String.join("|",
                req.getSenderAccountNumber(),
                req.getReceiverAccountNumber(),
                req.getAmount().toString(),
                req.getDescription() == null ? "" : req.getDescription()
        );

        // Generating hash value by calling RequestHashUtil.
        String reqHash = RequestHashUtil.generateHash(reqData);

        // Check whether the user already used this key:
        Optional<IdempotencyRecord> existingRecord =
                idempotencyRecordRepository.findByUserEmailAndIdempotencyKey(
                        userEmail,
                        idempotencyKey);

        if (existingRecord.isPresent()) {
            IdempotencyRecord idempotencyRecord = existingRecord.get();

            // 1. Reject reuse of same key for different request:
            if (!idempotencyRecord.getReqHash().equals(reqHash)) {
                throw new RuntimeException("Idempotency key was already used with different request:");
            }

            // 2. Handle an existing request without a transaction ID
            if (idempotencyRecord.getTransactionId() == null) {
                throw new IllegalStateException(
                        "A request with this idempotency key is already processing"
                );
            }

            // 3. Returning the original transaction record not deduct again.
            Transaction originalTransaction = transactionRepository
                    .findById(idempotencyRecord.getTransactionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Original transaction not found with id :" +
                                    idempotencyRecord.getTransactionId()
                    ));

            return mapToResponse(originalTransaction);
        }

        // Reserve the keys before processing:
        IdempotencyRecord idempotencyRecord;
        try {
            idempotencyRecord = idempotencyReservationService.reserve(
                    userEmail,
                    idempotencyKey,
                    reqHash
            );
        } catch (DataIntegrityViolationException ex) {
            Optional<IdempotencyRecord> existing =
                    idempotencyRecordRepository
                            .findByUserEmailAndIdempotencyKey(
                                    userEmail,
                                    idempotencyKey
                            );

            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();

                if (!record.getReqHash().equals(reqHash)) {
                    throw new IllegalStateException(
                            "Idempotency key was already used with a different request."
                    );
                }

                if (record.getTransactionId() == null) {
                    throw new IllegalStateException(
                            "A request with this idempotency key is already processing."
                    );
                }

                Transaction originalTransaction =
                        transactionRepository.findById(record.getTransactionId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Original transaction not found: "
                                                + record.getTransactionId()
                                ));

                return mapToResponse(originalTransaction);
            }

            // The error wasn't explained by an existing idempotency key.
            throw ex;
        }

        log.info("SAGA STARTED: Transfer : {} -> {} amount : {}", req.getSenderAccountNumber(), req.getReceiverAccountNumber(), req.getAmount());

        AccountResponse senderAccount;
        try {
            senderAccount = accountServiceClient.getAccount(
                    req.getSenderAccountNumber(),
                    userEmail
            );
        } catch (FeignException.Forbidden e) {
            throw new AccessDeniedException("Access denied");
        }

        if (senderAccount == null || senderAccount.getEmail() == null || !senderAccount.getEmail().equalsIgnoreCase(userEmail)) {
            throw new AccessDeniedException("Access denied");
        }

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(req.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(req.getReceiverAccountNumber());
        transaction.setAmount(req.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(req.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);

        // saved transactionId to idempotency record:
        idempotencyRecord.setTransactionId(savedTransaction.getId());
        idempotencyRecordRepository.save(idempotencyRecord);

        // SAGA STEP 1: deduct from sender account.
        accountServiceClient.deductBalance(
                req.getSenderAccountNumber(),
                req.getAmount(),
                savedTransaction.getId());

        log.info("Transaction saved as PROCESSING: {} ", transaction.getId());

        // SAGA STEP 2 - Publish for fraud check
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription());

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, event.getTransactionId(), event);
        log.info("SAGA STEP 2 -> transactionInitiatedEvent published : {} ", savedTransaction.getId());

        return mapToResponse(savedTransaction);
    }

    private TransactionResponse mapToResponse(Transaction savedTransaction) {
        TransactionResponse response = new TransactionResponse();
        response.setId(savedTransaction.getId());
        response.setSenderAccountNumber(savedTransaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(savedTransaction.getReceiverAccountNumber());
        response.setAmount(savedTransaction.getAmount());
        response.setType(savedTransaction.getType());
        response.setStatus(savedTransaction.getStatus());
        response.setDescription(savedTransaction.getDescription());
        response.setFailureReason(savedTransaction.getFailureReason());
        response.setReferenceNumber(savedTransaction.getReferenceNumber());
        response.setCreatedAt(savedTransaction.getCreatedAt());
        response.setCompletedAt(savedTransaction.getCompletedAt());

        return response;
    }

    private boolean isAccountOwnedByUser(String accountNumber, String userEmail) {
        if (accountNumber == null || userEmail == null || userEmail.isBlank()) {
            return false;
        }
        try {
            AccountResponse account = accountServiceClient.getAccount(accountNumber, userEmail);
            return account != null && account.getEmail() != null && account.getEmail().equalsIgnoreCase(userEmail);
        } catch (Exception e) {
            return false;
        }
    }

    public TransactionResponse getTransaction(String transactionId, String userEmail) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        boolean isSender = isAccountOwnedByUser(transaction.getSenderAccountNumber(), userEmail);
        boolean isReceiver = !isSender && isAccountOwnedByUser(transaction.getReceiverAccountNumber(), userEmail);

        if (!isSender && !isReceiver) {
            throw new AccessDeniedException("Access denied");
        }

        return mapToResponse(transaction);
    }

    public TransactionResponse getTransaction(String transactionId) {
        return getTransaction(transactionId, null);
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber, String userEmail) {
        if (!isAccountOwnedByUser(accountNumber, userEmail)) {
            throw new AccessDeniedException("Access denied");
        }

        return transactionRepository.findBySenderAccountNumberOrReceiverAccountNumberOrderByCreatedAtDesc(accountNumber, accountNumber)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return getTransactionHistory(accountNumber, null);
    }

    public TransactionResponse verifyOTP(String transactionId, String otp, String userEmail) {
        log.info("Verifying the otp to continue transaction : {}.", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        // Reject unauthorized users before checking transaction state or Redis OTP
        if (!isAccountOwnedByUser(transaction.getSenderAccountNumber(), userEmail)) {
            throw new AccessDeniedException("Access denied");
        }

        // OTP should only be accepted when the transaction is actually waiting for OTP verification.
        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new RuntimeException("Transaction is not waiting for OTP verification.");
        }

        // Fetch OTP value from Redis:
        String otpkey = "verification-otp:" + transactionId;
        String redisOtp = redisTemplate.opsForValue().get(otpkey);

        if (redisOtp == null) {
            log.warn("OTP expired for transaction : {}", transactionId);
            compensateTransaction(transaction, "OTP was expired -transaction cancelled and amount refunded.");

            return mapToResponse(transaction);
        }

        if (!redisOtp.equals(otp)) {
            log.warn("Wrong OTP -Blocking account and refunding : {} ", transactionId);
            redisTemplate.delete(otpkey);
            blockAccountAndCompensate(transaction,
                    "Wrong OTP entered -transaction cancelled." +
                            "Account Block for security.");

            return mapToResponse(transaction);
        }

        // OTP correct - complete transaction.
        log.info("OTP verified -transaction is completing : {}", transactionId);
        redisTemplate.delete(otpkey);
        completeTransaction(transaction);

        return mapToResponse(transaction);
    }

    public TransactionResponse verifyOTP(String transactionId, String otp) {
        return verifyOTP(transactionId, otp, null);
    }

    private void compensateTransaction(Transaction transaction, String reason) {
        log.warn(
                "SAGA compensation -refunding amount of {} in account : {}",
                transaction.getAmount(), transaction.getSenderAccountNumber());

        // Money credit back to sender account
        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(), transaction.getAmount());
        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason + "SAGA -compensation completed, amount refunded at : " + LocalDateTime.now());

        // Save changes to repo as well.
        transactionRepository.save(transaction);

        // Publish refund event - Notification will alert user.
        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);

        log.info("Compensation completed : {} refunded to : {}", transaction.getAmount(), transaction.getSenderAccountNumber());
    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {
        // 1- Publish fraud.detected event - account service will block account.
        Map<String, Object> fraudDetected = new HashMap<>();
        fraudDetected.put("transactionId", transaction.getId());
        fraudDetected.put("senderAccountNumber", transaction.getSenderAccountNumber());
        fraudDetected.put("amount", transaction.getAmount());
        fraudDetected.put("reason", reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getId(), fraudDetected);

        // It will refund the sender and save it.
        compensateTransaction(transaction, reason);
    }

    private void completeTransaction(Transaction transaction) {
        /*
         * 1- set Transaction.Status as "COMPLETED"
         * 2- set Transaction.completed time
         * 3- save transaction to db.
         */
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompletedEvent transactionCompletedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription());

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), transactionCompletedEvent);

        log.info("Transaction COMPLETED - Transaction {} completed.", transaction.getId());
    }

    public void processCleanResult(String transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found " + transactionId));

        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.info("Transaction -> {} not PROCESSING - Skipping", transactionId);
            return;
        }

        completeTransaction(transaction);
    }
}
