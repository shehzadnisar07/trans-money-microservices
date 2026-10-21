package com.transmoney.accountservice.service;

import com.transmoney.accountservice.Entity.Account;
import com.transmoney.accountservice.Entity.AccountStatus;
import com.transmoney.accountservice.Entity.AccountType;
import com.transmoney.accountservice.Entity.DebitOperation;
import com.transmoney.accountservice.dto.AccountResponse;
import com.transmoney.accountservice.dto.CreateAccountRequest;
import com.transmoney.accountservice.repository.AccountRepository;
import com.transmoney.accountservice.repository.DebitOperationRepository;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor

public class AccountService {
    private final AccountRepository accountRepository;
    private final DebitOperationRepository debitOperationRepository;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /*
    * Create a new Account.
    *
    * */
    public AccountResponse createAccount(CreateAccountRequest request, String userEmail) {
        log.info("Creating an account for : {}" , userEmail);

        if(accountRepository.existsByEmail(userEmail)){
            throw new RuntimeException("Account already exists against this email: " + userEmail);
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(userEmail);
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVING
                ? new BigDecimal("100000")
                : new BigDecimal("500000")
        );

        Account savedAccount = accountRepository.save(account);

        log.info("Account created : " + savedAccount.getAccountNumber());

        return mapToResponse(savedAccount);
    }

    // generate unique 12 digits account number :
    private String generateAccountNumber() {
        String accountNumber ;
        do {
            long number = 1L + SECURE_RANDOM.nextLong(999999999999L);
            accountNumber = String.format("%012d", number);
        } while(accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account savedAccount) {
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setId(savedAccount.getId());
        accountResponse.setAccountNumber(savedAccount.getAccountNumber());
        accountResponse.setAccountHolderName(savedAccount.getAccountHolderName());
        accountResponse.setEmail(savedAccount.getEmail());
        accountResponse.setPhone(savedAccount.getPhone());
        accountResponse.setAccountType(savedAccount.getAccountType());
        accountResponse.setAccountStatus(savedAccount.getAccountStatus());
        accountResponse.setBalance(savedAccount.getBalance());
        accountResponse.setDailyTransactionLimit(savedAccount.getDailyTransactionLimit());
        accountResponse.setCreatedAt(savedAccount.getCreatedAt());

        return accountResponse;

    }

    /*
    * get Account , called by using account number .
    * */
    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()-> new RuntimeException("Account Not found!."));

        return mapToResponse(account);
    }

    public AccountResponse getAccountByEmail(String email) {
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(()-> new RuntimeException("Account Not found for this email."));

        return mapToResponse(account);
    }

    /*
    * Get account balance , called from controller using accNumber:
    * */
    public BigDecimal getBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()-> new RuntimeException("Account NOt Found."));
        return account.getBalance();
    }


/*
* Block Account - Called by Fraud detection Service via Kafka
* */
    public void blockAccount(String accountNumber) {

        log.info("Blocking the Account : {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()-> new RuntimeException("Account NOt Found."));
        account.setAccountStatus(AccountStatus.BLOCKED);

        accountRepository.save(account);
        log.info("Account Blocked Successfully : {}", accountNumber);


    }

    /*
    * -Deduct balance form sender Account.
    * - Called by Transaction
    * */
    @Transactional
    public void deductBalance(
            String accountNumber,
            BigDecimal amount ,
            String operationId) {

        log.info("Deduction amount of {} from Account : {}", amount, accountNumber);

        if(operationId == null || operationId.isBlank()){
            throw new IllegalArgumentException("Operation id is required.");
        }

        // Check weather this operation was already processed.

        Optional<DebitOperation> existingOperation = debitOperationRepository
                .findByOperationId(operationId);

        if(existingOperation.isPresent()){

            DebitOperation previous = existingOperation.get();

            // A repeated ID must refer to the same request.
            if(!previous.getAccountNumber().equals(accountNumber)|| previous.getAmount().compareTo(amount) != 0){
                throw new IllegalArgumentException("Operation id was already used for different debit.");
            }

            log.info("Debit operation {} was already processed", operationId);
            return;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()-> new RuntimeException("Account not found"));

        if(account.getAccountStatus()!= AccountStatus.ACTIVE){
            throw new RuntimeException("Account is not Active : " + accountNumber);
        }

        if(account.getBalance().compareTo(amount)<0){
            throw new RuntimeException("Insufficient balance for this transaction : " + accountNumber);
        }

        // Record the operation.
        DebitOperation operation = new DebitOperation();
        operation.setOperationId(operationId);
        operation.setAccountNumber(accountNumber);
        operation.setAmount(amount);

        debitOperationRepository.save(operation);

        account.setBalance(account.getBalance().subtract(amount));

        accountRepository.save(account);

        log.info("Successfully deducted balance from sender account as : {}",accountNumber);
    }


    /*
    * 1- Credit balance.
    * 2- Called by transaction service via Kafka.
    * */
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("Crediting balance of {} in Account : {}", amount, accountNumber);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()-> new RuntimeException("Account not found"));

        if (account.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Account is not Active : " + accountNumber);
        }

        account.setBalance(account.getBalance().add(amount));

        accountRepository.save(account);

        log.info("Successfully credited balance in receiver account as : {}",accountNumber);

    }

}
